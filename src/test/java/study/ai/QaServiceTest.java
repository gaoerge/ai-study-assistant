package study.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import study.ai.service.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QaServiceTest {
    @TempDir Path directory;

    private QaService service(LlmClient client, boolean hasDocument) throws Exception {
        if (hasDocument) Files.writeString(directory.resolve("note.md"), "# 键\n\n候选键定义");
        var index = new DocumentIndexService(directory.toString());
        index.rebuild();
        return new QaService(new KeywordRetriever(index), new ContextAssembler(8000), client);
    }

    @Test
    void fakeCompletesFlowAndReturnsResolvableSource() throws Exception {
        var response = service(new FakeLlmClient(), true).answer("候选键");
        assertEquals("answered", response.status());
        assertTrue(response.answer().contains("Fake演示"));
        assertEquals(List.of(1), response.citedNumbers());
        assertEquals(1, response.citations().get(0).number());
        assertFalse(response.citations().get(0).chunk().id().isBlank());
    }

    @Test
    void emptyRetrievalDoesNotCallClient() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var response = service(context -> { calls.incrementAndGet(); return null; }, false).answer("候选键");
        assertEquals("insufficient_evidence", response.status());
        assertEquals(0, calls.get());
        assertTrue(response.citations().isEmpty());
    }

    @Test
    void stopWordFilteringPreservesOriginalQuestionInPreparedPrompt() throws Exception {
        String question = "请问 候选键是什么";
        AtomicInteger calls = new AtomicInteger();
        var response = service(context -> {
            calls.incrementAndGet();
            assertTrue(context.userPrompt().contains(question));
            return new LlmClient.LlmResult("候选键说明[1]", false);
        }, true).answer(question);
        assertEquals("answered", response.status());
        assertEquals(1, calls.get());
    }

    @Test
    void allStopWordQuestionDoesNotCallClientEvenWhenDocumentContainsThoseWords() throws Exception {
        Files.writeString(directory.resolve("noise.md"), "# 请问\n\n为什么 怎么 如何 为何 有什");
        AtomicInteger calls = new AtomicInteger();
        var response = service(context -> { calls.incrementAndGet(); return null; }, false)
                .answer("请问 为什么");
        assertEquals("insufficient_evidence", response.status());
        assertEquals(0, calls.get());
        assertTrue(response.citations().isEmpty());
    }

    @Test
    void clientFailureAndExplicitInsufficiencyKeepSourcesButHideAnswer() throws Exception {
        var error = service(context -> { throw new IllegalStateException("secret detail"); }, true).answer("候选键");
        assertEquals("model_error", error.status());
        assertEquals("", error.answer());
        assertFalse(error.message().contains("secret"));
        assertFalse(error.citations().isEmpty());
        var insufficient = service(context -> new LlmClient.LlmResult("不应展示", true), true).answer("候选键");
        assertEquals("insufficient_evidence", insufficient.status());
        assertEquals("", insufficient.answer());
        assertFalse(insufficient.citations().isEmpty());
        assertTrue(insufficient.citedNumbers().isEmpty());
    }

    @Test
    void rejectsBlankMissingAndOutOfRangeCitationsIncludingOverflow() throws Exception {
        for (String answer : List.of(" ", "无引用", "错误[9]", "混合[1][9]", "越界[999999999999999999999999]")) {
            var response = service(context -> new LlmClient.LlmResult(answer, false), true).answer("候选键");
            assertEquals("invalid_model_response", response.status(), answer);
            assertEquals("", response.answer());
            assertTrue(response.citedNumbers().isEmpty());
        }
        assertEquals("invalid_model_response", service(context -> null, true).answer("候选键").status());
    }

    @Test
    void currentlyAcceptsUnsupportedAnswerWhenCitationNumberExists() throws Exception {
        // 当前能力边界：编号合法不等于原文支持答案；此测试记录局限，不认可答案正确。
        String source = "advisor的两个属性分别是学生编号和教师编号。";
        Files.writeString(directory.resolve("schema.md"), "# 数据库模式图\n\n" + source);
        String question = "Java线程池七个参数分别是什么？";
        String unsupportedAnswer = "线程池参数包括核心线程数、最大线程数等[1]";
        AtomicInteger calls = new AtomicInteger();
        var response = service(context -> {
            calls.incrementAndGet();
            assertTrue(context.userPrompt().contains(question));
            assertEquals(1, context.citations().size());
            assertEquals(source, context.citations().get(0).chunk().content());
            assertFalse(context.citations().get(0).chunk().content().contains("线程"));
            return new LlmClient.LlmResult(unsupportedAnswer, false);
        }, false).answer(question);

        assertEquals(1, calls.get());
        assertEquals("answered", response.status());
        assertEquals(unsupportedAnswer, response.answer());
        assertEquals(List.of(1), response.citedNumbers());
        assertTrue(response.message().contains("是否支持回答仍需核对原文"));
    }

    @Test
    void candidateSourcesAndActuallyCitedNumbersRemainSeparate() throws Exception {
        Files.writeString(directory.resolve("a.md"), "# 候选键\n\n候选键定义甲");
        Files.writeString(directory.resolve("b.md"), "# 候选键\n\n候选键定义乙");
        var response = service(context -> {
            assertEquals(2, context.citations().size());
            return new LlmClient.LlmResult("这里只引用第二份资料[2]", false);
        }, false).answer("候选键");

        assertEquals("answered", response.status());
        assertEquals(List.of(1, 2), response.citations().stream().map(c -> c.number()).toList());
        assertEquals(List.of(2), response.citedNumbers());
        assertEquals("b.md", response.citations().get(1).chunk().fileName());
    }

    @Test
    void repeatedValidNumbersAreDeduplicated() throws Exception {
        var response = service(context -> new LlmClient.LlmResult("说明[1]补充[1]", false), true).answer("候选键");
        assertEquals("answered", response.status());
        assertEquals(List.of(1), response.citedNumbers());
    }
}
