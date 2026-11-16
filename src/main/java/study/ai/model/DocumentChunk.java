package study.ai.model;

/** 一段可被检索的资料，同时保留它在原文中的位置。 */
public record DocumentChunk(
        String id,
        String fileName,
        String heading,
        String content,
        int startLine,
        int endLine
) {
}
