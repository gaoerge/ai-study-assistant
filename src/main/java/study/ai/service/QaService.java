package study.ai.service;

import org.springframework.stereotype.Service;
import study.ai.model.AnswerResponse;
import study.ai.model.PreparedContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class QaService {
    private static final Pattern CITATION = Pattern.compile("\\[([0-9]+)\\]");
    //提取并验证引用编号。
    private final KeywordRetriever retriever;
    private final ContextAssembler assembler;
    private final LlmClient client;

    public QaService(KeywordRetriever retriever, ContextAssembler assembler, LlmClient client) {
        this.retriever = retriever;
        this.assembler = assembler;
        this.client = client;
    }

    public AnswerResponse answer(String question) {
        PreparedContext context = assembler.assemble(question, retriever.search(question, 5));
        //检索并组装
        if (!context.canCallModel()) {
            return response("insufficient_evidence", "", "没有可发送的资料，未调用模型。", context, List.of());
        }
        //没有可发送资料，直接结束
        LlmClient.LlmResult result;
        try {
            result = client.generate(context);
        } catch (RuntimeException e) {
            return response("model_error", "", "模型调用失败，可先查看候选资料。", context, List.of());
        }
        //调用客户端，处理调用失败
        if (result == null) return invalid(context);
        if (result.insufficientEvidence()) {
            return response("insufficient_evidence", "", "模型报告资料不足，可查看候选资料。", context, List.of());
        }
        String answer = result.answer();
        if (answer == null || answer.isBlank()) return invalid(context);
        //处理空结果，资料不足和空回答
        var allowed = context.citations().stream()
                            .map(c -> c.number()).collect(Collectors.toSet());

        var cited = new LinkedHashSet<Integer>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return invalid(context);
            }
            if (!allowed.contains(number)) return invalid(context);
            cited.add(number);
        }
        if (cited.isEmpty()) return invalid(context);
        return response("answered", answer, "引用编号已校验；是否支持回答仍需核对原文。", context, List.copyOf(cited));
    }

    private AnswerResponse invalid(PreparedContext context) {
        return response("invalid_model_response", "", "模型响应为空、缺少引用或引用编号无效。", context, List.of());
    }

    private AnswerResponse response(String status, String answer, String message,
                                    PreparedContext context, List<Integer> cited) {
        return new AnswerResponse(status, answer, message, context.citations(), cited);
    }
}
