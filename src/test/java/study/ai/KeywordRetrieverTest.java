package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRetrieverTest {
    @Test
    void ranksThreeShortChunksByDistinctBigramMatches(@TempDir Path directory) throws Exception {
        // 标题不含查询词，避免标题得分干扰这次手算；故意把较相关的 A 放在最后。
        // 用同名标题明确分隔三个区间，避免同小节组合改变手算评分用例。
        Files.writeString(directory.resolve("three-chunks.md"),
                "# 示例\n\n左外连接保留左表记录\n\n# 示例\n\n主键用于标识一行\n\n# 示例\n\n自然连接按同名属性匹配\n",
                StandardCharsets.UTF_8);

        DocumentIndexService index = new DocumentIndexService(directory.toString());
        assertEquals(3, index.rebuild());
        KeywordRetriever retriever = new KeywordRetriever(index);

        // “自然连接”拆成“自然、然连、连接”：A=3、B=1、C=0。
        List<KeywordRetriever.ScoredChunk> results = retriever.search("自然连接", 5);
        assertEquals(List.of("自然连接按同名属性匹配", "左外连接保留左表记录"),
                results.stream().map(result -> result.chunk().content()).toList());
        assertEquals(List.of(3, 1), results.stream().map(KeywordRetriever.ScoredChunk::score).toList());

        // 同一个词项重复出现不重复计分；limit=1 时只留下最高分。
        List<KeywordRetriever.ScoredChunk> topOne = retriever.search("自然连接 自然连接", 1);
        assertEquals(1, topOne.size());
        assertEquals("自然连接按同名属性匹配", topOne.get(0).chunk().content());
        assertEquals(3, topOne.get(0).score());

        // 这三段均不含该问题的任何二字词项，应返回空列表。
        assertTrue(retriever.search("红烧肉", 5).isEmpty());
    }

    @Test
    void importsMarkdownAndRanksMatchingChunkFirst() throws Exception {
        Path directory = Files.createTempDirectory("study-knowledge-");
        Files.writeString(directory.resolve("mysql.md"),
                "# MySQL\n\n## 联合索引\n\n联合索引可以帮助排序。\n\n## 事务\n\n事务保证一致性。\n",
                StandardCharsets.UTF_8);

        DocumentIndexService index = new DocumentIndexService(directory.toString());
        assertEquals(2, index.rebuild());

        KeywordRetriever retriever = new KeywordRetriever(index);
        List<KeywordRetriever.ScoredChunk> results = retriever.search("联合索引 排序", 5);

        assertTrue(results.size() >= 1);
        assertEquals("联合索引", results.get(0).chunk().heading());

        List<KeywordRetriever.ScoredChunk> noSpaceResults = retriever.search("联合索引为什么可能帮助排序", 5);
        assertTrue(noSpaceResults.size() >= 1);
        assertEquals("联合索引", noSpaceResults.get(0).chunk().heading());
    }
}
