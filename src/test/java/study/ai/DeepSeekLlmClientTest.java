package study.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import study.ai.model.PreparedContext;
import study.ai.service.DeepSeekLlmClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekLlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient.Builder wire = RestClient.builder().baseUrl("https://api.deepseek.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(wire).build();

    private DeepSeekLlmClient client(String key) {
        // 保留真正RestClient的序列化/HTTP处理，仅由Mock服务器替代网络与超时工厂。
        RestClient.Builder configured = mock(RestClient.Builder.class, RETURNS_SELF);
        when(configured.build()).thenReturn(wire.defaultHeader("Authorization", "Bearer " + key).build());
        return new DeepSeekLlmClient(configured, mapper, key, "deepseek-v4-pro", 1024, 5, 60);
    }

    private PreparedContext context() {
        return new PreparedContext("系统规则", "问题与资料[1]", List.of(
                new PreparedContext.ContextCitation(1, null, 3)), 12, 8000);
    }

    private String envelope(String answer, String finish) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of(
                "finish_reason", finish, "message", java.util.Map.of("content", answer)))));
    }

    @Test
    void sendsOfficialModelAndStrictJsonInstructionsAndParsesAnswer() throws Exception {
        var client = client("test-key");
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").value(1024))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("JSON")))
                .andExpect(jsonPath("$.messages[1].content").value("问题与资料[1]"))
                .andRespond(withSuccess(envelope("{\"answer\":\"定义[1]\",\"insufficientEvidence\":false}", "stop"), MediaType.APPLICATION_JSON));
        assertEquals("定义[1]", client.generate(context()).answer());
        server.verify();
    }

    @Test
    void rejectsTruncatedMalformedAndWrongTypeResponses() throws Exception {
        var client = client("test-key");
        for (String raw : List.of("not json", "{}",
                envelope("{\"answer\":\"部分[1]\",\"insufficientEvidence\":false}", "length"),
                envelope("{\"answer\":\"说明[1]\",\"insufficientEvidence\":\"false\"}", "stop"))) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
            assertNull(client.generate(context()));
            server.verify();
        }
    }

    @Test
    void preservesExplicitInsufficiencyAndPropagatesHttpFailureWithoutRetry() throws Exception {
        var client = client("test-key");
        server.expect(anything()).andRespond(withSuccess(envelope(
                "{\"answer\":\"\",\"insufficientEvidence\":true}", "stop"), MediaType.APPLICATION_JSON));
        assertTrue(client.generate(context()).insufficientEvidence());
        server.verify();
        server.reset();
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThrows(org.springframework.web.client.RestClientResponseException.class, () -> client.generate(context()));
        server.verify();
    }

    @Test
    void failsFastWithoutKey() {
        assertThrows(IllegalArgumentException.class, () -> client(""));
    }
}
