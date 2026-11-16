package study.ai.model;

import java.util.List;


public record PreparedContext(
        String systemPrompt,
        String userPrompt,
        //问题与选中资料
        List<ContextCitation> citations,
        int usedCharacters,
        int maxCharacters
) {
    public PreparedContext {
        citations = List.copyOf(citations);
        //把传入列表变成不可变列表
    }

    public boolean canCallModel() {
        return !citations.isEmpty();
    }

    public record ContextCitation(int number, DocumentChunk chunk, int score) {
    }
}
