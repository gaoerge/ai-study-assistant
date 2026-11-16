package study.ai.service;

import study.ai.model.PreparedContext;

//定义调用模型的统一入口
@FunctionalInterface
//@FunctionalInterface 表示它只有一个抽象方法，所以测试可以用 Lambda 临时提供客户端
public interface LlmClient {
    LlmResult generate(PreparedContext context);

    record LlmResult(String answer, boolean insufficientEvidence) {
    }
}
