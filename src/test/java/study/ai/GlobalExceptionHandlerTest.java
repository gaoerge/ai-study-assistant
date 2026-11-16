package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai.controller.AnswerController;
import study.ai.controller.DocumentController;
import study.ai.exception.ReindexException;
import study.ai.handler.GlobalExceptionHandler;
import study.ai.service.ContextAssembler;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;
import study.ai.service.LlmClient;
import study.ai.service.QaService;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private MockMvc mvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void validationHasSameContractAcrossBothQuestionControllers() throws Exception {
        var index = new DocumentIndexService("unused");
        var retriever = new KeywordRetriever(index);
        var qa = mock(QaService.class);
        var mvc = mvc(new AnswerController(qa), new DocumentController(index, retriever));
        for (String endpoint : new String[]{"/questions/answer", "/questions/search"}) {
            for (String body : new String[]{"{\"question\":\" \"}", "{}", "{\"question\":null}"}) {
                mvc.perform(post(endpoint).contentType("application/json").content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.status").value("invalid_request"))
                        .andExpect(jsonPath("$.message").value("问题不能为空"))
                        .andExpect(jsonPath("$.retainedChunkCount").doesNotExist());
            }
        }
        verifyNoInteractions(qa);
    }

    @Test
    void overBudgetQuestionIsKnownInputFailureAndDoesNotCallModel() throws Exception {
        var index = new DocumentIndexService("unused");
        var client = mock(LlmClient.class);
        var qa = new QaService(new KeywordRetriever(index), new ContextAssembler(8000), client);
        mvc(new AnswerController(qa))
                .perform(post("/questions/answer").contentType("application/json")
                        .content("{\"question\":\"" + "问".repeat(8001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("问题和固定说明已超过上下文预算"));
        verifyNoInteractions(client);
    }

    @Test
    void malformedOrMissingJsonHasSafeConsistentResponse() throws Exception {
        var qa = mock(QaService.class);
        var mvc = mvc(new AnswerController(qa));
        for (String body : new String[]{"", "{bad json", "{\"question\":[]}", "null"}) {
            mvc.perform(post("/questions/answer").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("invalid_request"))
                    .andExpect(jsonPath("$.message").value("请求体缺失或JSON格式不正确"))
                    .andExpect(jsonPath("$.exception").doesNotExist());
        }
        verifyNoInteractions(qa);
    }

    @Test
    void frameworkMethodAndMediaStatusesAndHeadersArePreserved() throws Exception {
        var qa = mock(QaService.class);
        var mvc = mvc(new AnswerController(qa));
        mvc.perform(get("/questions/answer"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.status").value("invalid_request"));
        mvc.perform(post("/questions/answer").contentType("text/plain").content("问题"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value("invalid_request"));
        verifyNoInteractions(qa);
    }

    @Test
    void unexpectedProgrammingFailureIs500AndDetailsStayInServerLog(CapturedOutput output) throws Exception {
        var qa = mock(QaService.class);
        when(qa.answer(any())).thenThrow(new IllegalArgumentException("internal-sensitive-marker"));
        mvc(new AnswerController(qa))
                .perform(post("/questions/answer").contentType("application/json")
                        .content("{\"question\":\"合法问题\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("internal_error"))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"))
                .andExpect(content().string(not(containsString("internal-sensitive-marker"))))
                .andExpect(jsonPath("$.retainedChunkCount").doesNotExist());
        assertTrue(output.getAll().contains("internal-sensitive-marker"));
    }

    @Test
    void reindexFailureLogsCauseAndRetainsItsExistingPublicFields(CapturedOutput output) throws Exception {
        var index = mock(DocumentIndexService.class);
        when(index.rebuildWithReport()).thenThrow(new ReindexException(7,
                new IOException("private-file-marker")));
        mvc(new DocumentController(index, mock(KeywordRetriever.class)))
                .perform(post("/documents/reindex"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("reindex_failed"))
                .andExpect(jsonPath("$.retainedChunkCount").value(7))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(content().string(not(containsString("private-file-marker"))));
        assertTrue(output.getAll().contains("private-file-marker"));
    }
}
