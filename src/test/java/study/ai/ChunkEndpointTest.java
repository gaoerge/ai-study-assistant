package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import study.ai.controller.DocumentController;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChunkEndpointTest {
    @Test
    void searchIdResolvesToIndexedSourceAndUnknownIdIs404(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("keys.md");
        Files.writeString(source, "# 键\n\n候选键用于唯一识别记录");
        DocumentIndexService index = new DocumentIndexService(directory.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new DocumentController(index, new KeywordRetriever(index))).build();
        mvc.perform(post("/documents/reindex")).andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1));
        String id = index.allChunks().get(0).id();
        mvc.perform(post("/questions/search").contentType("application/json")
                        .content("{\"question\":\"候选键\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].chunk.id").value(id));
        mvc.perform(get("/chunks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.fileName").value("keys.md"))
                .andExpect(jsonPath("$.heading").value("键"))
                .andExpect(jsonPath("$.content").value("候选键用于唯一识别记录"))
                .andExpect(jsonPath("$.startLine").value(3))
                .andExpect(jsonPath("$.endLine").value(3));
        // 查询读取已发布索引，不从磁盘重读：文件修改在重建后才生效。
        Files.writeString(source, "# 键\n\n新正文");
        assertEquals("候选键用于唯一识别记录", index.findById(id).orElseThrow().content());
        mvc.perform(get("/chunks/not-an-id")).andExpect(status().isNotFound());
        mvc.perform(post("/documents/reindex")).andExpect(status().isOk());
        mvc.perform(get("/chunks/{id}", id)).andExpect(status().isNotFound());
    }
}
