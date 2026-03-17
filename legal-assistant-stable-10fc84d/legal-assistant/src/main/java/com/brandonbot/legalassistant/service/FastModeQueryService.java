package com.brandonbot.legalassistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.brandonbot.legalassistant.config.AppProperties;
import com.brandonbot.legalassistant.llm.LlmClient;
import com.brandonbot.legalassistant.model.Evidence;

@Service
public class FastModeQueryService {

    private static final Pattern FRONT_MATTER = Pattern.compile("(?s)^---\\n(.*?)\\n---\\n");
    private static final Pattern META_LINE = Pattern.compile("(?m)^-\\s*([a-zA-Z_]+):\\s*(.*)$");
    private static final Pattern ARTICLE_HEADING = Pattern.compile("(?m)^####\\s*(第[一二三四五六七八九十百千万0-9]+条[^\\n]*)$");
    private static final Pattern DOC_NO_PATTERN = Pattern.compile(
            "(财库|财办库|国办发|苏财购|扬财购|财税|财综|财金|财行|国务院令|财政部令)\\s*[〔\\[（(]?\\s*(20\\d{2})?\\s*[〕\\]）)]?\\s*([0-9]{1,4})\\s*号|第\\s*[0-9]{1,4}\\s*号");
    private static final Pattern ABS_PATH = Pattern.compile("(?i)(/users/\\S+|/home/\\S+|/opt/\\S+|/tmp/\\S+|/var/\\S+|[a-z]:\\\\\\S+)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ATTACHMENT_EXTS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "txt", "csv", "ppt", "pptx");

    private final AppProperties appProperties;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;

    private volatile List<FastChunk> chunkCache = List.of();
    private volatile long lastLoadedMs = 0L;
    private final Map<String, QueryService.QueryResult> answerCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, QueryService.QueryResult> eldest) {
            return size() > 500;
        }
    };

    public FastModeQueryService(AppProperties appProperties,
                                PromptBuilder promptBuilder,
                                LlmClient llmClient) {
        this.appProperties = appProperties;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
    }

    public QueryService.QueryResult ask(String question) {
        if (appProperties.fastMode() == null || !appProperties.fastMode().enabled()) {
            return new QueryService.QueryResult("快速模式未启用。", List.of());
        }
        List<FastChunk> allChunks = loadIfNeeded();
        if (allChunks.isEmpty()) {
            return new QueryService.QueryResult("快速模式法规库为空，请先完成快速模式入库。", List.of());
        }

        String q = normalize(question);
        List<String> terms = buildTerms(q);
        if (terms.isEmpty()) {
            return new QueryService.QueryResult("请输入具体问题。", List.of());
        }

        int retrievalTopK = Math.max(8, appProperties.fastMode().retrievalTopK());
        int rerankTopK = Math.max(1, Math.min(retrievalTopK, appProperties.fastMode().rerankTopK()));
        List<ScoredChunk> stage1 = lexicalRecall(allChunks, q, terms, retrievalTopK);
        List<ScoredChunk> stage2 = semanticRerank(stage1, q, terms, rerankTopK);
        List<Evidence> evidences = toEvidences(stage2);

        if (evidences.isEmpty()) {
            return new QueryService.QueryResult("未找到足够依据，请补充更具体问题或切换到兼容模式。", List.of());
        }

        boolean useSingle = shouldUseSingleEvidence(q, stage2);
        List<ScoredChunk> selectedScored = useSingle ? List.of(stage2.get(0)) : stage2;
        List<Evidence> selected = toEvidences(selectedScored);
        boolean concise = isDefinitionQuestion(q);
        String cacheKey = buildCacheKey(q, concise, selectedScored);
        QueryService.QueryResult cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String answer = llmClient.chat(
                concise ? promptBuilder.systemPromptForDefinition() : promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(question, selected, concise)
        );

        String sanitized = sanitize(answer);
        if (sanitized.isBlank() || isModelUnavailable(sanitized)) {
            sanitized = fallbackFromEvidence(selected, isModelUnavailable(answer));
        }
        sanitized = appendAttachmentLinks(sanitized, selectedScored);

        QueryService.QueryResult result = new QueryService.QueryResult(sanitized, selected);
        putCached(cacheKey, result);
        return result;
    }

    private List<FastChunk> loadIfNeeded() {
        long now = System.currentTimeMillis();
        if (!chunkCache.isEmpty() && (now - lastLoadedMs) < 60_000L) {
            return chunkCache;
        }
        synchronized (this) {
            if (!chunkCache.isEmpty() && (now - lastLoadedMs) < 60_000L) {
                return chunkCache;
            }
            chunkCache = loadAllChunks();
            lastLoadedMs = now;
            clearCache();
            return chunkCache;
        }
    }

    private String buildCacheKey(String normalizedQuestion, boolean concise, List<ScoredChunk> selectedScored) {
        StringBuilder sb = new StringBuilder();
        sb.append(normalizedQuestion).append("|").append(concise ? "D" : "N");
        for (ScoredChunk scored : selectedScored) {
            FastChunk c = scored.chunk();
            sb.append("|")
                    .append(normalize(defaultIfBlank(c.docNo(), ""))).append("#")
                    .append(normalize(defaultIfBlank(c.articleNo(), ""))).append("#")
                    .append(normalize(defaultIfBlank(c.title(), "")));
        }
        return sb.toString();
    }

    private QueryService.QueryResult getCached(String key) {
        synchronized (answerCache) {
            return answerCache.get(key);
        }
    }

    private void putCached(String key, QueryService.QueryResult value) {
        synchronized (answerCache) {
            answerCache.put(key, value);
        }
    }

    private void clearCache() {
        synchronized (answerCache) {
            answerCache.clear();
        }
    }

    private List<FastChunk> loadAllChunks() {
        String rootPath = appProperties.fastMode() == null ? "" : appProperties.fastMode().rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            return List.of();
        }
        Path root = Path.of(rootPath);
        if (!Files.exists(root)) {
            return List.of();
        }

        List<Path> mdFiles = new ArrayList<>();
        for (String sub : List.of("01_结构化法规md", "02_半结构化附件md", "03_仅原文索引")) {
            Path dir = root.resolve(sub);
            if (!Files.exists(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                mdFiles.addAll(s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .sorted(Comparator.naturalOrder())
                        .toList());
            } catch (IOException ignored) {
            }
        }
        Path attachmentRoot = root.resolve("04_附件原文");
        if (Files.exists(attachmentRoot)) {
            try (Stream<Path> s = Files.walk(attachmentRoot)) {
                mdFiles.addAll(s.filter(Files::isRegularFile)
                        .filter(this::isRawAttachmentFile)
                        .sorted(Comparator.naturalOrder())
                        .toList());
            } catch (IOException ignored) {
            }
        }

        List<FastChunk> out = new ArrayList<>();
        for (Path file : mdFiles) {
            try {
                if (isMarkdownFile(file)) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    out.addAll(extractChunksFromMarkdown(file, text));
                } else {
                    out.add(buildRawAttachmentChunk(file));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private boolean isMarkdownFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md");
    }

    private boolean isRawAttachmentFile(Path path) {
        String ext = fileExt(path);
        return !ext.isBlank() && ATTACHMENT_EXTS.contains(ext);
    }

    private FastChunk buildRawAttachmentChunk(Path file) {
        String fileName = file.getFileName() == null ? "附件原文" : file.getFileName().toString();
        String title = fileName.replaceFirst("\\.[^.]+$", "");
        String folder = file.getParent() == null ? "" : file.getParent().getFileName().toString();
        String pseudo = ("附件原文 " + title + " " + folder + " " + fileName).trim();
        String docNo = extractDocNo(pseudo);
        return new FastChunk(
                title,
                docNo,
                "",
                "",
                "",
                pseudo,
                file.toAbsolutePath().toString(),
                "raw_attachment",
                ""
        );
    }

    private List<FastChunk> extractChunksFromMarkdown(Path file, String text) {
        String title = file.getFileName().toString().replaceFirst("\\.md$", "");
        Map<String, String> front = new LinkedHashMap<>();
        Matcher fm = FRONT_MATTER.matcher(text);
        String body = text;
        if (fm.find()) {
            String block = fm.group(1);
            for (String line : block.split("\\n")) {
                int i = line.indexOf(':');
                if (i > 0) {
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim().replaceAll("^\"|\"$", "");
                    front.put(k, v);
                }
            }
            body = text.substring(fm.end());
        }

        String docNo = firstNonBlank(front.get("doc_no"), extractDocNo(body));
        String issuer = front.getOrDefault("issuer", "");
        String sourceUrl = firstNonBlank(
                front.get("source_url"),
                front.get("url"),
                front.get("source_link"),
                extractMetaValue(body, "source_url"),
                extractMetaValue(body, "url"),
                extractMetaValue(body, "source_link"),
                extractMetaValue(body, "来源链接"));
        String lawLevel = extractMetaValue(body, "law_level");
        String contentType = front.getOrDefault("content_type", "structured_text");

        List<FastChunk> chunks = new ArrayList<>();
        List<ArticleSpan> spans = findArticleSpans(body);
        if (!spans.isEmpty()) {
            for (ArticleSpan span : spans) {
                String article = span.heading();
                String content = body.substring(span.start(), span.end()).trim();
                if (content.length() < 30) {
                    continue;
                }
                chunks.add(new FastChunk(title, docNo, issuer, lawLevel, article, content,
                        file.toAbsolutePath().toString(), contentType, sourceUrl));
            }
            return chunks;
        }

        for (String para : body.split("\\n\\s*\\n")) {
            String content = para.trim();
            if (content.length() < 120) {
                continue;
            }
            chunks.add(new FastChunk(title, docNo, issuer, lawLevel, "", content,
                    file.toAbsolutePath().toString(), contentType, sourceUrl));
        }
        return chunks;
    }

    private List<ArticleSpan> findArticleSpans(String body) {
        Matcher m = ARTICLE_HEADING.matcher(body);
        List<int[]> marks = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[] { m.start(), m.end() });
            heads.add(m.group(1).trim());
        }
        if (marks.isEmpty()) {
            return List.of();
        }
        List<ArticleSpan> spans = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            int start = marks.get(i)[1];
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : body.length();
            spans.add(new ArticleSpan(heads.get(i), start, end));
        }
        return spans;
    }

    private List<ScoredChunk> lexicalRecall(List<FastChunk> all, String q, List<String> terms, int topN) {
        Map<String, Double> idf = computeIdf(all, terms);
        List<ScoredChunk> scored = new ArrayList<>(all.size());
        for (FastChunk c : all) {
            String bodyN = normalize(c.content());
            String titleN = normalize(c.title());
            String docNoN = normalize(c.docNo());
            String articleN = normalize(c.articleNo());

            double cov = weightedCoverage(terms, idf, bodyN);
            double title = weightedCoverage(terms, idf, titleN);
            double doc = weightedCoverage(terms, idf, docNoN);
            double article = weightedCoverage(terms, idf, articleN);

            double score = cov * 0.54 + title * 0.20 + doc * 0.16 + article * 0.10;
            if ("structured_text".equalsIgnoreCase(c.contentType())) {
                score += 0.08;
            }
            if (!c.articleNo().isBlank()) {
                score += 0.04;
            }
            scored.add(new ScoredChunk(c, score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topN)
                .toList();
    }

    private List<ScoredChunk> semanticRerank(List<ScoredChunk> candidates, String q, List<String> terms, int topN) {
        Set<String> q2 = charNgrams(q, 2);
        List<ScoredChunk> rescored = new ArrayList<>(candidates.size());
        for (ScoredChunk c : candidates) {
            String text = normalize(c.chunk().title() + " " + c.chunk().articleNo() + " " + c.chunk().content());
            Set<String> d2 = charNgrams(text, 2);
            double j = jaccard(q2, d2);
            double phrase = phraseHit(q, text);
            double finalScore = c.score() * 0.62 + j * 0.28 + phrase * 0.10;
            rescored.add(new ScoredChunk(c.chunk(), finalScore));
        }
        return rescored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topN)
                .toList();
    }

    private List<Evidence> toEvidences(List<ScoredChunk> chunks) {
        return chunks.stream().map(sc -> {
            FastChunk c = sc.chunk();
            String ttl = c.title();
            if (!c.articleNo().isBlank()) {
                ttl += " " + c.articleNo();
            }
            if (!c.docNo().isBlank()) {
                ttl += "（" + c.docNo() + "）";
            }
            return new Evidence(ttl, compact(c.content(), 900), c.sourcePath(), sc.score());
        }).toList();
    }

    private boolean shouldUseSingleEvidence(String q, List<ScoredChunk> top) {
        if (top.isEmpty()) {
            return false;
        }
        if (top.size() == 1) {
            return true;
        }
        if (isBroadQuestion(q) || isCompareQuestion(q)) {
            return false;
        }
        double s1 = top.get(0).score();
        double s2 = top.get(1).score();
        double leadMin = appProperties.fastMode() == null
                ? 0.12
                : Math.max(0.02, appProperties.fastMode().singleEvidenceLeadMin());
        return s1 >= 0.58 && (s1 - s2) >= leadMin;
    }

    private boolean isBroadQuestion(String q) {
        String n = normalize(q);
        return n.contains("有哪些") || n.contains("要点") || n.contains("清单") || n.contains("总结") || n.contains("流程");
    }

    private boolean isCompareQuestion(String q) {
        String n = normalize(q);
        return n.contains("区别") || n.contains("差异") || n.contains("边界") || n.contains("对比") || n.contains("与") && n.contains("和");
    }

    private boolean isDefinitionQuestion(String q) {
        String n = normalize(q);
        return n.contains("什么是") || n.startsWith("定义") || n.contains("含义") || n.contains("概念");
    }

    private String fallbackFromEvidence(List<Evidence> evidences, boolean modelUnavailable) {
        StringBuilder sb = new StringBuilder();
        if (modelUnavailable) {
            sb.append("当前大模型不可用，以下为快速模式证据汇总：\n\n");
        } else {
            sb.append("根据快速模式命中证据，给出摘要：\n\n");
        }
        for (int i = 0; i < Math.min(3, evidences.size()); i++) {
            Evidence e = evidences.get(i);
            sb.append(i + 1).append(". ").append(e.title()).append("\n");
            sb.append("   ").append(compact(e.snippet(), 220)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String appendAttachmentLinks(String answer, List<ScoredChunk> selectedScored) {
        if (answer == null) {
            return "";
        }
        if (selectedScored == null || selectedScored.isEmpty()) {
            return answer;
        }
        record LinkCandidate(String label, String url, int mentionPos, int originalIndex) {}
        List<LinkCandidate> candidates = new ArrayList<>();
        String normalizedAnswer = normalize(answer);
        int originalIndex = 0;
        for (ScoredChunk scored : selectedScored) {
            FastChunk c = scored.chunk();
            String officialUrl = extractOfficialUrl(c);
            if (officialUrl == null || officialUrl.isBlank()) {
                originalIndex++;
                continue;
            }
            if (!evidenceMentionedInAnswer(normalizedAnswer, c)) {
                originalIndex++;
                continue;
            }
            String label = c.title() == null || c.title().isBlank() ? "原文链接" : c.title();
            int pos = mentionPosition(normalizedAnswer, c);
            candidates.add(new LinkCandidate(label, officialUrl, pos, originalIndex));
            originalIndex++;
        }
        candidates.sort(Comparator
                .comparingInt((LinkCandidate c) -> c.mentionPos() >= 0 ? c.mentionPos() : Integer.MAX_VALUE)
                .thenComparingInt(LinkCandidate::originalIndex));
        LinkedHashMap<String, String> links = new LinkedHashMap<>();
        for (LinkCandidate c : candidates) {
            links.putIfAbsent(c.label(), c.url());
            if (links.size() >= 4) {
                break;
            }
        }
        // If model output did not explicitly mention any evidence title/doc_no, keep one fallback link.
        if (links.isEmpty()) {
            for (ScoredChunk scored : selectedScored) {
                FastChunk c = scored.chunk();
                String officialUrl = extractOfficialUrl(c);
                if (officialUrl == null || officialUrl.isBlank()) {
                    continue;
                }
                String label = c.title() == null || c.title().isBlank() ? "原文链接" : c.title();
                links.putIfAbsent(label, officialUrl);
                break;
            }
        }
        if (links.isEmpty()) {
            return answer;
        }

        StringBuilder sb = new StringBuilder(answer.trim());
        sb.append("\n\n原文链接（官网）：\n");
        links.forEach((label, url) -> sb.append("- [").append(label).append("](").append(url).append(")\n"));
        return sb.toString().trim();
    }

    private int mentionPosition(String normalizedAnswer, FastChunk chunk) {
        if (normalizedAnswer == null || normalizedAnswer.isBlank() || chunk == null) {
            return -1;
        }
        String docNo = normalize(defaultIfBlank(chunk.docNo(), ""));
        if (!docNo.isBlank()) {
            int p = normalizedAnswer.indexOf(docNo);
            if (p >= 0) {
                return p;
            }
        }
        String title = normalize(defaultIfBlank(chunk.title(), ""));
        if (!title.isBlank()) {
            int p = normalizedAnswer.indexOf(title);
            if (p >= 0) {
                return p;
            }
            if (title.length() > 12) {
                p = normalizedAnswer.indexOf(title.substring(0, 12));
                if (p >= 0) {
                    return p;
                }
            }
        }
        String art = normalize(defaultIfBlank(chunk.articleNo(), ""));
        return art.isBlank() ? -1 : normalizedAnswer.indexOf(art);
    }

    private boolean evidenceMentionedInAnswer(String normalizedAnswer, FastChunk chunk) {
        if (normalizedAnswer == null || normalizedAnswer.isBlank() || chunk == null) {
            return false;
        }
        String ttl = normalize(defaultIfBlank(chunk.title(), ""));
        String docNo = normalize(defaultIfBlank(chunk.docNo(), ""));
        String art = normalize(defaultIfBlank(chunk.articleNo(), ""));
        if (!docNo.isBlank() && normalizedAnswer.contains(docNo)) {
            return true;
        }
        if (!art.isBlank() && normalizedAnswer.contains(art)) {
            return true;
        }
        if (!ttl.isBlank()) {
            if (ttl.length() <= 12) {
                return normalizedAnswer.contains(ttl);
            }
            String shortTitle = ttl.substring(0, 12);
            return normalizedAnswer.contains(shortTitle);
        }
        return false;
    }

    private String extractOfficialUrl(FastChunk c) {
        String direct = firstNonBlank(c.sourceUrl(), extractMetaValue(c.content(), "来源链接"));
        if (!direct.isBlank()) {
            Matcher dm = URL_PATTERN.matcher(direct);
            if (dm.find()) {
                return dm.group();
            }
        }
        Matcher m = URL_PATTERN.matcher(c.content());
        return m.find() ? m.group() : "";
    }

    private String sanitize(String answer) {
        if (answer == null) {
            return "";
        }
        String out = answer.replaceAll("(?is)<think>.*?</think>", "");
        out = ABS_PATH.matcher(out).replaceAll("已隐藏");
        out = out.replaceAll("(?m)^\\s*(目录|文件绝对路径|文件路径|来源路径|绝对路径)\\s*[:：].*$", "");
        out = out.replaceAll("\\n{3,}", "\\n\\n").trim();
        return out;
    }

    private boolean isModelUnavailable(String text) {
        if (text == null) {
            return true;
        }
        String n = normalize(text);
        return n.contains("模型不可用") || n.contains("api key") || n.contains("network") || n.contains("timeout");
    }

    private String extractMetaValue(String body, String key) {
        Matcher m = META_LINE.matcher(body);
        while (m.find()) {
            if (key.equalsIgnoreCase(m.group(1))) {
                return m.group(2).trim();
            }
        }
        return "";
    }

    private String extractDocNo(String text) {
        Matcher m = DOC_NO_PATTERN.matcher(text);
        return m.find() ? m.group().replaceAll("\\s+", "") : "";
    }

    private Map<String, Double> computeIdf(List<FastChunk> chunks, List<String> terms) {
        Map<String, Double> map = new HashMap<>();
        int n = Math.max(chunks.size(), 1);
        for (String t : terms) {
            int df = 0;
            for (FastChunk c : chunks) {
                String all = normalize(c.title() + " " + c.docNo() + " " + c.articleNo() + " " + c.content());
                if (all.contains(t)) {
                    df++;
                }
            }
            double idf = Math.log((n + 1.0) / (df + 0.5)) + 1.0;
            map.put(t, idf);
        }
        return map;
    }

    private double weightedCoverage(List<String> terms, Map<String, Double> idf, String text) {
        if (terms.isEmpty() || text.isBlank()) {
            return 0d;
        }
        double total = 0d;
        double hit = 0d;
        for (String t : terms) {
            double w = idf.getOrDefault(t, 1.0);
            total += w;
            if (text.contains(t)) {
                hit += w;
            }
        }
        return total <= 0 ? 0d : hit / total;
    }

    private List<String> buildTerms(String q) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String compact = q.replaceAll("\\s+", "");
        for (String w : q.split("[^\\p{IsHan}A-Za-z0-9]+")) {
            String n = normalize(w);
            if (n.length() >= 2) {
                set.add(n);
            }
        }
        for (int n = 2; n <= 4; n++) {
            for (int i = 0; i + n <= compact.length(); i++) {
                String s = compact.substring(i, i + n);
                if (s.length() >= 2) {
                    set.add(s);
                }
                if (set.size() >= 96) {
                    break;
                }
            }
        }
        return set.stream().limit(96).toList();
    }

    private Set<String> charNgrams(String s, int n) {
        String c = s.replaceAll("\\s+", "");
        if (c.length() < n) {
            return Set.of(c);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i + n <= c.length(); i++) {
            out.add(c.substring(i, i + n));
        }
        return out;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        long inter = a.stream().filter(b::contains).count();
        long uni = a.size() + b.size() - inter;
        return uni == 0 ? 0d : (double) inter / uni;
    }

    private double phraseHit(String q, String text) {
        String c = q.replaceAll("\\s+", "");
        if (c.length() >= 4 && text.contains(c)) {
            return 1.0;
        }
        if (c.length() <= 3) {
            return 0d;
        }
        int max = Math.min(12, c.length());
        for (int n = max; n >= 4; n--) {
            for (int i = 0; i + n <= c.length(); i++) {
                if (text.contains(c.substring(i, i + n))) {
                    return (double) n / max;
                }
            }
        }
        return 0d;
    }

    private String compact(String s, int max) {
        if (s == null) {
            return "";
        }
        String c = s.replaceAll("\\s+", " ").trim();
        return c.length() <= max ? c : c.substring(0, max) + " ...";
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private String fileExt(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private record FastChunk(String title,
                             String docNo,
                             String issuer,
                             String lawLevel,
                             String articleNo,
                             String content,
                             String sourcePath,
                             String contentType,
                             String sourceUrl) {
    }

    private record ScoredChunk(FastChunk chunk, double score) {
    }

    private record ArticleSpan(String heading, int start, int end) {
    }
}
