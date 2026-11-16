package study.ai.model;

import java.util.List;

/** 检索评分的可复算明细；不是置信度或语义支持判断。 */
// queryTerms保留分词/长度过滤/去重后的原始词项；effectiveQueryTerms才参与计分。
// ignoredQueryTerms记录停用词；三组词项均保持原出现顺序，不代表置信度或20%门槛。
public record ScoreExplanation(List<String> queryTerms,
                               //原始分词结果，含义保持不变
                               List<String> effectiveQueryTerms,
                               //真正参与评分的词项
                               List<String> ignoredQueryTerms,
                               //被七词表忽略的词项
                               List<String> matchedTerms,
                               List<String> titleMatches,
                               List<String> bodyMatches,
                               int baseScore,
                               int titleBonusWeight,
                               int titleBonus) {
    public ScoreExplanation {
        queryTerms = List.copyOf(queryTerms);
        effectiveQueryTerms = List.copyOf(effectiveQueryTerms);
        ignoredQueryTerms = List.copyOf(ignoredQueryTerms);
        matchedTerms = List.copyOf(matchedTerms);
        titleMatches = List.copyOf(titleMatches);
        bodyMatches = List.copyOf(bodyMatches);
    }
}
