package study.ai.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import study.ai.model.AnswerResponse;
import study.ai.service.QaService;

@RestController
public class AnswerController {
    private final QaService qaService;

    public AnswerController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/questions/answer")
    public AnswerResponse answer(@Valid @RequestBody AnswerRequest request) {
        return qaService.answer(request.question());
    }

    // 输入异常的 HTTP 转换统一交给 GlobalExceptionHandler。

    public record AnswerRequest(@NotBlank(message = "问题不能为空") String question) {
    }
}
