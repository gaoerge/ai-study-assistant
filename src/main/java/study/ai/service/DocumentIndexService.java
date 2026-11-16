package study.ai.service;

import study.ai.exception.ReindexException;
import study.ai.model.DocumentChunk;
import study.ai.model.ReindexReport;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class DocumentIndexService {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    //开头允许0-3个普通空格，至少3个反引号或至少3个波浪号，后面可以有语言说明等内容。
    private final Path knowledgeDirectory;
    private final List<String> allowedFiles;
    private volatile List<DocumentChunk> chunks = List.of();

    public DocumentIndexService(String directory) {
        this(directory, List.of());
    }

    @Autowired
    public DocumentIndexService(
            @Value("${study.knowledge-directory:knowledge}") String directory,
            @Value("${study.allowed-files:}") List<String> allowedFiles) {
        this.knowledgeDirectory = Paths.get(directory);
        this.allowedFiles = allowedFiles.stream().map(String::trim)
                .filter(name -> !name.isEmpty()).distinct().toList();
    }

    public int rebuild() throws IOException {
        return rebuildWithReport().chunkCount();
    }
    //需要整数的旧代码就继续调用rebuild()

    //需要详细信息就调用rebuildWithReport()
    public synchronized ReindexReport rebuildWithReport() throws ReindexException {
        long started = System.nanoTime();
        try {
            // 不创建缺失目录，避免路径配置错误时发布空索引。
            List<Path> entries;
            try (var files = Files.list(knowledgeDirectory)) {
                entries = files.sorted().toList();
            }
            List<DocumentChunk> rebuilt = new ArrayList<>();
            List<ReindexReport.ImportedFile> imported = new ArrayList<>();
            List<ReindexReport.SkippedFile> skipped = new ArrayList<>();
            Set<String> importedNames = new HashSet<>();

            for (Path path : entries) {
                String name = path.getFileName().toString();
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String reason = attributes.isSymbolicLink() ? "symbolic_link"
                        : !attributes.isRegularFile() ? "not_regular_file"
                        : !name.endsWith(".md") ? "not_markdown"
                        : !allowedFiles.isEmpty() && !allowedFiles.contains(name) ? "not_allowed" : null;
                // 空清单保持原来的全量扫描；配置清单时只读指定文件名。
                if (reason != null) {
                    skipped.add(new ReindexReport.SkippedFile(name, reason));
                    continue;
                }
                int before = rebuilt.size();
                readMarkdown(path, rebuilt);
                imported.add(new ReindexReport.ImportedFile(name, rebuilt.size() - before));
                importedNames.add(name);
            }

            if (!importedNames.containsAll(allowedFiles)) {
                throw new IOException("允许清单中存在缺失或不可导入的文件");
            }
            // 先赋予全部片段ID，成功后一次发布；ID与行号无关。
            Map<String, Integer> occurrences = new HashMap<>();
            //记录同一指纹出现了几次
            List<DocumentChunk> identified = new ArrayList<>();

            for (DocumentChunk chunk : rebuilt) {
                String fingerprint = fingerprint(chunk);
                int occurrence = occurrences.merge(fingerprint, 1, Integer::sum);
                identified.add(new DocumentChunk("chunk_" + fingerprint + "_" + occurrence,
                        chunk.fileName(), chunk.heading(), chunk.content(), chunk.startLine(), chunk.endLine()));
            }

            // 先构造快照和报告，最后仅进行一次引用替换；异常不会发布部分结果。
            List<DocumentChunk> snapshot = List.copyOf(identified);
            ReindexReport report = new ReindexReport(snapshot.size(), imported.size(), skipped.size(),
                    (System.nanoTime() - started) / 1_000_000, imported, skipped);
            chunks = snapshot;
            return report;
        } catch (IOException | RuntimeException e) {
            throw new ReindexException(chunks.size(), e);
        }
    }

    public List<DocumentChunk> allChunks() {
        return Collections.unmodifiableList(chunks);
    }

    //Optional 表达“可能有结果，也可能没有”
    public Optional<DocumentChunk> findById(String id) {
        return chunks.stream().filter(chunk -> chunk.id().equals(id)).findFirst();
    }

    private String fingerprint(DocumentChunk chunk) {
        try {
            //创建SHA-256计算器：输入一串字节，输出一个固定长度的内容指纹。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 长度前缀避免不同字段拼接成同一串；v1标记ID规则版本。
            for (String field : List.of("v1", chunk.fileName(), chunk.heading(), chunk.content())) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持SHA-256", e);
        }
    }

    private static final int MAX_CHUNK_CHARS = 800;

    private void readMarkdown(Path path, List<DocumentChunk> target) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            //用UTF-8读取文件，每一行放进列表中的一个字符串。
            long[] offsets = new long[lines.size() + 1];
            // 前缀长度按 Java UTF-16 字符计数；每行加一个换行，区间末尾再减去一个。
            for (int i = 0; i < lines.size(); i++) {
                offsets[i + 1] = offsets[i] + lines.get(i).length() + 1L;
            }
            //以后计算任意连续行区间的长度时，不用每次重新拼接字符串。
            String heading = "未分类";
            int start = -1;
            int end = 0;

            for (int index = 0; index < lines.size();) {
                String line = lines.get(index);
                if (isHeading(line)) {
                    //以 # 开头：保存旧段落，更新标题，清空段落缓冲
                    // 现在保存整个组合；即使标题文字相同，每次标题也结束当前区间。
                    appendChunk(path, heading, lines, start, end, target);
                    start = -1;
                    heading = line.replaceFirst("^#+\\s*", "").trim();
                    index++;
                } else if (line.isBlank()) {
                    //空行：保存旧段落，清空缓存，标题不变
                    // 现在空行只分隔完整块；组合内部的空行会随原文区间一起保留。
                    index++;
                } else {
                    //普通行：追加到当前段落，多行之间加空格
                    // 为了用行号还原原文，现在保留换行及缩进；评分仍可匹配其中的词项。
                    int blockEnd = blockEnd(lines, index);
                    if (start >= 0 && offsets[blockEnd] - offsets[start] - 1 > MAX_CHUNK_CHARS) {
                        appendChunk(path, heading, lines, start, end, target);
                        //保存用的旧的end，新快还没有放进去
                        start = -1;
                    }
                    if (start < 0) start = index;
                    end = blockEnd;
                    // 超长完整块单独发布，不切断，也不与后面的块重叠。
                    if (offsets[end] - offsets[start] - 1 > MAX_CHUNK_CHARS) {
                        appendChunk(path, heading, lines, start, end, target);
                        start = -1;
                    }
                    index = blockEnd;
                }
            }
            //文件结束：再保存一次，避免末尾段落遗漏
            appendChunk(path, heading, lines, start, end, target);
        } catch (IOException e) {
            throw new IllegalStateException("读取资料失败: " + path, e);
        }
    }

    // 返回完整块的末行之后的位置；围栏和公式内部不再识别标题或空行。
    private int blockEnd(List<String> lines, int start) {
        String first = lines.get(start);
        var opening = FENCE.matcher(first);
        //代码围栏
        if (opening.matches()) {
            char marker = opening.group(1).charAt(0);
            int length = opening.group(1).length();
            //识别起点后，记录围栏字符和长度
            for (int i = start + 1; i < lines.size(); i++) {
                var closing = FENCE.matcher(lines.get(i));
                if (closing.matches() && closing.group(1).charAt(0) == marker
                        && closing.group(1).length() >= length && closing.group(2).isBlank()) {
                    return i + 1;
                }
                //找结束围栏
            }
            return lines.size();
        }
        if (isFormula(first)) {
            String stripped = first.strip();

            if (stripped.length() > 2 && stripped.endsWith("$$")) return start + 1;
            //单行公式
            for (int i = start + 1; i < lines.size(); i++) {
                if (lines.get(i).strip().endsWith("$$")) return i + 1;
            }
            //多行公式
            return lines.size();
        }
        int end = start + 1;
        if (isQuote(first)) {
            while (end < lines.size() && isQuote(lines.get(end))) end++;
            //连续引用
        } else if (isTableStart(lines, start)) {
            end = start + 2;
            while (end < lines.size() && !lines.get(end).isBlank()
                    && !isHeading(lines.get(end)) && !FENCE.matcher(lines.get(end)).matches()
                    && !isFormula(lines.get(end)) && !isQuote(lines.get(end))
                    && lines.get(end).contains("|")) {
                end++;
            }
            //表格：通过“表头行＋分隔行”识别
        } else {
            while (end < lines.size() && !lines.get(end).isBlank()
                    && !isHeading(lines.get(end)) && !FENCE.matcher(lines.get(end)).matches()
                    && !isFormula(lines.get(end)) && !isQuote(lines.get(end))
                    && !isTableStart(lines, end)) {
                end++;
            }
            //普通段落：只要下一行仍属于普通正文，就继续；遇到空行、标题或其他结构的开始，就停止。
        }
        return end;
    }

    private boolean isHeading(String line) {
        return line.startsWith("#");
    }

    private boolean isQuote(String line) {
        return line.stripLeading().startsWith(">");
    }

    private boolean isFormula(String line) {
        return line.stripLeading().startsWith("$$");
    }

    private boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size() || !lines.get(index).contains("|")) return false;
        String separator = lines.get(index + 1).strip();
        if (separator.startsWith("|")) separator = separator.substring(1);
        if (separator.endsWith("|")) separator = separator.substring(0, separator.length() - 1);
        String[] cells = separator.split("\\|", -1);
        if (cells.length == 0) return false;
        for (String cell : cells) {
            if (!cell.strip().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private void appendChunk(Path path, String heading, List<String> lines,
                             int start, int end, List<DocumentChunk> target) {
        if (start >= 0) {
            target.add(new DocumentChunk("", path.getFileName().toString(), heading,
                    String.join("\n", lines.subList(start, end)), start + 1, end));
        }
    }
}
