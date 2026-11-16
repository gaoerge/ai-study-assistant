package study.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import study.ai.model.PreparedContext;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "study.llm.mode", havingValue = "deepseek")
public class DeepSeekLlmClient implements LlmClient {
    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final int maxTokens;
    private static final String JSON_INSTRUCTION = """
            输出必须是JSON对象，不加Markdown围栏，恰好包含：
            {"answer":"带[编号]的回答", "insufficientEvidence":false}。
            资料不足时返回{"answer":"", "insufficientEvidence":true}。
            """;

    public DeepSeekLlmClient(RestClient.Builder builder, ObjectMapper mapper,
            @Value("${study.llm.deepseek.api-key:}") String apiKey,
            @Value("${study.llm.deepseek.model:deepseek-v4-pro}") String model,
            @Value("${study.llm.deepseek.max-tokens:1024}") int maxTokens,
            @Value("${study.llm.deepseek.connect-timeout-seconds:5}") int connectSeconds,
            @Value("${study.llm.deepseek.read-timeout-seconds:60}") int readSeconds) {
        if (apiKey.isBlank()) throw new IllegalArgumentException("DeepSeek模式需要配置DEEPSEEK_API_KEY");

        if (model.isBlank() || maxTokens < 1 || maxTokens > 4096 || connectSeconds < 1 || readSeconds < 1) {
            throw new IllegalArgumentException("DeepSeek模型或超时/输出预算配置无效（输出上限1至4096）");
        }

        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectSeconds)).build());

        factory.setReadTimeout(Duration.ofSeconds(readSeconds));

        this.http = builder.requestFactory(factory).baseUrl("https://api.deepseek.com")
                .defaultHeader("Authorization", "Bearer " + apiKey).build();

        this.mapper = mapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public LlmResult generate(PreparedContext context) {
        if (!context.canCallModel()) return new LlmResult("", true);
        // 供应商协议适配留在客户端；不改动已保存的上下文或来源映射。
        String raw = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "stream", false,
                        "thinking", Map.of("type", "disabled"),
                        "max_tokens", maxTokens, "response_format", Map.of("type", "json_object"),
                        "messages", List.of(
                                Map.of("role", "system", "content", context.systemPrompt() + JSON_INSTRUCTION),
                                Map.of("role", "user", "content", context.userPrompt()))))
                .retrieve().body(String.class);
        if (raw == null || raw.isBlank()) return null;
        try {
            var root = mapper.readTree(raw);
            if (root == null) return null;
            var choice = root.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) return null;
            var content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) return null;
            var result = mapper.readTree(content.asText());
            if (result == null || !result.isObject() || result.size() != 2
                    || !result.path("answer").isTextual() || !result.path("insufficientEvidence").isBoolean()) return null;
            return new LlmResult(result.path("answer").asText(), result.path("insufficientEvidence").asBoolean());
        } catch (JsonProcessingException e) {
            // 格式错误交QaService判invalid_model_response；HTTP/超时异常交其判model_error。
            return null;
        }
    }
}
