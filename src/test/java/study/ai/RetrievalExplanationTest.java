package study.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import study.ai.controller.DocumentController;
import study.ai.model.DocumentChunk;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetrievalExplanationTest {
    private DocumentChunk chunk(String id, String heading, String body) {
        return new DocumentChunk(id, "fixture.md", heading, body, 1, 1);
    }

    private DocumentIndexService index(List<DocumentChunk> chunks) {
        var index = mock(DocumentIndexService.class);
        when(index.allChunks()).thenReturn(chunks);
        return index;
    }

    @Test
    void titleBonusChangesRankingAndEveryPointCanBeRecomputed() {
        var index = index(List.of(chunk("body", "other", "alpha alpha"),
                chunk("title", "ALPHA", "other"), chunk("both", "alpha", "alpha alpha"),
                chunk("none", "other", "other")));
        var results = new KeywordRetriever(index, 1).search("ALPHA alpha", 5);
        assertEquals(List.of("title", "both", "body"),
                results.stream().map(r -> r.chunk().id()).toList());
        assertEquals(List.of(2, 2, 1), results.stream().map(KeywordRetriever.ScoredChunk::score).toList());
        for (var result : results) {
            var e = result.explanation();
            assertEquals(List.of("alpha"), e.queryTerms());
            assertEquals(List.of("alpha"), e.matchedTerms());
            assertEquals(e.matchedTerms().size(), e.baseScore());
            assertEquals(e.titleMatches().size() * e.titleBonusWeight(), e.titleBonus());
            assertEquals(e.baseScore() + e.titleBonus(), result.score());
        }
        assertTrue(results.get(0).explanation().bodyMatches().isEmpty());
        assertEquals(List.of("alpha"), results.get(1).explanation().titleMatches());
        assertEquals(List.of("alpha"), results.get(1).explanation().bodyMatches());
        assertTrue(results.get(2).explanation().titleMatches().isEmpty());
        assertEquals("title", new KeywordRetriever(index, 1).search("alpha", 1).get(0).chunk().id());
        assertTrue(new KeywordRetriever(index, 1).search("missing", 5).isEmpty());
    }

    @Test
    void zeroBonusPreservesBaselineScoresAndStableTies() {
        var chunks = List.of(chunk("first", "other", "alpha"),
                chunk("second", "alpha", "other"), chunk("third", "alpha", "alpha"));
        var index = index(chunks);
        var results = new KeywordRetriever(index, 0).search("alpha", 5);
        assertEquals(chunks, results.stream().map(KeywordRetriever.ScoredChunk::chunk).toList());
        assertEquals(List.of(1, 1, 1), results.stream().map(KeywordRetriever.ScoredChunk::score).toList());
        assertEquals(results, new KeywordRetriever(index).search("alpha", 5));
        assertEquals(0, results.get(1).explanation().titleBonus());
        assertEquals(List.of("alpha"), results.get(1).explanation().titleMatches());
    }

    @Test
    void explanationsAreImmutableAndInvalidConfigurationIsRejected() {
        var index = index(List.of(chunk("a", "alpha", "alpha")));
        var e = new KeywordRetriever(index, 1).search("alpha", 5).get(0).explanation();
        for (var terms : List.of(e.queryTerms(), e.effectiveQueryTerms(), e.ignoredQueryTerms(),
                e.matchedTerms(), e.titleMatches(), e.bodyMatches())) {
            assertThrows(UnsupportedOperationException.class, () -> terms.add("change"));
        }
        assertThrows(IllegalArgumentException.class, () -> new KeywordRetriever(index, -1));
        assertThrows(IllegalArgumentException.class, () -> new KeywordRetriever(index, 11));
        assertNull(new KeywordRetriever.ScoredChunk(chunk("a", "", ""), 3).explanation());
    }

    @Test
    void searchEndpointReturnsExplanationAlongsideExistingChunkAndScore() throws Exception {
        var index = index(List.of(chunk("a", "ALPHA", "alpha")));
        var mvc = MockMvcBuilders.standaloneSetup(
                new DocumentController(index, new KeywordRetriever(index, 1))).build();
        mvc.perform(post("/questions/search").contentType("application/json")
                        .content("{\"question\":\"alpha\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].chunk.id").value("a"))
                .andExpect(jsonPath("$.citations[0].score").value(2))
                .andExpect(jsonPath("$.citations[0].explanation.queryTerms[0]").value("alpha"))
                .andExpect(jsonPath("$.citations[0].explanation.effectiveQueryTerms[0]").value("alpha"))
                .andExpect(jsonPath("$.citations[0].explanation.ignoredQueryTerms").isEmpty())
                .andExpect(jsonPath("$.citations[0].explanation.titleMatches[0]").value("alpha"))
                .andExpect(jsonPath("$.citations[0].explanation.bodyMatches[0]").value("alpha"))
                .andExpect(jsonPath("$.citations[0].explanation.baseScore").value(1))
                .andExpect(jsonPath("$.citations[0].explanation.titleBonusWeight").value(1))
                .andExpect(jsonPath("$.citations[0].explanation.titleBonus").value(1));
    }
}
