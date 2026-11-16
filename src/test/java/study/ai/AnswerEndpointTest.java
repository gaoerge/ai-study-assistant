package study.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnswerEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void realConfigurationUsesFakeAndReturnedIdResolves() throws Exception {
        mvc.perform(post("/documents/reindex")).andExpect(status().isOk());
        var result = mvc.perform(post("/questions/answer").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("question", "联合索引如何影响排序"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Fake演示")))
                .andExpect(jsonPath("$.citedNumbers[0]").value(1)).andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsByteArray())
                .path("citations").get(0).path("chunk").path("id").asText();
        mvc.perform(get("/chunks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void rejectsBlankAndOverBudgetQuestionWith400() throws Exception {
        for (String question : new String[]{" ", "问".repeat(8001)}) {
            mvc.perform(post("/questions/answer").contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("question", question))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("invalid_request"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.retainedChunkCount").doesNotExist());
        }
    }
}
