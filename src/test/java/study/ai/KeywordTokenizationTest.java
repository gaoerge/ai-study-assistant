package study.ai;

import org.junit.jupiter.api.Test;
import study.ai.model.DocumentChunk;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过公开检索结果中的解释验证分词，不暴露生产私有方法。 */
class KeywordTokenizationTest {
    private static KeywordRetriever retriever(String... contents) {
        var index = mock(DocumentIndexService.class);
        var chunks = java.util.stream.IntStream.range(0, contents.length)
                .mapToObj(i -> new DocumentChunk("c" + i, "fixture.md", "", contents[i], i + 1, i + 1))
                .toList();
        when(index.allChunks()).thenReturn(chunks);
        return new KeywordRetriever(index, 0);
    }

    private static List<String> terms(String question) {
        // 原串本身包含所有有效词项；用它作片段仅为取得公开的queryTerms。
        var hits = retriever(question).search(question, 5);
        return hits.isEmpty() ? List.of() : hits.get(0).explanation().queryTerms();
    }

    @Test
    void separatesChineseRunsFromCompleteEnglishTerms() {
        assertEquals(List.of("mysql", "连接", "接为", "为何", "何使", "使用", "null"),
                terms("MySQL连接为何使用NULL"));
        assertEquals(List.of("自然", "然连", "连接"), terms("自然连接"));
        assertEquals(List.of("mysql", "连接"), terms("MySQL连接MySQL"));
    }

    @Test
    void preservesIdentifiersNumbersAndAlphanumericNames() {
        assertEquals(List.of("requires_new", "与原", "原有", "有事", "事务", "mysql8", "5000", "c2"),
                terms("REQUIRES_NEW与原有事务MySQL8 5000 C2"));
        assertEquals(List.of("null", "is", "null_value"), terms("NULL IS null_value"));
    }

    @Test
    void punctuationAndScriptTransitionsNeverProduceCrossBoundaryBigrams() {
        assertEquals(List.of("主键", "外键", "mysql", "连接", "left", "join", "null", "true", "5000"),
                terms("主键/外键（MySQL）连接 @LEFT-JOIN\nNULL=TRUE，5000🙂"));
        assertEquals(List.of("mysql", "连接"), terms("主🙂键MySQL连接"));
        assertEquals(List.of("ab", "cd"), terms("_AB__CD_"));
    }

    @Test
    void repeatedTermsRemainDistinctAndTiedResultsKeepIndexOrder() {
        var results = retriever("mysql", "mysql mysql", "连接", "无关")
                .search("MySQL连接 MYSQL 连接", 2);
        assertEquals(List.of("c0", "c1"), results.stream().map(r -> r.chunk().id()).toList());
        assertEquals(List.of(1, 1), results.stream().map(KeywordRetriever.ScoredChunk::score).toList());
        assertEquals(List.of("mysql", "连接"), results.get(0).explanation().queryTerms());
        assertEquals(List.of("mysql"), results.get(0).explanation().matchedTerms());
        assertEquals(0, results.get(0).explanation().titleBonus());
    }

    @Test
    void completeEnglishTermsDoNotMatchScatteredTwoLetterFragments() {
        var results = retriever("tr ns ac ct ti io on re eq es", "transactional requires_new")
                .search("Transactional与REQUIRES_NEW", 5);
        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunk().id());
        assertEquals(2, results.get(0).score());
        assertEquals(List.of("transactional", "requires_new"), results.get(0).explanation().queryTerms());
        // 本轮仍是contains匹配；完整词项也可能是更长标识符的一部分，尚非英文整词匹配。
        assertEquals(1, retriever("mysql8").search("mysql", 5).size());
    }

    @Test
    void preservesExistingMinimumLengthAndHandlesEmptyOrDelimiterOnlyQuestions() {
        for (String question : List.of("", "  ", "，？！/@🙂", "A 1 中", "A中B")) {
            assertTrue(retriever("a 1 中 ab mysql").search(question, 5).isEmpty(), question);
        }
        assertEquals(List.of("ab", "12", "中文"), terms("a ab 1 12 中 中文"));
    }
}
