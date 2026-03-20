package com.brandonbot.legalassistant.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class TextChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6}\\s+.+|第[一二三四五六七八九十百千万0-9]+[章节条款].*|[0-9]+[.、].+)$");
    private static final Pattern LEGAL_ARTICLE = Pattern.compile("(?m)^\\s*第[一二三四五六七八九十百千万0-9]+条.*$");
    private static final Pattern INLINE_ARTICLE = Pattern.compile("(?<!\\n)(第[一二三四五六七八九十百千万0-9]+条)");

    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = normalizeArticleBoundaries(text);
        List<String> sections = splitByLegalArticles(normalized);
        // 法条优先：只要命中多条法条，就每条独立成 chunk，不再按窗口二次切分。
        if (sections.size() > 1) {
            for (String section : sections) {
                String trimmed = section.trim();
                if (!trimmed.isBlank()) {
                    chunks.add(trimmed);
                }
            }
            return dedup(chunks);
        }

        sections = splitBySections(normalized);
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                chunks.add(section);
                continue;
            }
            chunks.addAll(splitByWindowSmart(section, chunkSize, overlap));
        }

        return dedup(chunks);
    }

    private String normalizeArticleBoundaries(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return INLINE_ARTICLE.matcher(text).replaceAll("\n$1");
    }

    private List<String> splitBySections(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            boolean newSection = HEADING.matcher(trimmed).matches();
            if (newSection && current.length() > 0) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            if (!trimmed.isBlank()) {
                current.append(trimmed).append("\n");
            }
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        if (sections.isEmpty()) {
            sections.add(text);
        }
        return sections;
    }

    private List<String> splitByWindowSmart(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int rawEnd = Math.min(start + chunkSize, len);
            int end = chooseBoundary(text, start, rawEnd, len);
            chunks.add(text.substring(start, end));
            if (end >= len) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return chunks;
    }

    private int chooseBoundary(String text, int start, int rawEnd, int len) {
        if (rawEnd >= len) {
            return len;
        }
        int searchStart = Math.max(start, rawEnd - 60);
        int searchEnd = Math.min(len - 1, rawEnd + 30);
        for (int i = rawEnd; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '；' || c == ';') {
                return i + 1;
            }
        }
        for (int i = rawEnd; i <= searchEnd; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '；' || c == ';') {
                return i + 1;
            }
        }
        return rawEnd;
    }

    private List<String> splitByLegalArticles(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = LEGAL_ARTICLE.matcher(text);
        int currentStart = -1;
        while (m.find()) {
            if (currentStart >= 0) {
                String block = text.substring(currentStart, m.start()).trim();
                if (!block.isBlank()) {
                    blocks.add(block);
                }
            }
            currentStart = m.start();
        }
        if (currentStart >= 0) {
            String tail = text.substring(currentStart).trim();
            if (!tail.isBlank()) {
                blocks.add(tail);
            }
        }
        return blocks;
    }

    private List<String> dedup(List<String> chunks) {
        // De-duplicate while preserving order.
        Set<String> dedup = new LinkedHashSet<>();
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isBlank()) {
                dedup.add(trimmed);
            }
        }
        return new ArrayList<>(dedup);
    }
}
