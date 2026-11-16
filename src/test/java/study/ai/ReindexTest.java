package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import study.ai.controller.DocumentController;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;
import study.ai.exception.ReindexException;
import study.ai.handler.GlobalExceptionHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReindexTest {
    @Test
    void reportsImportedEmptyAndSkippedEntries(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.md"), "# 键\n\n候选键正文\n\n另一个段落");
        Files.writeString(directory.resolve("empty.md"), "# 只有标题\n\n");
        Files.writeString(directory.resolve("excluded.md"), "不导入");
        Files.writeString(directory.resolve("note.txt"), "不导入");
        Files.createDirectory(directory.resolve("nested.md"));
        var index = new DocumentIndexService(directory.toString(), List.of("a.md", "empty.md"));
        var report = index.rebuildWithReport();
        // 同小节两个短段落组合为一个片段；导入/跳过文件统计不变。
        assertEquals(1, report.chunkCount());
        assertEquals(2, report.importedFileCount());
        assertEquals(3, report.skippedFileCount());
        assertEquals(List.of(1, 0), report.importedFiles().stream().map(f -> f.chunkCount()).toList());
        assertEquals(List.of("not_allowed", "not_regular_file", "not_markdown"),
                report.skippedFiles().stream().map(f -> f.reason()).toList());
        assertTrue(report.elapsedMillis() >= 0);
        assertThrows(UnsupportedOperationException.class, () -> report.importedFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.skippedFiles().clear());
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(index, new KeywordRetriever(index)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/documents/reindex")).andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.importedFileCount").value(2))
                .andExpect(jsonPath("$.skippedFileCount").value(3))
                .andExpect(jsonPath("$.importedFiles[1].chunkCount").value(0));
    }

    @Test
    void failedReadNeverPublishesPartialDataAndEndpointKeepsOldSources(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("a.md");
        Files.writeString(first, "# 键\n\n候选键旧正文");
        var index = new DocumentIndexService(directory.toString());
        index.rebuild();
        var before = index.allChunks();
        String oldId = before.get(0).id();
        var retriever = new KeywordRetriever(index);
        var oldResults = retriever.search("候选键", 5);
        Files.writeString(first, "# 新标题\n\n完全不同的新正文");
        // a.md先成功读取，排序靠后的z.md使用非法UTF-8制造确定性读取失败。
        Files.write(directory.resolve("z.md"), new byte[]{(byte) 0xC3, (byte) 0x28});
        var failure = assertThrows(ReindexException.class, index::rebuildWithReport);
        assertEquals(1, failure.retainedChunkCount());
        assertEquals(before, index.allChunks());
        assertEquals(oldResults, retriever.search("候选键", 5));
        assertEquals(before.get(0), index.findById(oldId).orElseThrow());
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentController(index, retriever))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/documents/reindex")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("reindex_failed"))
                .andExpect(jsonPath("$.retainedChunkCount").value(1))
                .andExpect(jsonPath("$.cause").doesNotExist());
        mvc.perform(get("/chunks/{id}", oldId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("候选键旧正文"));
        Files.delete(directory.resolve("z.md"));
        assertEquals(1, index.rebuild());
        assertTrue(index.findById(oldId).isEmpty());
        assertEquals("完全不同的新正文", index.allChunks().get(0).content());
    }

    @Test
    void missingAllowedFilePreservesSnapshot(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("required.md");
        Files.writeString(source, "必须保留的正文");
        var index = new DocumentIndexService(directory.toString(), List.of("required.md"));
        index.rebuild();
        var before = index.allChunks();
        Files.delete(source);
        assertThrows(ReindexException.class, index::rebuildWithReport);
        assertEquals(before, index.allChunks());
    }

    @Test
    void missingOrInvalidDirectoryFailsWithoutCreatingIt(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("knowledge"));
        Path source = root.resolve("a.md");
        Files.writeString(source, "旧索引");
        var index = new DocumentIndexService(root.toString());
        index.rebuild();
        var before = index.allChunks();
        Files.delete(source);
        Files.delete(root);
        assertThrows(ReindexException.class, index::rebuildWithReport);
        assertFalse(Files.exists(root));
        assertEquals(before, index.allChunks());
        Files.writeString(root, "这是文件，不是目录");
        assertThrows(ReindexException.class, index::rebuildWithReport);
        assertEquals(before, index.allChunks());
    }

    @Test
    void genuineEmptyDirectoryCanPublishEmptyIndex(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("a.md");
        Files.writeString(source, "旧正文");
        var index = new DocumentIndexService(directory.toString());
        index.rebuild();
        Files.delete(source);
        var report = index.rebuildWithReport();
        assertEquals(0, report.chunkCount());
        assertEquals(0, report.importedFileCount());
        assertTrue(index.allChunks().isEmpty());
    }
}
