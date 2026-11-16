package study.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** HTTP 错误响应；仅重建失败时携带 retainedChunkCount。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String status, String message, Integer retainedChunkCount) {

    public ApiError(String status, String message) {
        this(status, message, null);
    }
}
