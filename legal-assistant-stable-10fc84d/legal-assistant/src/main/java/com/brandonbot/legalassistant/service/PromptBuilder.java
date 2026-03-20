package com.brandonbot.legalassistant.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.brandonbot.legalassistant.model.Evidence;

@Component
public class PromptBuilder {
    private static final Pattern TITLE_ARTICLE_PATTERN = Pattern.compile("^(.*?)(第[一二三四五六七八九十百千万0-9]+条)?(\\s*第[一二三四五六七八九十百千万0-9]+款)?(?:（(.+?)）)?$");

    public String systemPrompt() {
        return "你是政府采购法律政策助手。必须基于给定依据回答，不得编造。"
                + "若依据不足，明确说\"未找到足够依据\"。"
                + "默认给出完整结构化回答，按以下顺序输出："
                + "1) 结论；2) 核心要求清单；3) 依据条款（写明条号）；4) 适用条件；5) 风险提示。"
                + "优先覆盖多个关键条款，不只引用单一条款。"
                + "语言保持精炼，不写与问题无关的背景。"
                + "在“依据条款”部分，每一条都必须包含来源法规名称和条款定位。"
                + "禁止输出本地目录层级、文件绝对路径或服务器路径。"
                + "禁止只写“第X条”而不写来源法规。"
                + "若证据之间存在层级关系，优先解释上位法，再补充地方实施细则。";
    }

    public String systemPromptForDefinition() {
        return "你是政府采购法律政策助手。必须基于给定依据回答，不得编造。"
                + "若依据不足，明确说\"未找到足够依据\"。"
                + "当前是定义类问题，请使用短模板输出，严格按以下顺序："
                + "1) 结论（1-2句）；2) 依据条款（1-2条，必须写来源法规+条款定位）；3) 风险提示（1-2点）。"
                + "不要输出冗长背景，不要输出路径信息。";
    }

    public String userPrompt(String question, List<Evidence> evidences) {
        return userPrompt(question, evidences, false);
    }

    public String userPrompt(String question, List<Evidence> evidences, boolean conciseDefinition) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：\n").append(question).append("\n\n");
        sb.append("可用依据（按相关度排序）：\n");
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            TitleParts parts = parseTitle(e.title());
            sb.append("证据 ").append(i + 1).append("\n")
                    .append("- 法规标题：").append(parts.lawTitle()).append("\n");
            if (!parts.articleNo().isBlank()) {
                sb.append("- 条号：").append(parts.articleNo()).append("\n");
            }
            if (!parts.clauseNo().isBlank()) {
                sb.append("- 款项：").append(parts.clauseNo()).append("\n");
            }
            if (!parts.docNo().isBlank()) {
                sb.append("- 文号：").append(parts.docNo()).append("\n");
            }
            sb.append("- 证据片段：\n").append(normalizeSnippet(e.snippet())).append("\n")
                    .append("- 引用要求：以“法规标题 + 条号/款项”定位，不得输出文件路径。\n\n");
        }
        if (conciseDefinition) {
            sb.append("这是定义类问题，请按短模板输出：")
                    .append("1) 结论（1-2句）；2) 依据条款（1-2条）；3) 风险提示（1-2点）。")
                    .append("输出“依据条款”时按模板：条款：...；来源法规：...\n")
                    .append("再次强调：不要输出目录、文件名路径、绝对路径。\n");
        } else {
            sb.append("请严格依据以上证据作答，优先给出直接条款答案，再补充适用条件。")
                    .append("输出“依据条款”时，按如下模板：")
                    .append("条款：...；来源法规：...\n")
                    .append("再次强调：不要输出目录、文件名路径、绝对路径。\n");
        }
        return sb.toString();
    }

    private String normalizeSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "[无可用正文摘录]";
        }
        return snippet.replaceAll("(?m)^\\s*$", "")
                .replaceAll("(?m)\\n{3,}", "\n\n")
                .trim();
    }

    private TitleParts parseTitle(String title) {
        if (title == null || title.isBlank()) {
            return new TitleParts("未知法规", "", "", "");
        }
        String normalized = title.trim().replaceAll("\\s+", " ");
        Matcher matcher = TITLE_ARTICLE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return new TitleParts(normalized, "", "", "");
        }
        String lawTitle = safe(matcher.group(1));
        String articleNo = safe(matcher.group(2));
        String clauseNo = safe(matcher.group(3));
        String docNo = safe(matcher.group(4));
        String normalizedLaw = lawTitle.isBlank() ? normalized : lawTitle;
        return new TitleParts(normalizedLaw, articleNo, clauseNo, docNo);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record TitleParts(String lawTitle, String articleNo, String clauseNo, String docNo) {
    }
}
