package study.ai.model;

import java.util.List;

/** 一次成功发布的重建统计；只统计资料目录第一层。 */
public record ReindexReport(int chunkCount,
                            //发布后的总片段数
                            int importedFileCount,
                            //成功读取的文件数
                            int skippedFileCount,
                            //跳过的条目数
                            long elapsedMillis,
                            //本次构建耗时
                            List<ImportedFile> importedFiles,
                            List<SkippedFile> skippedFiles) {
    public ReindexReport {
        importedFiles = List.copyOf(importedFiles);
        skippedFiles = List.copyOf(skippedFiles);
    }

    public record ImportedFile(String fileName, int chunkCount) {}
    //导入文件及其片段数

    public record SkippedFile(String fileName, String reason) {}
    //跳过的文件或目录及其原因
}
