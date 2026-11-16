package study.ai.controller;

import study.ai.model.DocumentChunk;
import study.ai.model.ReindexReport;
import study.ai.model.ScoreExplanation;
import study.ai.exception.ReindexException;
import study.ai.service.DocumentIndexService;
import study.ai.service.KeywordRetriever;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
public class DocumentController {
    private final DocumentIndexService indexService;
    private final KeywordRetriever retriever;

    public DocumentController(DocumentIndexService indexService, KeywordRetriever retriever) {
        this.indexService = indexService;
        this.retriever = retriever;
    }

    @PostMapping("/documents/reindex")
    public ReindexReport reindex() throws ReindexException {
        return indexService.rebuildWithReport();
    }

    @GetMapping("/chunks/{id}")
    public ResponseEntity<DocumentChunk> chunk(@PathVariable("id") String id) {
        return indexService.findById(id)
                .map(ResponseEntity::ok)
                //找到就返回 200 和片段
                .orElseGet(() -> ResponseEntity.notFound().build());
                //没找到就返回 404
    }

    @PostMapping("/questions/search")
    public ResponseEntity<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        List<KeywordRetriever.ScoredChunk> results = retriever.search(request.question(), 5);
        if (results.isEmpty()) {
            return ResponseEntity.ok(new SearchResponse(
                    "当前资料中没有找到足够相关的依据。",
                    true,
                    List.of()
            ));
        }

        List<Citation> citations = results.stream()
                .map(result -> new Citation(result.chunk(), result.score(), result.explanation()))
                .toList();
                //1.变成Stream 2.每条ScoredChunk转成一条Citation
        return ResponseEntity.ok(new SearchResponse(
                "已找到相关资料，第一版暂不调用大模型，请先检查这些引用。",
                false,
                citations
        ));
    }

    public record SearchRequest(@NotBlank(message = "问题不能为空") String question) {
    }

    public record SearchResponse(String message, boolean insufficientEvidence,
                                 List<Citation> citations) {
    }

    public record Citation(DocumentChunk chunk, int score, ScoreExplanation explanation) {
    }
}
