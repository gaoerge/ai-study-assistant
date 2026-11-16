package study.ai;

import org.junit.jupiter.api.Test;
import study.ai.model.DocumentChunk;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅用构造资料验证固定七词表；不调用模型，不修改开发题集。 */
class KeywordStopWordsTest {
    private DocumentIndexService index(DocumentChunk... chunks) {
        var index = mock(DocumentIndexService.class);
        when(index.allChunks()).thenReturn(List.of(chunks));
        return index;
    }

    private DocumentChunk chunk(String id, String heading, String content) {
        return new DocumentChunk(id, "fixture.md", heading, content, 1, 1);
    }

    @Test
    void allSevenTermsAreExplainedButNeverContributeToAnyScore() {
        String ignored = "请问 什么 怎么 如何 为何 为什 有什";
        var index = index(chunk("noise", ignored, ignored),
                chunk("answer", ignored + " MYSQL", ignored + " mysql"));
        var results = new KeywordRetriever(index, 1).search(ignored + " MySQL 请问 MYSQL", 5);
        assertEquals(1, results.size());
        var result = results.get(0);
        assertEquals("answer", result.chunk().id());
        var e = result.explanation();
        assertEquals(List.of("请问", "什么", "怎么", "如何", "为何", "为什", "有什", "mysql"), e.queryTerms());
        assertEquals(List.of("请问", "什么", "怎么", "如何", "为何", "为什", "有什"), e.ignoredQueryTerms());
        assertEquals(List.of("mysql"), e.effectiveQueryTerms());
        assertEquals(List.of("mysql"), e.matchedTerms());
        assertEquals(List.of("mysql"), e.titleMatches());
        assertEquals(List.of("mysql"), e.bodyMatches());
        assertEquals(1, e.baseScore());
        assertEquals(1, e.titleBonus());
        assertEquals(2, result.score());
    }

    @Test
    void filtersAfterTokenizationWithoutRemovingTextOrJoiningNewNeighbors() {
        String question = "MySQL为什么使用NULL";
        var result = new KeywordRetriever(index(chunk("a", "", question)))
                .search(question, 5).get(0);
        var e = result.explanation();
        assertEquals(List.of("mysql", "为什", "什么", "么使", "使用", "null"), e.queryTerms());
        assertEquals(List.of("为什", "什么"), e.ignoredQueryTerms());
        assertEquals(List.of("mysql", "么使", "使用", "null"), e.effectiveQueryTerms());
        assertEquals(e.effectiveQueryTerms(), e.matchedTerms());
        assertEquals(4, result.score());
    }

    @Test
    void preservesNegationRangeOperationsDomainTermsAndEnglishSqlWords() {
        String question = "没有 不能 全部 至少 区别 删除 保留 主键 连接 NULL IS 有效";
        var result = new KeywordRetriever(index(chunk("a", "", question)))
                .search(question, 5).get(0);
        var e = result.explanation();
        assertEquals(List.of("没有", "不能", "全部", "至少", "区别", "删除", "保留",
                "主键", "连接", "null", "is", "有效"), e.effectiveQueryTerms());
        assertEquals(e.queryTerms(), e.effectiveQueryTerms());
        assertTrue(e.ignoredQueryTerms().isEmpty());
        assertEquals(12, result.score());
    }

    @Test
    void allIgnoredTermsReturnNoCandidatesWithoutFallback() {
        var index = index(chunk("noise", "请问 为什么", "怎么 如何 为何 有什"));
        var retriever = new KeywordRetriever(index, 1);
        for (String question : List.of("请问", "为什么", "请问 什么 怎么 如何 为何 为什 有什")) {
            assertTrue(retriever.search(question, 5).isEmpty(), question);
        }
    }

    @Test
    void repeatedStopWordsDoNotAffectDistinctScoresStableTiesOrLimit() {
        var index = index(chunk("first", "", "mysql"),
                chunk("second", "请问 什么", "mysql mysql"), chunk("third", "", "mysql"));
        var results = new KeywordRetriever(index, 0).search("请问 MySQL 什么 MYSQL 请问", 2);
        assertEquals(List.of("first", "second"), results.stream().map(r -> r.chunk().id()).toList());
        assertEquals(List.of(1, 1), results.stream().map(KeywordRetriever.ScoredChunk::score).toList());
        for (var result : results) {
            var e = result.explanation();
            assertEquals(List.of("请问", "mysql", "什么"), e.queryTerms());
            assertEquals(List.of("mysql"), e.effectiveQueryTerms());
            assertEquals(List.of("请问", "什么"), e.ignoredQueryTerms());
        }
    }
}
