package com.brandonbot.legalassistant.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.brandonbot.legalassistant.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class QueryIntentAnalyzer {
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万0-9]+条");
    private static final Set<String> KNOWN_INTENTS = Set.of("DEFINITION", "PROCESS", "CONDITION", "PENALTY", "OTHER");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryIntentAnalyzer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public QueryIntent analyze(String question) {
        QueryIntent heuristic = heuristicIntent(question);
        if (question == null || question.isBlank()) {
            return heuristic;
        }
        if (shouldUseHeuristicOnly(question, heuristic)) {
            return heuristic;
        }

        String raw = llmClient.chat(systemPrompt(), userPrompt(question));
        if (isUnavailable(raw)) {
            return heuristic;
        }

        QueryIntent parsed = parseIntentJson(raw, question);
        if (parsed == null) {
            return heuristic;
        }
        return merge(heuristic, parsed);
    }

    private boolean shouldUseHeuristicOnly(String question, QueryIntent heuristic) {
        String q = question == null ? "" : question.trim();
        // Strategy: always try LLM intent parsing for non-empty queries.
        // If LLM is unavailable/invalid, analyze() will fall back to heuristic intent.
        return q.isBlank();
    }

    private QueryIntent heuristicIntent(String question) {
        String q = question == null ? "" : question.trim();
        String normalized = q
                .replace("啥是", "什么是")
                .replace("啥叫", "什么是")
                .replace("啥意思", "什么意思");
        String lower = normalized.toLowerCase(Locale.ROOT);
        String intentType = "OTHER";
        if (containsAny(lower, "什么是", "是什么", "定义", "指什么", "概念", "含义")) {
            intentType = "DEFINITION";
        } else if (containsAny(lower, "流程", "步骤", "怎么办", "如何办理", "怎么做")) {
            intentType = "PROCESS";
        } else if (containsAny(lower, "条件", "要求", "资格", "门槛")) {
            intentType = "CONDITION";
        } else if (containsAny(lower, "处罚", "责任", "罚款", "违法", "违规")) {
            intentType = "PENALTY";
        }

        String targetLaw = "";
        if (lower.contains("政府采购法")) {
            targetLaw = "中华人民共和国政府采购法";
        } else if (lower.contains("政府采购法实施条例") || lower.contains("实施条例")) {
            targetLaw = "中华人民共和国政府采购法实施条例";
        }

        String targetArticle = extractArticleNo(lower);
        List<String> keywords = extractKeywords(lower);
        return new QueryIntent(q, normalized, intentType, targetLaw, targetArticle, keywords);
    }

    private QueryIntent parseIntentJson(String raw, String question) {
        try {
            String json = extractJsonObject(raw);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            String intentType = normalizeIntentType(node.path("intent_type").asText("OTHER"));
            String normalized = node.path("normalized_question").asText(question == null ? "" : question);
            String targetLaw = node.path("target_law").asText("");
            String targetArticle = normalizeArticleNo(node.path("target_article").asText(""));
            List<String> keywords = new ArrayList<>();
            JsonNode kw = node.path("keywords");
            if (kw.isArray()) {
                for (JsonNode item : kw) {
                    String v = item.asText("").trim();
                    if (!v.isBlank()) {
                        keywords.add(v);
                    }
                }
            }
            return new QueryIntent(question == null ? "" : question, normalized, intentType, targetLaw, targetArticle, keywords);
        } catch (Exception ex) {
            return null;
        }
    }

    private QueryIntent merge(QueryIntent heuristic, QueryIntent parsed) {
        String normalized = firstNonBlank(parsed.normalizedQuestion(), heuristic.normalizedQuestion(), heuristic.originalQuestion());
        String intentType = KNOWN_INTENTS.contains(parsed.intentType()) ? parsed.intentType() : heuristic.intentType();
        String targetLaw = firstNonBlank(parsed.targetLaw(), heuristic.targetLaw());
        String targetArticle = firstNonBlank(normalizeArticleNo(parsed.targetArticle()), heuristic.targetArticle());

        LinkedHashSet<String> kws = new LinkedHashSet<>();
        kws.addAll(heuristic.keywords());
        kws.addAll(parsed.keywords());
        List<String> keywords = kws.stream().filter(s -> !s.isBlank()).limit(16).toList();

        return new QueryIntent(heuristic.originalQuestion(), normalized, intentType, targetLaw, targetArticle, keywords);
    }

    private String systemPrompt() {
        return "你是法律问答系统的查询意图解析器。"
                + "你只输出 JSON，不输出 markdown 或解释。"
                + "字段格式固定："
                + "{\"normalized_question\":\"...\",\"intent_type\":\"DEFINITION|PROCESS|CONDITION|PENALTY|OTHER\","
                + "\"target_law\":\"...\",\"target_article\":\"第X条或空字符串\",\"keywords\":[\"...\",\"...\"]}"
                + "若无法判断，intent_type=OTHER，其他字段尽量保守。";
    }

    private String userPrompt(String question) {
        return "问题：" + question + "\n"
                + "请做语义归一：同义问法（如“什么是/是什么/定义”）要统一。"
                + "若问题中明确提到法规名称或条号，请填入 target_law/target_article。";
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeIntentType(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (KNOWN_INTENTS.contains(v)) {
            return v;
        }
        return "OTHER";
    }

    private String extractArticleNo(String text) {
        Matcher matcher = ARTICLE_PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    private String normalizeArticleNo(String value) {
        return extractArticleNo(value);
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : question.split("[\\s，。！？、,.!?;；:：]+")) {
            String t = token.trim();
            if (t.length() >= 2) {
                keywords.add(t);
            }
        }
        return keywords.stream().limit(12).toList();
    }

    private boolean isUnavailable(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        return answer.startsWith("MiniMax 鉴权失败")
                || answer.startsWith("MiniMax 调用失败")
                || answer.startsWith("MiniMax 调用异常")
                || answer.startsWith("MiniMax API Key 未配置");
    }

    public record QueryIntent(
            String originalQuestion,
            String normalizedQuestion,
            String intentType,
            String targetLaw,
            String targetArticle,
            List<String> keywords
    ) {
        public boolean isDefinition() {
            return "DEFINITION".equals(intentType);
        }

        public String retrievalQuery() {
            String normalized = normalizedQuestion == null ? "" : normalizedQuestion.trim();
            if (normalized.isBlank()) {
                return originalQuestion == null ? "" : originalQuestion;
            }
            if (keywords == null || keywords.isEmpty()) {
                return normalized;
            }
            String keywordText = keywords.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .limit(6)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
            return (normalized + " " + keywordText).trim();
        }
    }
}
