package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import study.ai.model.DocumentChunk;
import study.ai.service.DocumentIndexService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIndexServiceTest {
    @Test
    void idsSurviveRebuildAndLineShiftsAndDistinguishDuplicates(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("ids.md");
        String original = "# 标题\n重复正文\n# 标题\n重复正文\n# 标题\n保留正文";
        Files.writeString(file, original);
        DocumentIndexService index = new DocumentIndexService(directory.toString());
        index.rebuild();
        var before = index.allChunks();
        var ids = before.stream().map(DocumentChunk::id).toList();
        assertEquals(3, ids.stream().distinct().count());
        assertEquals(before.get(0), index.findById(ids.get(0)).orElseThrow());
        index.rebuild();
        assertEquals(ids, index.allChunks().stream().map(DocumentChunk::id).toList());

        Files.writeString(file, "\n\n" + original);
        index.rebuild();
        assertEquals(ids, index.allChunks().stream().map(DocumentChunk::id).toList());
        assertEquals(4, index.findById(ids.get(0)).orElseThrow().startLine());

        Files.writeString(file, "# 标题\n修改正文");
        index.rebuild();
        assertTrue(index.findById(ids.get(0)).isEmpty());
        assertNotEquals(ids.get(0), index.allChunks().get(0).id());
        Files.delete(file);
        index.rebuild();
        assertTrue(index.allChunks().isEmpty());
        assertTrue(index.findById(ids.get(2)).isEmpty());
    }

    @Test
    void preservesLinesAndCodeFenceAndFiltersFiles(@TempDir Path directory) throws Exception {
        List<String> lines = List.of("# 标题", "", "  第一行", "第二行  ", "  ",
                "```java", "# 不是标题", "", "  code();", "```", "",
                "| A | B |", "|---|---|", "| 1 | 2 |", "", "末尾无换行");
        Files.writeString(directory.resolve("allowed.md"), String.join("\r\n", lines), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("excluded.md"), "不应导入", StandardCharsets.UTF_8);
        DocumentIndexService index = new DocumentIndexService(directory.toString(), List.of("allowed.md"));
        assertEquals(1, index.rebuild());
        var chunk = index.allChunks().get(0);
        assertEquals(3, chunk.startLine());
        assertEquals(16, chunk.endLine());
        assertEquals("allowed.md", chunk.fileName());
        assertEquals("标题", chunk.heading());
        assertSourceRanges(lines, index.allChunks());
        assertEquals(1, index.rebuild());
    }

    @Test
    void keepsTildeFenceAndUnclosedFenceIntact(@TempDir Path directory) throws Exception {
        var index = index(directory, "~~~\n\n# code\n~~~\n```\n\n# unclosed");
        assertEquals(1, index.allChunks().size());
        assertEquals(1, index.allChunks().get(0).startLine());
        assertEquals(7, index.allChunks().get(0).endLine());
        assertEquals("未分类", index.allChunks().get(0).heading());
    }

    @Test
    void greedilyCombinesWholeParagraphsAt800Utf16Characters(@TempDir Path directory) throws Exception {
        String first = "😀".repeat(199); // 398 UTF-16字符。
        String second = "乙".repeat(400);
        var index = index(directory, first + "\n\n" + second + "\n\n尾段");
        assertEquals(List.of(first + "\n\n" + second, "尾段"), contents(index));
        assertEquals(800, index.allChunks().get(0).content().length());

        index = index(directory, first + "\n\n" + second + "乙");
        assertEquals(List.of(first, second + "乙"), contents(index));
        assertEquals(List.of(1, 3), index.allChunks().stream().map(DocumentChunk::startLine).toList());
    }

    @Test
    void keeps800And801CharacterSingleBlocksWholeAndIndependent(@TempDir Path directory) throws Exception {
        String exact = "甲".repeat(800);
        String oversized = "乙".repeat(801);
        var index = index(directory, "前\n\n" + exact + "\n\n" + oversized + "\n\n后");
        assertEquals(List.of("前", exact, oversized, "后"), contents(index));
    }

    @Test
    void countsWhitespaceSeparatorsAndNeverSplitsMultilineParagraphs(@TempDir Path directory) throws Exception {
        String first = "甲".repeat(398);
        String second = "乙".repeat(398);
        var index = index(directory, first + "\n  \n" + second);
        assertEquals(1, index.allChunks().size());
        assertEquals(800, index.allChunks().get(0).content().length());
        index = index(directory, first + "\n   \n" + second);
        assertEquals(List.of(first, second), contents(index));
        String paragraph = "甲".repeat(400) + "\n" + "乙".repeat(400);
        index = index(directory, "前\n\n" + paragraph + "\n\n后");
        assertEquals(List.of("前", paragraph, "后"), contents(index));
        assertEquals(3, index.allChunks().get(1).startLine());
        assertEquals(4, index.allChunks().get(1).endLine());
    }

    @Test
    void everyHeadingEndsCombinationEvenWhenNamesRepeat(@TempDir Path directory) throws Exception {
        List<String> lines = List.of("前言", "# 同名", "甲", "", "乙", "# 同名", "丙",
                "## 子标题", "丁", "# 空标题区间", "# 同名", "戊");
        var index = index(directory, String.join("\n", lines));
        assertEquals(List.of("前言", "甲\n\n乙", "丙", "丁", "戊"), contents(index));
        assertEquals(List.of("未分类", "同名", "同名", "子标题", "同名"),
                index.allChunks().stream().map(DocumentChunk::heading).toList());
        assertSourceRanges(lines, index.allChunks());
    }

    @Test
    void recognizesAdjacentCodeFormulaQuoteAndTableAsWholeBlocks(@TempDir Path directory) throws Exception {
        String prefix = "前".repeat(790);
        List<String> blocks = List.of(
                "````java\n# 内部标题\n\n```\n~~~~\n  code();\n````",
                "$$\n# 公式内部\n\n  x + y\n$$",
                "> 引用第一行\n>\n> 引用第二行",
                "| 名称 | 值 |\n| :--- | ---: |\n| 甲 | 乙 |",
                "列一 | 列二\n--- | ---\n甲 | 乙");
        for (String block : blocks) {
            List<String> lines = ("# 标题\n" + prefix + "\n" + block + "\n\n尾部").lines().toList();
            var index = index(directory, String.join("\n", lines));
            assertEquals(List.of(prefix, block + "\n\n尾部"), contents(index), block);
            assertTrue(index.allChunks().stream().allMatch(c -> c.heading().equals("标题")));
            assertSourceRanges(lines, index.allChunks());
        }
    }

    @Test
    void oversizedStructuredBlocksStayWhole(@TempDir Path directory) throws Exception {
        String longLine = "字".repeat(801);
        for (String block : List.of("```\n" + longLine + "\n\n```",
                "$$\n" + longLine + "\n\n$$",
                "> " + longLine + "\n> 第二行",
                "| A | B |\n|---|---|\n| " + longLine + " | b |")) {
            var index = index(directory, "前\n\n" + block + "\n\n后");
            assertEquals(List.of("前", block, "后"), contents(index));
        }
    }

    @Test
    void protectsUnclosedFormulaThroughEofAndSupportsSingleLineFormula(@TempDir Path directory) throws Exception {
        String formula = "$$\n\n# 不是标题\n" + "式".repeat(801) + "\n\n尾行";
        var index = index(directory, "前\n\n" + formula);
        assertEquals(List.of("前", formula), contents(index));
        assertEquals(8, index.allChunks().get(1).endLine());
        assertEquals("未分类", index.allChunks().get(1).heading());
        index = index(directory, "$$x+y$$\n# 新标题\n正文");
        assertEquals(List.of("$$x+y$$", "正文"), contents(index));
        assertEquals("新标题", index.allChunks().get(1).heading());
    }

    @Test
    void insertedParagraphCanMoveCombinationBoundariesAndChangeIds(@TempDir Path directory) throws Exception {
        String first = "甲".repeat(398);
        String second = "乙".repeat(400);
        var index = index(directory, "# 标题\n" + first + "\n\n" + second + "\n# 保留\n保留正文");
        var old = index.allChunks();
        assertEquals(800, old.get(0).content().length());
        Files.writeString(directory.resolve("sample.md"),
                "# 标题\n新增\n\n" + first + "\n\n" + second + "\n# 保留\n保留正文");
        index.rebuild();
        assertEquals(List.of("新增\n\n" + first, second, "保留正文"), contents(index));
        assertTrue(index.findById(old.get(0).id()).isEmpty());
        assertEquals(old.get(1).id(), index.allChunks().get(2).id());
        String mergedId = index.allChunks().get(0).id();
        Files.writeString(directory.resolve("sample.md"), "# 标题\n修改\n\n" + first);
        index.rebuild();
        assertNotEquals(mergedId, index.allChunks().get(0).id());
    }

    private DocumentIndexService index(Path directory, String markdown) throws Exception {
        Files.writeString(directory.resolve("sample.md"), markdown, StandardCharsets.UTF_8);
        DocumentIndexService index = new DocumentIndexService(directory.toString());
        index.rebuild();
        return index;
    }

    private List<String> contents(DocumentIndexService index) {
        return index.allChunks().stream().map(DocumentChunk::content).toList();
    }

    private void assertSourceRanges(List<String> lines, List<DocumentChunk> chunks) {
        int previousEnd = 0;
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.startLine() > previousEnd, "片段不可重叠");
            assertEquals(String.join("\n", lines.subList(chunk.startLine() - 1, chunk.endLine())), chunk.content());
            previousEnd = chunk.endLine();
        }
    }
}
