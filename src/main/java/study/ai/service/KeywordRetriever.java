package study.ai.service;

import study.ai.model.DocumentChunk;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import study.ai.model.ScoreExplanation;

import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KeywordRetriever {
    // 先分离连续的汉字与英文/数字标识符，避免把MySQL连接拆成my、ys、l连。
    // 汉字范围沿用原实现；标点作为边界，下划线只保留在英文/数字标识符内部。
    private static final Pattern TOKEN_RUN = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*|[\\u4E00-\\u9FFF]+");
    // 只对分词后的完整词项精确过滤；不删原始问题，也不按包含某个字过滤。
    // 当前中文按相邻二字切分，所以“为什么”对应“为什”和“什么”。
    private static final Set<String> STOP_WORDS = Set.of("请问", "什么", "怎么", "如何", "为何", "为什", "有什");
    private final DocumentIndexService indexService;
    private final int titleBonusWeight;

    public KeywordRetriever(DocumentIndexService indexService) {
        this(indexService, 0);
    }

    @Autowired
    public KeywordRetriever(DocumentIndexService indexService,
            @Value("${study.retrieval.title-bonus-weight:0}") int titleBonusWeight) {
        if (titleBonusWeight < 0 || titleBonusWeight > 10) {
            throw new IllegalArgumentException("标题加分权重必须在0到10之间");
        }
        this.indexService = indexService;
        this.titleBonusWeight = titleBonusWeight;
    }

    private List<String> tokenize(String question) {
        return TOKEN_RUN.matcher(question.toLowerCase(Locale.ROOT)).results()
                .map(MatchResult::group)//数据处理流水线
                .flatMap(this::expandToken)//token -> this.expandToken(token)
                //flatMap把各个片段产生的流合并成一条流。
                .filter(word -> word.length() >= 2)
                .distinct()
                .collect(Collectors.toList());
        // 按字符类别取段，保留英文标识符，只对纯汉字段取二字词项。
        // 长度小于2的词项不参与检索。
    }

    private java.util.stream.Stream<String> expandToken(String token) {
        if (token.isBlank()) {
            return java.util.stream.Stream.empty();
        }
        // 中文问题通常没有空格；二字片段让“联合索引为什么帮助排序”能够命中资料中的短语。
        boolean containsChinese = token.codePoints().anyMatch(
                codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);

        //没有汉字或者长度不超过2，就原样输出
        if (!containsChinese || token.length() <= 2) {
            return java.util.stream.Stream.of(token);
        }

        //较长的中文片段，滑动取相邻二字
        return java.util.stream.IntStream.range(0, token.length() - 1)
                .mapToObj(index -> token.substring(index, index + 2));
                //mapToObj 的作用是：把 IntStream 中的每个整数，按照你提供的规则转换成一个对象，得到对象流 Stream<T>。
                // 在这段代码中，就是把每个整数下标转换成一个字符串。
    }

    private ScoredChunk score(DocumentChunk chunk, List<String> queryTerms,
                              List<String> effectiveQueryTerms, List<String> ignoredQueryTerms) {
        String text = (chunk.heading() + " " + chunk.content()).toLowerCase(Locale.ROOT);
        // 标题和正文参与基础匹配；标题命中可按配置额外加分。
        String heading = chunk.heading().toLowerCase(Locale.ROOT);
        String content = chunk.content().toLowerCase(Locale.ROOT);
        // 只有有效词项参与基础分、标题命中和正文命中；原始/忽略词项只用于解释。
        List<String> matchedTerms = effectiveQueryTerms.stream().filter(text::contains).toList();
        List<String> titleMatches = effectiveQueryTerms.stream().filter(heading::contains).toList();
        List<String> bodyMatches = effectiveQueryTerms.stream().filter(content::contains).toList();
        //它计算的是“命中了多少个不同的问题词项”，不是“关键词出现了多少次”。
        int baseScore = matchedTerms.size();
        int titleBonus = titleMatches.size() * titleBonusWeight;
        var explanation = new ScoreExplanation(queryTerms, effectiveQueryTerms, ignoredQueryTerms,
                matchedTerms, titleMatches, bodyMatches, baseScore, titleBonusWeight, titleBonus);
        return new ScoredChunk(chunk, baseScore + titleBonus, explanation);
    }

    public List<ScoredChunk> search(String question, int limit) {
        List<String> queryTerms = tokenize(question);
        //问题只分词一次
        // 在评分前统一分组，保留原出现顺序；不会在每个片段中重新分词或过滤。
        List<String> effectiveQueryTerms = queryTerms.stream()
                .filter(term -> !STOP_WORDS.contains(term)).toList();
        List<String> ignoredQueryTerms = queryTerms.stream()
                .filter(STOP_WORDS::contains).toList();
        // 全部被忽略时不恢复泛词来凑候选；无候选也就没有候选级评分解释。
        if (effectiveQueryTerms.isEmpty()) {
            return List.of();
        }

        return indexService.allChunks().stream()
                //获取所有已导入的片段
                .map(chunk -> score(chunk, queryTerms, effectiveQueryTerms, ignoredQueryTerms))
                //给每个片段加上分数
                .filter(result -> result.score() > 0)
                //过滤0分结果
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed())
                //分数从高到底排列
                .limit(limit)
                .collect(Collectors.toList());
    }

    public record ScoredChunk(DocumentChunk chunk, int score, ScoreExplanation explanation) {
        // 兼容手工构造候选的调用方；没有真实匹配过程时不伪造解释。
        public ScoredChunk(DocumentChunk chunk, int score) {
            this(chunk, score, null);
        }
    }
}

//流更像一条处理流水线：元素依次经过转换、过滤等步骤，最后得到结果。
