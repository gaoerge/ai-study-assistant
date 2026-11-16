package study.ai.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import study.ai.model.PreparedContext;

@Service
@ConditionalOnProperty(name = "study.llm.mode", havingValue = "fake", matchIfMissing = true)
//@ConditionalOnProperty：配置为 fake，或者没写这项配置时，才启用它。
public class FakeLlmClient implements LlmClient {
    @Override
    public LlmResult generate(PreparedContext context) {

        if (!context.canCallModel()) return new LlmResult("", true);

        return new LlmResult("【Fake演示】调用链已接通，可查看本次资料[1]。此内容不是知识问答答案。", false);
    }
}
