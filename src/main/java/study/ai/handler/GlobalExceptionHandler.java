package study.ai.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import study.ai.exception.InvalidQuestionException;
import study.ai.exception.ReindexException;
import study.ai.model.ApiError;

/** 统一 MVC 请求异常的 HTTP 表达；索引回滚/保留仍由业务层负责。 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidQuestionException.class)
    public ResponseEntity<ApiError> invalidQuestion(InvalidQuestionException exception) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(ReindexException.class)
    public ResponseEntity<ApiError> reindexFailed(ReindexException exception) {
        log.error("资料重建失败，保留片段数={}", exception.retainedChunkCount(), exception);
        return ResponseEntity.internalServerError().body(new ApiError(
                "reindex_failed", exception.getMessage(), exception.retainedChunkCount()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null || fieldError.getDefaultMessage() == null
                ? "请求参数校验失败" : fieldError.getDefaultMessage();
        return handleExceptionInternal(exception, new ApiError("invalid_request", message), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                new ApiError("invalid_request", "请求体缺失或JSON格式不正确"), headers, status, request);
    }

    // 沿用 Spring 对 405、415 等异常确定的状态码和响应头，只统一响应体。
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("MVC 请求处理失败，HTTP状态={}", status.value(), exception);
        }
        ApiError error = body instanceof ApiError apiError ? apiError
                : status.is5xxServerError() ? new ApiError("internal_error", "服务器内部错误，请稍后重试")
                : new ApiError("invalid_request", "请求方法、路径、媒体类型或参数不符合接口要求");
        return super.handleExceptionInternal(exception, error, headers, status, request);
    }

    // 仅兜底未被专门处理的 MVC 异常；不把所有 IllegalArgumentException 都归为用户错误。
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("未预期的请求处理异常", exception);
        return ResponseEntity.internalServerError()
                .body(new ApiError("internal_error", "服务器内部错误，请稍后重试"));
    }
}
