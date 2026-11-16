package study.ai;

import org.junit.jupiter.api.Test;
import study.ai.exception.InvalidQuestionException;
import study.ai.model.DocumentChunk;
import study.ai.service.ContextAssembler;
import study.ai.service.KeywordRetriever.ScoredChunk;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {
    private ScoredChunk candidate(String id, String content) {
        return new ScoredChunk(new DocumentChunk(id, "notes.md", "标题", content, 3, 5), 3);
    }

    @Test
    void deduplicatesAndNumbersOnlyActuallySelectedSources() {
        var first = candidate("a", "候选键定义");
        var second = candidate("b", "```sql\nSELECT 1;\n```");
        var context = new ContextAssembler(8000).assemble("什么是候选键？",
                List.of(first, first, candidate("copy", "候选键定义"), second));
        assertTrue(context.canCallModel());
        assertEquals(List.of(1, 2), context.citations().stream().map(c -> c.number()).toList());
        assertEquals(List.of("a", "b"), context.citations().stream().map(c -> c.chunk().id()).toList());
        assertTrue(context.userPrompt().contains("[2]\n文件：notes.md"));
        assertTrue(context.userPrompt().contains(second.chunk().content()));
        assertFalse(context.userPrompt().contains("[3]"));
        assertEquals(context.systemPrompt().length() + context.userPrompt().length(), context.usedCharacters());
    }

    @Test
    void allowsExactBudgetAndSkipsOversizedBlockWithoutTruncation() {
        var shortChunk = candidate("short", "短正文");
        int exact = new ContextAssembler(8000).assemble("问题", List.of(shortChunk)).usedCharacters();
        var context = new ContextAssembler(exact).assemble("问题",
                List.of(candidate("long", "长".repeat(9000)), shortChunk));
        assertEquals(exact, context.usedCharacters());
        assertEquals("short", context.citations().get(0).chunk().id());
        assertEquals(1, context.citations().get(0).number());
        assertFalse(context.userPrompt().contains("长"));
        assertFalse(new ContextAssembler(exact - 1).assemble("问题", List.of(shortChunk)).canCallModel());
    }

    @Test
    void doesNotAuthorizeCallForEmptyOrIneligibleSourcesAndRejectsOversizedQuestion() {
        var assembler = new ContextAssembler(8000);
        assertFalse(assembler.assemble("问题", List.of()).canCallModel());
        assertFalse(assembler.assemble("问题", List.of(candidate("", "正文"), candidate("a", " "))).canCallModel());
        assertThrows(InvalidQuestionException.class, () -> assembler.assemble("问".repeat(8001), List.of()));
        assertThrows(InvalidQuestionException.class, () -> assembler.assemble(" ", List.of()));
        assertThrows(InvalidQuestionException.class, () -> assembler.assemble(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextAssembler(0));
    }
}
