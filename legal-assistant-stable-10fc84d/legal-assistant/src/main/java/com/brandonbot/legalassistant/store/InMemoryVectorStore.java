package com.brandonbot.legalassistant.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.brandonbot.legalassistant.embedding.EmbeddingClient;
import com.brandonbot.legalassistant.model.DocumentChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class InMemoryVectorStore implements VectorStoreGateway {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);
    private static final int MAX_QUERY_TERMS = 72;
    private static final int MAX_QUERY_PHRASES = 24;
    // Keep in-memory index compact to avoid OOM on 2GB-class instances.
    private static final int MAX_INDEX_CHARS_PER_CHUNK = 3200;
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万0-9]+条");
    private static final Pattern META_LINE = Pattern.compile("(?m)^\\[(文档类型|附件属性|主文标题|挂靠主文ID|来源文件|提示|法规标题|法规类别|条号|款项|文号|发文机关|发布日期|施行日期|来源链接|元数据|抽取策略|疑似扫描|疑似乱码|疑似摘要页|主文判定)\\].*$");
    private static final Pattern SUMMARY_HINT = Pattern.compile("(发稿时间|访问量|当前位置|上一篇|下一篇|打印本页|关闭窗口)");
    private static final Pattern WEAK_TITLE_HINT = Pattern.compile("^[（(]?[0-9一二三四五六七八九十]+[)）.、]\\s*.+$");
    private static final Set<String> STOPWORDS = Set.of(
            "什么", "哪些", "怎么", "如何", "有几种", "几种", "多少", "是否", "以及", "关于", "这个", "那个",
            "规定", "问题", "相关", "内容", "进行", "可以", "应当", "需要", "必须", "按照", "对于", "本级",
            "政策", "法规", "法律", "办法", "条例", "通知", "意见"
    );

    private final List<DocumentChunk> chunks = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path storeFile = resolveStoreFile();
    private final EmbeddingClient embeddingClient;
    private volatile List<IndexedChunk> indexedChunks = List.of();
    private volatile boolean embeddingDimMismatchWarned = false;

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @PostConstruct
    public void restore() {
        try {
            if (!Files.exists(storeFile)) {
                log.warn("Vector store file not found: {}", storeFile);
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            List<DocumentChunk> restored = objectMapper.readValue(json, new TypeReference<>() {});
            chunks.clear();
            chunks.addAll(restored);
            rebuildIndex();
            log.info("Vector store restored: file={}, chunks={}, indexed={}", storeFile, chunks.size(), indexedChunks.size());
        } catch (OutOfMemoryError oom) {
            indexedChunks = List.of();
            log.error("Vector store restore OOM: file={}, chunks={}", storeFile, chunks.size(), oom);
        } catch (Exception ignored) {
            indexedChunks = List.of();
            log.error("Vector store restore failed: file={}", storeFile, ignored);
        }
    }

    @Override
    public void upsertChunks(List<DocumentChunk> newChunks) {
        chunks.clear();
        chunks.addAll(newChunks);
        try {
            rebuildIndex();
        } catch (OutOfMemoryError oom) {
            indexedChunks = List.of();
        }
        persist();
    }

    @Override
    public List<DocumentChunkScore> retrieve(String query, int topK) {
        String normalizedQuery = normalizeForRecall(query);
        if (normalizedQuery.isBlank() || indexedChunks.isEmpty()) {
            return List.of();
        }

        QueryProfile profile = buildQueryProfile(normalizedQuery);
        if (profile.queryTerms().isEmpty() && profile.queryPhrases().isEmpty()) {
            return List.of();
        }
        float[] queryVector = buildQueryEmbedding(query);

        Map<String, Double> idfByTerm = buildIdf(profile.queryTerms());
        Map<String, Double> idfByPhrase = buildIdf(profile.queryPhrases());

        List<DocumentChunkScore> scored = new ArrayList<>(Math.min(indexedChunks.size(), topK * 8));
        for (IndexedChunk indexed : indexedChunks) {
            double lexical = weightedCoverage(profile.queryTerms(), idfByTerm, indexed.fullNormalized(), indexed.fullCompact());
            double phrase = weightedCoverage(profile.queryPhrases(), idfByPhrase, indexed.bodyNormalized(), indexed.bodyCompact());
            double field = fieldHitScore(profile.queryTerms(), idfByTerm, indexed);
            double titleCoverage = weightedCoverage(profile.queryTerms(), idfByTerm, indexed.titleNormalized(), indexed.titleCompact());
            double lawCoverage = weightedCoverage(profile.queryTerms(), idfByTerm, indexed.lawTitleNormalized(), indexed.lawTitleCompact());
            double exact = exactMatchScore(profile.compactQuery(), indexed.bodyCompact());
            double articleBoost = articleBoost(profile.queryArticle(), indexed.articleCompact(), indexed.bodyNormalized());

            double score = (0.36d * lexical)
                    + (0.20d * phrase)
                    + (0.18d * field)
                    + (0.12d * titleCoverage)
                    + (0.08d * lawCoverage)
                    + (0.06d * exact)
                    + articleBoost;
            if (queryVector != null && indexed.embeddingVector() != null) {
                double semantic = semanticScore(queryVector, indexed.embeddingVector());
                score = score * 0.66d + semantic * 0.34d;
            }

            score += structuralQualityBonus(indexed);
            score -= lengthPenalty(indexed.bodyLength());
            score -= indexed.summaryLike() ? 0.14d : 0d;
            score -= indexed.scanned() ? 0.06d : 0d;
            score -= indexed.garbled() ? 0.16d : 0d;
            score -= indexed.weakTitle() ? 0.08d : 0d;
            if (indexed.bodyLength() < 40) {
                score -= 0.36d;
            } else if (indexed.bodyLength() < 120) {
                score -= 0.12d;
            }

            // Do not hard-drop low-score chunks here; let QueryService do final filtering.
            // Otherwise broad/natural questions may end up with an empty candidate set.
            scored.add(new DocumentChunkScore(indexed.chunk(), score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(DocumentChunkScore::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public void clear() {
        chunks.clear();
        indexedChunks = List.of();
        persist();
    }

    private QueryProfile buildQueryProfile(String normalizedQuery) {
        String compactQuery = compact(normalizedQuery);
        LinkedHashSet<String> terms = new LinkedHashSet<>(extractWordTokens(normalizedQuery));
        terms.addAll(extractCjkTerms(compactQuery, 2, 4, MAX_QUERY_TERMS));

        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (String token : terms) {
            if (token.length() >= 4) {
                phrases.add(token);
            }
            if (phrases.size() >= MAX_QUERY_PHRASES) {
                break;
            }
        }
        if (phrases.isEmpty()) {
            phrases.addAll(extractCjkTerms(compactQuery, 4, 6, MAX_QUERY_PHRASES));
        }

        List<String> queryTerms = terms.stream()
                .filter(t -> t != null && t.length() >= 2)
                .limit(MAX_QUERY_TERMS)
                .toList();
        List<String> queryPhrases = phrases.stream()
                .filter(p -> p != null && p.length() >= 3)
                .limit(MAX_QUERY_PHRASES)
                .toList();

        String queryArticle = "";
        Matcher matcher = ARTICLE_PATTERN.matcher(normalizedQuery);
        if (matcher.find()) {
            queryArticle = compact(matcher.group());
        }

        return new QueryProfile(normalizedQuery, compactQuery, queryTerms, queryPhrases, queryArticle);
    }

    private Map<String, Double> buildIdf(List<String> terms) {
        if (terms == null || terms.isEmpty() || indexedChunks.isEmpty()) {
            return Map.of();
        }
        int n = indexedChunks.size();
        java.util.LinkedHashMap<String, Double> idf = new java.util.LinkedHashMap<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            int df = 0;
            for (IndexedChunk chunk : indexedChunks) {
                if (containsToken(chunk.fullNormalized(), chunk.fullCompact(), term)) {
                    df++;
                }
            }
            double value = Math.log((n + 1.0d) / (df + 0.5d)) + 1.0d;
            idf.put(term, value);
        }
        return idf;
    }

    private double weightedCoverage(List<String> queryTerms,
                                    Map<String, Double> idfByTerm,
                                    String normalizedText,
                                    String compactText) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return 0d;
        }
        double total = 0d;
        double matched = 0d;
        for (String term : queryTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            double weight = idfByTerm.getOrDefault(term, 1.0d);
            total += weight;
            if (containsToken(normalizedText, compactText, term)) {
                matched += weight;
            }
        }
        if (total <= 0d) {
            return 0d;
        }
        return matched / total;
    }

    private double fieldHitScore(List<String> queryTerms, Map<String, Double> idfByTerm, IndexedChunk indexed) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return 0d;
        }
        double total = 0d;
        double hit = 0d;
        for (String term : queryTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            double weight = idfByTerm.getOrDefault(term, 1.0d);
            total += weight;
            if (containsToken(indexed.titleNormalized(), indexed.titleCompact(), term)
                    || containsToken(indexed.lawTitleNormalized(), indexed.lawTitleCompact(), term)
                    || containsToken(indexed.articleNormalized(), indexed.articleCompact(), term)) {
                hit += weight;
            }
        }
        if (total <= 0d) {
            return 0d;
        }
        return hit / total;
    }

    private double exactMatchScore(String compactQuery, String compactText) {
        if (compactQuery == null || compactText == null || compactQuery.isBlank() || compactText.isBlank()) {
            return 0d;
        }
        if (compactQuery.length() >= 4 && compactText.contains(compactQuery)) {
            return 1d;
        }
        if (compactQuery.length() >= 6) {
            int upper = Math.min(12, compactQuery.length());
            for (int n = upper; n >= 4; n--) {
                for (int i = 0; i <= compactQuery.length() - n; i++) {
                    String sub = compactQuery.substring(i, i + n);
                    if (compactText.contains(sub)) {
                        return (double) n / upper;
                    }
                }
            }
        }
        return 0d;
    }

    private double articleBoost(String queryArticle, String articleCompact, String bodyNormalized) {
        if (queryArticle == null || queryArticle.isBlank()) {
            return 0d;
        }
        if (articleCompact != null && !articleCompact.isBlank() && articleCompact.equals(queryArticle)) {
            return 0.25d;
        }
        return (bodyNormalized != null && bodyNormalized.contains(queryArticle)) ? 0.10d : 0d;
    }

    private double lengthPenalty(int bodyLen) {
        if (bodyLen <= 1000) {
            return 0d;
        }
        return Math.min(0.18d, ((double) (bodyLen - 1000)) / 5200d);
    }

    private double structuralQualityBonus(IndexedChunk indexed) {
        double bonus = 0d;
        if (indexed.articleCompact() != null && !indexed.articleCompact().isBlank()) {
            bonus += 0.03d;
        }
        if (indexed.lawTitleCompact() != null && indexed.lawTitleCompact().length() >= 4) {
            bonus += 0.03d;
        }
        if (!indexed.summaryLike() && !indexed.garbled()) {
            bonus += 0.02d;
        }
        return bonus;
    }

    private boolean containsToken(String normalizedText, String compactText, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String target = token.indexOf(' ') >= 0 ? compact(token) : token;
        if (target.length() < 2) {
            return false;
        }
        return (normalizedText != null && normalizedText.contains(target))
                || (compactText != null && compactText.contains(target));
    }

    private void rebuildIndex() {
        if (chunks.isEmpty()) {
            indexedChunks = List.of();
            return;
        }
        List<IndexedChunk> rebuilt = new ArrayList<>(chunks.size());
        List<String> embeddingInputs = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            String title = truncateForIndex(chunk.title(), 300);
            String lawTitle = truncateForIndex(chunk.lawTitle(), 400);
            String article = truncateForIndex(chunk.articleNo(), 40);
            String content = truncateForIndex(chunk.content(), MAX_INDEX_CHARS_PER_CHUNK);
            String body = stripMetaLines(content);

            String docNo = extractMetaValue(content, "文号");
            String issuer = extractMetaValue(content, "发文机关");
            String publishDate = extractMetaValue(content, "发布日期");
            String effectiveDate = extractMetaValue(content, "施行日期");
            // Keep only high-signal fields for recall. Avoid path/meta noise dominating ranking.
            String fullNormalized = normalizeForRecall(title + "\n"
                    + lawTitle + "\n"
                    + article + "\n"
                    + docNo + "\n"
                    + issuer + "\n"
                    + publishDate + "\n"
                    + effectiveDate + "\n"
                    + body);
            String fullCompact = compact(fullNormalized);
            String bodyNormalized = normalizeForRecall(body);
            String bodyCompact = compact(bodyNormalized);
            String embeddingInput = buildEmbeddingInput(title, lawTitle, article, body);

            String normalizedTitle = normalizeForRecall(title);
            String compactTitle = compact(normalizedTitle);
            String normalizedLawTitle = normalizeForRecall(lawTitle);
            String compactLawTitle = compact(normalizedLawTitle);
            String normalizedArticle = normalizeForRecall(article);
            String compactArticle = compact(normalizedArticle);

            boolean scanned = content.contains("[疑似扫描] true");
            boolean summaryLike = content.contains("[疑似摘要页] true") || looksSummaryBody(body);
            boolean garbled = content.contains("[疑似乱码] true") || looksGarbledBody(body);
            boolean weakTitle = looksWeakTitle(title);

            rebuilt.add(new IndexedChunk(
                    chunk,
                    fullNormalized,
                    fullCompact,
                    bodyNormalized,
                    bodyCompact,
                    normalizedTitle,
                    compactTitle,
                    normalizedLawTitle,
                    compactLawTitle,
                    normalizedArticle,
                    compactArticle,
                    bodyCompact.length(),
                    scanned,
                    summaryLike,
                    garbled,
                    weakTitle,
                    null
            ));
            embeddingInputs.add(embeddingInput);
        }
        rebuilt = attachEmbeddings(rebuilt, embeddingInputs);
        indexedChunks = List.copyOf(rebuilt);
    }

    private List<IndexedChunk> attachEmbeddings(List<IndexedChunk> rebuilt, List<String> embeddingInputs) {
        if (rebuilt.isEmpty() || embeddingInputs.isEmpty() || embeddingClient == null || !embeddingClient.available()) {
            return rebuilt;
        }
        List<float[]> vectors = embedInBatches(embeddingInputs);
        if (vectors.isEmpty()) {
            return rebuilt;
        }
        List<IndexedChunk> withVectors = new ArrayList<>(rebuilt.size());
        for (int i = 0; i < rebuilt.size(); i++) {
            IndexedChunk chunk = rebuilt.get(i);
            float[] vector = i < vectors.size() ? vectors.get(i) : null;
            withVectors.add(chunk.withEmbeddingVector(vector));
        }
        return withVectors;
    }

    private boolean looksWeakTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String normalizedTitle = title.replaceAll("\\s+", " ").trim();
        if (normalizedTitle.length() < 4) {
            return true;
        }
        if (WEAK_TITLE_HINT.matcher(normalizedTitle).matches()
                && !containsLegalCue(normalizedTitle)) {
            return true;
        }
        return normalizedTitle.startsWith("1.") && !containsLegalCue(normalizedTitle);
    }

    private boolean containsLegalCue(String text) {
        return text.contains("法")
                || text.contains("条例")
                || text.contains("办法")
                || text.contains("规定")
                || text.contains("通知")
                || text.contains("意见");
    }

    private String stripMetaLines(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return META_LINE.matcher(content).replaceAll("").trim();
    }

    private String extractMetaValue(String content, String key) {
        if (content == null || content.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?m)^\\[" + Pattern.quote(key) + "\\]\\s*(.+)$").matcher(content);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private boolean looksSummaryBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        String normalized = body.replaceAll("\\s+", "");
        if (normalized.length() > 2600) {
            return false;
        }
        int hints = 0;
        if (SUMMARY_HINT.matcher(body).find()) {
            hints++;
        }
        if (normalized.contains("附件") && normalized.contains(".pdf")) {
            hints++;
        }
        if (!ARTICLE_PATTERN.matcher(body).find()) {
            hints++;
        }
        return hints >= 2;
    }

    private boolean looksGarbledBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        String compactBody = compact(body);
        int len = compactBody.length();
        if (len < 120) {
            return false;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < compactBody.length(); i++) {
            char c = compactBody.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B) {
                cjk++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        double cjkRatio = (double) cjk / len;
        double latinRatio = (double) latin / len;
        return cjkRatio < 0.12d && latinRatio > 0.45d;
    }

    private List<String> extractWordTokens(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String piece : normalizedText.split("\\s+")) {
            String token = piece.trim();
            if (token.length() < 2) {
                continue;
            }
            if (STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() >= MAX_QUERY_TERMS) {
                break;
            }
        }
        return tokens.stream().toList();
    }

    private List<String> extractCjkTerms(String compactInput, int minN, int maxN, int maxTerms) {
        if (compactInput == null || compactInput.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        int upper = Math.min(maxN, compactInput.length());
        for (int n = upper; n >= minN; n--) {
            for (int i = 0; i <= compactInput.length() - n; i++) {
                String gram = compactInput.substring(i, i + n);
                if (gram.isBlank() || STOPWORDS.contains(gram)) {
                    continue;
                }
                if (isMostlyDigits(gram)) {
                    continue;
                }
                terms.add(gram);
                if (terms.size() >= maxTerms) {
                    return terms.stream().toList();
                }
            }
        }
        return terms.stream().toList();
    }

    private boolean isMostlyDigits(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int digit = 0;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                digit++;
            }
        }
        return digit >= Math.max(2, token.length() - 1);
    }

    private float[] buildQueryEmbedding(String query) {
        if (embeddingClient == null || !embeddingClient.available()) {
            return null;
        }
        String input = normalizeForRecall(query);
        if (input.isBlank()) {
            return null;
        }
        return embeddingClient.embed(input);
    }

    private List<float[]> embedInBatches(List<String> inputs) {
        if (embeddingClient == null || !embeddingClient.available() || inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        int batchSize = 32;
        List<float[]> all = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, inputs.size());
            List<String> batch = inputs.subList(start, end);
            List<float[]> vectors = embeddingClient.embedBatch(batch);
            if (vectors.isEmpty()) {
                for (int i = start; i < end; i++) {
                    all.add(null);
                }
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                all.add(i < vectors.size() ? vectors.get(i) : null);
            }
        }
        return all;
    }

    private String buildEmbeddingInput(String title, String lawTitle, String article, String body) {
        String text = (defaultIfBlank(title, "") + "\n"
                + defaultIfBlank(lawTitle, "") + "\n"
                + defaultIfBlank(article, "") + "\n"
                + defaultIfBlank(body, "")).trim();
        return truncateForIndex(text, 1800);
    }

    private double cosineNormalized(float[] query, float[] doc) {
        if (query == null || doc == null || query.length == 0 || doc.length == 0) {
            return 0d;
        }
        int n = query.length;
        double dot = 0d;
        double nq = 0d;
        double nd = 0d;
        for (int i = 0; i < n; i++) {
            double q = query[i];
            double d = doc[i];
            dot += q * d;
            nq += q * q;
            nd += d * d;
        }
        if (nq <= 0d || nd <= 0d) {
            return 0d;
        }
        double cos = dot / (Math.sqrt(nq) * Math.sqrt(nd));
        return (cos + 1d) * 0.5d;
    }

    private double semanticScore(float[] query, float[] doc) {
        if (query == null || doc == null || query.length == 0 || doc.length == 0) {
            return 0d;
        }
        if (query.length != doc.length) {
            if (!embeddingDimMismatchWarned) {
                embeddingDimMismatchWarned = true;
                log.warn("Embedding dimension mismatch detected: queryDim={}, docDim={}. "
                                + "Semantic score disabled; lexical retrieval fallback remains active. "
                                + "Check APP_EMBEDDING_MODEL consistency between ingest and query.",
                        query.length, doc.length);
            }
            return 0d;
        }
        return cosineNormalized(query, doc);
    }

    private String normalizeForRecall(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compact(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private String truncateForIndex(String input, int maxChars) {
        if (input == null || input.isBlank()) {
            return "";
        }
        if (input.length() <= maxChars) {
            return input;
        }
        return input.substring(0, maxChars);
    }

    private record IndexedChunk(
            DocumentChunk chunk,
            String fullNormalized,
            String fullCompact,
            String bodyNormalized,
            String bodyCompact,
            String titleNormalized,
            String titleCompact,
            String lawTitleNormalized,
            String lawTitleCompact,
            String articleNormalized,
            String articleCompact,
            int bodyLength,
            boolean scanned,
            boolean summaryLike,
            boolean garbled,
            boolean weakTitle,
            float[] embeddingVector
    ) {
        private IndexedChunk withEmbeddingVector(float[] vector) {
            return new IndexedChunk(
                    chunk,
                    fullNormalized,
                    fullCompact,
                    bodyNormalized,
                    bodyCompact,
                    titleNormalized,
                    titleCompact,
                    lawTitleNormalized,
                    lawTitleCompact,
                    articleNormalized,
                    articleCompact,
                    bodyLength,
                    scanned,
                    summaryLike,
                    garbled,
                    weakTitle,
                    vector
            );
        }
    }

    private record QueryProfile(
            String normalizedQuery,
            String compactQuery,
            List<String> queryTerms,
            List<String> queryPhrases,
            String queryArticle
    ) {
    }

    private void persist() {
        try {
            Files.createDirectories(storeFile.getParent());
            Files.writeString(storeFile, objectMapper.writeValueAsString(chunks), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Ignore persist failures to avoid breaking query flow.
        }
    }

    private Path resolveStoreFile() {
        String configured = System.getenv("APP_VECTOR_STORE_FILE");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }

        Path dataRun = Paths.get("/var/data/.run/vector-store.json");
        if (Files.exists(dataRun)) {
            return dataRun;
        }

        return Path.of(System.getProperty("user.dir"), ".run", "vector-store.json");
    }
}
