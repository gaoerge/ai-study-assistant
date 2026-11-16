package study.ai.exception;

import java.io.IOException;

/** 本次重建未发布；保留失败时已有的索引快照。 */
public class ReindexException extends IOException {
    private final int retainedChunkCount;

    public ReindexException(int retainedChunkCount, Exception cause) {
        super("资料重建失败，本次未发布，请检查资料目录、允许清单及文件编码。", cause);
        this.retainedChunkCount = retainedChunkCount;
    }

    public int retainedChunkCount() {
        return retainedChunkCount;
    }
}
