package study.ai.model;

import java.util.List;
import study.ai.model.PreparedContext.ContextCitation;

public record AnswerResponse(
        String status,
        String answer,
        String message,
        List<ContextCitation> citations,
        List<Integer> citedNumbers
) {
    public AnswerResponse {
        citations = List.copyOf(citations);
        citedNumbers = List.copyOf(citedNumbers);
    }
}
