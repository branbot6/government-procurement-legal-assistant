package com.brandonbot.legalassistant.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.stereotype.Service;

import com.brandonbot.legalassistant.config.AppProperties;
import com.brandonbot.legalassistant.llm.LlmClient;
import com.brandonbot.legalassistant.model.Evidence;
import com.brandonbot.legalassistant.service.QueryIntentAnalyzer.QueryIntent;
import com.brandonbot.legalassistant.store.VectorStoreGateway;

@Service
public class QueryService {
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
            "(?i)(/users/\\S+|/home/\\S+|/opt/\\S+|/tmp/\\S+|/var/\\S+|[a-z]:\\\\\\\\\\S+)");
    private static final Pattern SOURCE_PATH_LINE_PATTERN = Pattern.compile("(?im)^\\s*(\\*\\*)?(目录|文件绝对路径|文件路径|来源路径|绝对路径)(\\*\\*)?\\s*[:：].*$");
    private static final Pattern META_LINE_PATTERN = Pattern.compile("(?m)^\\[(.+?)\\]\\s*(.*)$");
    private static final Pattern DOC_NO_PATTERN = Pattern.compile(
            "(财库|财办库|国办发|苏财购|扬财购|财税|财综|财金|财行)\\s*[〔\\[\\(]?\\s*(20\\d{2})\\s*[〕\\]\\)]?\\s*(\\d{1,4})\\s*号");
    private static final Pattern COMPARE_PATTERN = Pattern.compile("(.{2,20}?)(与|和|跟|及)(.{2,20}?)(的|之)?(适用边界|区别|差异|关系)");
    private static final Pattern LAW_TITLE_CUE_PATTERN = Pattern.compile("(关于|办法|条例|意见|规定|通知|标准|目录)");
    private static final Pattern RATIO_CUE_PATTERN = Pattern.compile(
            "(20[%％]|20[汉只]|百分之二十|二十).{0,8}(价格|评审|优惠|扣除)|价格.{0,8}(扣除|减免|优惠)|评审.{0,8}优惠");
    private static final int MAX_ANSWER_LEN = 12000;
    private static final int SNIPPET_MAX_LEN = 760;
    private static final int DEFAULT_RETRIEVAL_TOP_K = 24;
    private static final int DOC_NO_PREFILTER_TOP_K = 240;
    private static final int QA_CACHE_MAX_SIZE = 500;
    private static final long QA_CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private final VectorStoreGateway vectorStoreGateway;
    private final AppProperties appProperties;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final QueryIntentAnalyzer queryIntentAnalyzer;
    private final FastModeQueryService fastModeQueryService;
    private final Map<String, CachedQueryResult> questionCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedQueryResult> eldest) {
                    return size() > QA_CACHE_MAX_SIZE;
                }
            });

    public QueryService(VectorStoreGateway vectorStoreGateway,
                        AppProperties appProperties,
                        PromptBuilder promptBuilder,
                        LlmClient llmClient,
                        QueryIntentAnalyzer queryIntentAnalyzer,
                        FastModeQueryService fastModeQueryService) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.appProperties = appProperties;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.queryIntentAnalyzer = queryIntentAnalyzer;
        this.fastModeQueryService = fastModeQueryService;
    }

    public QueryResult ask(String question, String mode) {
        if (isQuickMode(mode)) {
            if (appProperties.fastMode() == null || !appProperties.fastMode().enabled()) {
                return ask(question);
            }
            return fastModeQueryService.ask(question);
        }
        return ask(question);
    }

    private boolean isQuickMode(String mode) {
        if (mode == null) {
            return false;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return "quick".equals(normalized) || "fast".equals(normalized) || "快速".equals(normalized);
    }

    public QueryResult ask(String question) {
        String cacheKey = buildCacheKey(question);
        QueryResult cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        QueryIntent intent = queryIntentAnalyzer.analyze(question);
        String normalizedQuestion = normalize(question);
        String requestedDocNo = extractRequestedDocNo(question);
        String retrievalQuery = intent.retrievalQuery().isBlank() ? question : intent.retrievalQuery();
        retrievalQuery = enrichRetrievalQuery(retrievalQuery, normalizedQuestion);
        int retrievalTopK = Math.max(appProperties.retrieval().topK() * 2, DEFAULT_RETRIEVAL_TOP_K);

        List<EvidenceCandidate> candidates = retrieveCandidates(
                retrievalQuery,
                normalizedQuestion,
                intent,
                retrievalTopK,
                requestedDocNo
        );
        boolean hasExactRequestedDocNo = requestedDocNo.isBlank()
                || candidates.stream().anyMatch(c -> c.matchesDocNo(requestedDocNo));
        List<Evidence> evidences = selectEvidence(candidates, normalizedQuestion, intent);
        if (evidences.isEmpty()) {
            String general = generateGeneralLlmAnswer(question);
            if (!general.isBlank()) {
                return cacheAndReturn(cacheKey, new QueryResult(
                        "未找到足够依据（法规库未命中可直接解释该问题的条款）。\n"
                                + "以下为通用回答（非证据结论，仅供参考）：\n"
                                + general,
                        List.of()
                ));
            }
            return cacheAndReturn(cacheKey, new QueryResult("未找到足够依据，请补充更具体问题或先完成法规入库。", List.of()));
        }

        boolean conciseDefinition = isDefinitionQuestion(normalizedQuestion, intent);
        String answer = llmClient.chat(
                conciseDefinition ? promptBuilder.systemPromptForDefinition() : promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(question, evidences, conciseDefinition)
        );

        if (isLlmUnavailable(answer)) {
            return cacheAndReturn(cacheKey, new QueryResult(
                    buildFallbackAnswer(evidences, answer, requestedDocNo, hasExactRequestedDocNo),
                    evidences
            ));
        }

        String sanitized = sanitizeAnswer(answer);
        if (isMissAnswer(sanitized) && hasStrongEvidence(evidences, normalizedQuestion)) {
            String rescued = llmClient.chat(
                    buildRescueSystemPrompt(),
                    buildRescueUserPrompt(question, evidences)
            );
            if (!isLlmUnavailable(rescued)) {
                String rescuedSanitized = sanitizeAnswer(rescued);
                if (!rescuedSanitized.isBlank() && !isMissAnswer(rescuedSanitized)) {
                    sanitized = rescuedSanitized;
                }
            }
        } else if (isMissAnswer(sanitized) && !hasStrongEvidence(evidences, normalizedQuestion)) {
            String general = generateGeneralLlmAnswer(question);
            if (!general.isBlank()) {
                sanitized = "未找到足够依据（法规库未命中可直接解释该问题的条款）。\n"
                        + "以下为通用回答（非证据结论，仅供参考）：\n"
                        + general;
            }
        }
        if (sanitized.isBlank()) {
            return cacheAndReturn(cacheKey, new QueryResult(
                    buildFallbackAnswer(evidences, "模型返回空内容", requestedDocNo, hasExactRequestedDocNo),
                    evidences
            ));
        }
        return cacheAndReturn(cacheKey, new QueryResult(sanitized, evidences));
    }

    private QueryResult cacheAndReturn(String cacheKey, QueryResult result) {
        putCache(cacheKey, result);
        return result;
    }

    private QueryResult getCached(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        synchronized (questionCache) {
            CachedQueryResult cached = questionCache.get(cacheKey);
            if (cached == null) {
                return null;
            }
            if (System.currentTimeMillis() - cached.cachedAtMs() > QA_CACHE_TTL_MILLIS) {
                questionCache.remove(cacheKey);
                return null;
            }
            return cached.result();
        }
    }

    private void putCache(String cacheKey, QueryResult result) {
        if (cacheKey == null || cacheKey.isBlank() || result == null) {
            return;
        }
        if (!isCacheable(result.answer())) {
            return;
        }
        synchronized (questionCache) {
            questionCache.put(cacheKey, new CachedQueryResult(result, System.currentTimeMillis()));
        }
    }

    private boolean isCacheable(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (isLlmUnavailable(answer) || answer.startsWith("当前大模型不可用")) {
            return false;
        }
        return true;
    }

    private String buildCacheKey(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        return compact(normalize(question));
    }

    private List<EvidenceCandidate> retrieveCandidates(String retrievalQuery,
                                                       String normalizedQuestion,
                                                       QueryIntent intent,
                                                       int retrievalTopK,
                                                       String requestedDocNo) {
        List<String> queryKeywords = buildQueryKeywords(normalizedQuestion, intent);
        if (!requestedDocNo.isBlank()) {
            return retrieveDocNoFirstCandidates(retrievalQuery, normalizedQuestion, intent, retrievalTopK, requestedDocNo, queryKeywords);
        }

        List<VectorStoreGateway.DocumentChunkScore> raw = new ArrayList<>(vectorStoreGateway.retrieve(retrievalQuery, retrievalTopK));
        if (isProcurementMethodQuestion(normalizedQuestion)) {
            String methodAnchorQuery = "中华人民共和国政府采购法 第二十六条 采购采用以下方式 "
                    + "公开招标 邀请招标 竞争性谈判 单一来源采购 询价 竞争性磋商";
            raw = mergeByChunkId(
                    raw,
                    vectorStoreGateway.retrieve(methodAnchorQuery, Math.max(retrievalTopK * 2, 48)),
                    Math.max(retrievalTopK * 3, 72)
            );
        }

        List<EvidenceCandidate> ranked = raw.stream()
                .map(score -> toCandidate(score, queryKeywords, normalizedQuestion, intent, requestedDocNo))
                .sorted(Comparator.comparingDouble(EvidenceCandidate::score).reversed())
                .toList();
        return ranked;
    }

    private List<VectorStoreGateway.DocumentChunkScore> mergeByChunkId(
            List<VectorStoreGateway.DocumentChunkScore> first,
            List<VectorStoreGateway.DocumentChunkScore> second,
            int limit) {
        Map<String, VectorStoreGateway.DocumentChunkScore> merged = new LinkedHashMap<>();
        for (VectorStoreGateway.DocumentChunkScore score : first) {
            if (score == null || score.chunk() == null) {
                continue;
            }
            merged.put(score.chunk().chunkId(), score);
            if (merged.size() >= limit) {
                return merged.values().stream()
                        .sorted(Comparator.comparingDouble(VectorStoreGateway.DocumentChunkScore::score).reversed())
                        .limit(limit)
                        .toList();
            }
        }
        for (VectorStoreGateway.DocumentChunkScore score : second) {
            if (score == null || score.chunk() == null) {
                continue;
            }
            VectorStoreGateway.DocumentChunkScore existing = merged.get(score.chunk().chunkId());
            if (existing == null || score.score() > existing.score()) {
                merged.put(score.chunk().chunkId(), score);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(VectorStoreGateway.DocumentChunkScore::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<EvidenceCandidate> retrieveDocNoFirstCandidates(String retrievalQuery,
                                                                 String normalizedQuestion,
                                                                 QueryIntent intent,
                                                                 int retrievalTopK,
                                                                 String requestedDocNo,
                                                                 List<String> queryKeywords) {
        String docNoQuery = buildDocNoPrefilterQuery(retrievalQuery, requestedDocNo);
        List<EvidenceCandidate> docNoFirst = vectorStoreGateway.retrieve(docNoQuery, DOC_NO_PREFILTER_TOP_K).stream()
                .map(score -> toCandidate(score, queryKeywords, normalizedQuestion, intent, requestedDocNo))
                .filter(c -> c.matchesDocNo(requestedDocNo))
                .sorted(Comparator.comparingDouble(EvidenceCandidate::score).reversed())
                .limit(retrievalTopK)
                .toList();
        if (!docNoFirst.isEmpty()) {
            return docNoFirst;
        }

        // Fallback when doc-no extraction is incomplete in source metadata/content.
        int fallbackTopK = Math.max(retrievalTopK * 2, DEFAULT_RETRIEVAL_TOP_K);
        return vectorStoreGateway.retrieve(retrievalQuery, fallbackTopK).stream()
                .map(score -> toCandidate(score, queryKeywords, normalizedQuestion, intent, requestedDocNo))
                .sorted(Comparator.comparingDouble(EvidenceCandidate::score).reversed())
                .toList();
    }

    private String buildDocNoPrefilterQuery(String retrievalQuery, String requestedDocNo) {
        String expanded = expandDocNoVariants(requestedDocNo);
        String prefix = defaultIfBlank(retrievalQuery, "");
        if (prefix.isBlank()) {
            return expanded;
        }
        return (prefix + " " + expanded).trim();
    }

    private String expandDocNoVariants(String requestedDocNo) {
        Matcher matcher = Pattern.compile(
                "(财库|财办库|国办发|苏财购|扬财购|财税|财综|财金|财行)(20\\d{2})(\\d{1,4})号")
                .matcher(defaultIfBlank(requestedDocNo, ""));
        if (!matcher.find()) {
            return defaultIfBlank(requestedDocNo, "");
        }
        String prefix = matcher.group(1);
        String year = matcher.group(2);
        String seq = matcher.group(3);
        return String.join(" ",
                prefix + year + seq + "号",
                prefix + "〔" + year + "〕" + seq + "号",
                prefix + " " + year + " " + seq + "号",
                "文号",
                "发文字号");
    }

    private EvidenceCandidate toCandidate(VectorStoreGateway.DocumentChunkScore score,
                                          List<String> queryKeywords,
                                          String normalizedQuestion,
                                          QueryIntent intent,
                                          String requestedDocNo) {
        String rawContent = score.chunk().content() == null ? "" : score.chunk().content();
        MetaBlock meta = parseMeta(rawContent);
        String body = stripMetaLines(rawContent);
        String lawTitle = firstNonBlank(score.chunk().lawTitle(), meta.get("法规标题"), score.chunk().title());
        lawTitle = repairLawTitle(lawTitle, score.chunk().sourcePath());
        String articleNo = firstNonBlank(score.chunk().articleNo(), meta.get("条号"));
        String clauseNo = meta.get("款项");
        String docNo = meta.get("文号");
        if (docNo.isBlank()) {
            docNo = extractDocNoFromText(score.chunk().sourcePath()
                    + " " + score.chunk().title()
                    + " " + lawTitle
                    + " " + body);
        }
        String issuer = meta.get("发文机关");
        String publishDate = meta.get("发布日期");
        String effectiveDate = meta.get("施行日期");
        String docType = meta.get("文档类型");
        String attachmentType = meta.get("附件属性");

        String displayTitle = buildDisplayTitle(lawTitle, articleNo, clauseNo, docNo);
        String snippet = buildSnippet(body, queryKeywords, SNIPPET_MAX_LEN);
        if (!docNo.isBlank() && !snippet.contains(docNo)) {
            snippet = "【文号】" + docNo + "\n" + snippet;
        }
        if (!issuer.isBlank()) {
            snippet = "【发文机关】" + issuer + "\n" + snippet;
        }
        if (!publishDate.isBlank() || !effectiveDate.isBlank()) {
            String dateLine = "【日期】发布:" + defaultIfBlank(publishDate, "未知")
                    + " / 施行:" + defaultIfBlank(effectiveDate, "未知");
            snippet = dateLine + "\n" + snippet;
        }
        if (!docType.isBlank()) {
            snippet = "【文档类型】" + docType + (attachmentType.isBlank() ? "" : (" / " + attachmentType)) + "\n" + snippet;
        }

        double tunedScore = tuneScore(
                score.score(),
                normalizedQuestion,
                intent,
                queryKeywords,
                requestedDocNo,
                score.chunk().sourcePath(),
                score.chunk().regionLevel(),
                lawTitle,
                articleNo,
                docNo,
                snippet,
                body,
                meta
        );

        return new EvidenceCandidate(
                new Evidence(displayTitle, snippet, score.chunk().sourcePath(), tunedScore),
                score.chunk().sourcePath(),
                lawTitle,
                articleNo,
                docNo,
                score.chunk().regionLevel(),
                meta.summaryLike(),
                meta.scanned(),
                meta.garbled()
        );
    }

    private double tuneScore(double baseScore,
                             String question,
                             QueryIntent intent,
                             List<String> queryKeywords,
                             String requestedDocNo,
                             String sourcePath,
                             String regionLevel,
                             String lawTitle,
                             String articleNo,
                             String docNo,
                             String snippet,
                             String body,
                             MetaBlock meta) {
        double score = baseScore;
        String merged = normalize(lawTitle + " " + snippet + " " + body);
        String intentType = intent.intentType() == null ? "OTHER" : intent.intentType();
        ComparePair comparePair = extractComparePair(question);
        double queryCoverage = keywordCoverage(merged, queryKeywords);
        boolean titleStyleQuery = isTitleStyleQuery(question);
        if (titleStyleQuery && isStrongTitleMatch(question, lawTitle)) {
            score += 0.34d;
        }

        score += Math.min(0.14d, queryCoverage * 0.16d);
        if (queryCoverage < 0.16d) {
            score -= 0.07d;
        }

        // Semantic intent preference: "本国产品" should prioritize domestic-product policies.
        if (containsToken(normalize(question), "本国产品")) {
            if (containsToken(merged, "本国产品")) {
                score += 0.18d;
                if (containsAny(merged, "价格扣除", "评审优惠", "优惠")) {
                    score += 0.06d;
                }
            } else if (containsToken(merged, "中小企业")) {
                score -= 0.22d;
            } else {
                score -= 0.18d;
            }
        }

        if (isPolicyRatioQuestion(question)) {
            if (hasRatioCue(merged)) {
                score += 0.22d;
            } else {
                score -= 0.14d;
            }
            if (containsAny(merged, "争议处理", "监督检查", "投诉处理", "附件")) {
                score -= 0.08d;
            }
        }

        if (isProcurementMethodQuestion(question)) {
            if (containsAny(merged, "采购方式", "采用以下方式", "方式包括")) {
                score += 0.16d;
            }
            if (containsAny(merged,
                    "公开招标", "邀请招标", "竞争性谈判", "单一来源采购", "询价", "竞争性磋商")) {
                score += 0.24d;
            }
            if (containsAny(normalize(lawTitle), "政府采购法")) {
                score += 0.28d;
            }
            if (containsAny(merged, "ppp", "社会资本合作", "政府和社会资本合作项目")) {
                score -= 0.10d;
            }
        }

        if ("DEFINITION".equals(intentType) && containsAny(merged, "是指", "所称", "定义", "包括")) {
            score += 0.08d;
        }
        if ("PROCESS".equals(intentType) && containsAny(merged, "程序", "步骤", "流程", "办理", "期限")) {
            score += 0.07d;
        }
        if ("CONDITION".equals(intentType) && containsAny(merged, "条件", "应当", "不得", "符合", "资格")) {
            score += 0.07d;
        }
        if ("PENALTY".equals(intentType) && containsAny(merged, "处罚", "罚款", "违法", "责任", "禁止")) {
            score += 0.07d;
        }

        String targetLaw = normalize(intent.targetLaw());
        if (!targetLaw.isBlank() && normalize(lawTitle).contains(targetLaw.replace("中华人民共和国", ""))) {
            score += 0.22d;
        }

        String targetArticle = normalize(intent.targetArticle());
        if (!targetArticle.isBlank()) {
            if (!articleNo.isBlank() && normalize(articleNo).equals(targetArticle)) {
                score += 0.32d;
            } else if (merged.contains(targetArticle)) {
                score += 0.10d;
            } else {
                score -= 0.12d;
            }
        }

        if (!requestedDocNo.isBlank()) {
            String mergedDocNo = normalizeDocNo(docNo + " " + snippet + " " + lawTitle);
            if (!mergedDocNo.isBlank() && mergedDocNo.contains(requestedDocNo)) {
                score += 0.42d;
            } else {
                score -= 0.16d;
            }
        }

        if (!comparePair.isEmpty()) {
            boolean left = containsToken(merged, comparePair.left());
            boolean right = containsToken(merged, comparePair.right());
            if (left && right) {
                score += 0.13d;
            } else if (left || right) {
                score -= 0.08d;
            } else {
                score -= 0.14d;
            }
            if (containsAny(normalize(question), "适用边界", "适用范围", "适用情形")
                    && !containsAny(merged, "适用", "情形", "条件", "范围")) {
                score -= 0.08d;
            }
        }

        score += lawFormScore(lawTitle, snippet, meta.get("文档类型"), meta.get("附件属性"));

        boolean explicitRegion = hasExplicitRegionIntent(question);
        boolean national = isNational(regionLevel, lawTitle, snippet);
        boolean local = isLocal(regionLevel, lawTitle, snippet);
        if (explicitRegion) {
            if (local) {
                score += 0.10d;
            } else {
                score -= 0.03d;
            }
        } else {
            if (national) {
                score += 0.11d;
            }
            if (local) {
                score -= 0.08d;
            }
        }

        if (meta.summaryLike()) {
            score -= 0.18d;
        }
        if (meta.scanned()) {
            score -= 0.06d;
        }
        if (meta.garbled()) {
            score -= 0.12d;
        }

        int bodyLen = body == null ? 0 : body.replaceAll("\\s+", "").length();
        if (bodyLen < 40) {
            score -= 0.42d;
        } else if (bodyLen < 120) {
            score -= 0.14d;
        }
        if (bodyLen > 1500) {
            score -= Math.min(0.18d, ((double) (bodyLen - 1500)) / 4200d);
        }
        if (isManifestLikeChunk(sourcePath, lawTitle, snippet, body)) {
            score -= 0.55d;
        }

        return clampScore(score);
    }

    private List<Evidence> selectEvidence(List<EvidenceCandidate> candidates, String question, QueryIntent intent) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<EvidenceCandidate> deduped = dedupeCandidates(candidates);
        if (deduped.isEmpty()) {
            return List.of();
        }

        double topScore = deduped.get(0).score();
        double floor = Math.max(0.03d, topScore * 0.55d);
        List<EvidenceCandidate> filtered = deduped.stream()
                .filter(c -> c.score() >= floor)
                .collect(Collectors.toCollection(ArrayList::new));
        if (filtered.isEmpty()) {
            filtered = deduped.stream().limit(6).collect(Collectors.toCollection(ArrayList::new));
        }

        String requestedDocNo = extractRequestedDocNo(question);
        if (!requestedDocNo.isBlank() && isPolicyRatioQuestion(question)) {
            List<EvidenceCandidate> sameDoc = deduped.stream()
                    .filter(c -> c.matchesDocNo(requestedDocNo))
                    .limit(6)
                    .toList();
            if (!sameDoc.isEmpty()) {
                List<EvidenceCandidate> ratioFirst = prioritizeRatioEvidence(sameDoc);
                if (!ratioFirst.isEmpty()) {
                    return ratioFirst.stream()
                            .limit(3)
                            .map(EvidenceCandidate::evidence)
                            .toList();
                }
                filtered = new ArrayList<>(sameDoc);
            }
        }

        if (isPolicyRatioQuestion(question)) {
            List<EvidenceCandidate> ratioFirst = prioritizeRatioEvidence(filtered);
            if (!ratioFirst.isEmpty()) {
                filtered = new ArrayList<>(ratioFirst);
            }
        }

        if (isTitleStyleQuery(question)) {
            List<EvidenceCandidate> titleHits = deduped.stream()
                    .filter(c -> isStrongTitleMatch(question, c.lawTitle())
                            || isStrongTitleMatch(question, c.evidence().title()))
                    .toList();
            if (!titleHits.isEmpty()) {
                LinkedHashSet<EvidenceCandidate> ordered = new LinkedHashSet<>();
                ordered.addAll(titleHits);
                ordered.addAll(filtered);
                filtered = new ArrayList<>(ordered);
            }
        }

        if (shouldUseSingleEvidence(filtered, question, intent)) {
            return List.of(filtered.get(0).evidence());
        }

        boolean explicitRegion = hasExplicitRegionIntent(question);
        List<EvidenceCandidate> selected = new ArrayList<>();
        Map<String, Integer> perLawCount = new LinkedHashMap<>();

        if (!explicitRegion) {
            EvidenceCandidate firstNational = filtered.stream().filter(EvidenceCandidate::isNational).findFirst().orElse(null);
            if (firstNational != null) {
                EvidenceCandidate candidate = firstNational;
                selected.add(candidate);
                perLawCount.put(normalize(candidate.lawTitle()), 1);
            }
        } else {
            EvidenceCandidate firstLocal = filtered.stream().filter(EvidenceCandidate::isLocal).findFirst().orElse(null);
            if (firstLocal != null) {
                EvidenceCandidate candidate = firstLocal;
                selected.add(candidate);
                perLawCount.put(normalize(candidate.lawTitle()), 1);
            }
        }

        for (EvidenceCandidate candidate : filtered) {
            if (selected.contains(candidate)) {
                continue;
            }
            String lawKey = normalize(candidate.lawTitle());
            int already = perLawCount.getOrDefault(lawKey, 0);
            if (already >= 2) {
                continue;
            }
            selected.add(candidate);
            perLawCount.put(lawKey, already + 1);
            if (selected.size() >= 6) {
                break;
            }
        }

        if (selected.isEmpty()) {
            selected.addAll(filtered.stream().limit(4).toList());
        }

        int evidenceLimit = resolveEvidenceLimit(question, intent, filtered);
        if (selected.size() < evidenceLimit) {
            for (EvidenceCandidate candidate : filtered) {
                if (selected.contains(candidate)) {
                    continue;
                }
                selected.add(candidate);
                if (selected.size() >= evidenceLimit) {
                    break;
                }
            }
        }

        return selected.stream()
                .limit(evidenceLimit)
                .map(EvidenceCandidate::evidence)
                .toList();
    }

    private boolean shouldUseSingleEvidence(List<EvidenceCandidate> ranked, String question, QueryIntent intent) {
        if (ranked == null || ranked.isEmpty()) {
            return false;
        }
        String normalizedQuestion = normalize(question);
        boolean definitionQuestion = isDefinitionQuestion(normalizedQuestion, intent);
        if (isBroadQuestion(normalizedQuestion)
                || isCompareQuestion(normalizedQuestion)
                || isPolicyRatioQuestion(normalizedQuestion)) {
            return false;
        }

        EvidenceCandidate top = ranked.get(0);
        EvidenceCandidate second = ranked.size() > 1 ? ranked.get(1) : null;
        double gap = second == null ? top.score() : (top.score() - second.score());

        String requestedDocNo = extractRequestedDocNo(normalizedQuestion);
        boolean docNoHit = !requestedDocNo.isBlank() && top.matchesDocNo(requestedDocNo);
        String targetArticle = normalize(intent.targetArticle());
        boolean articleHit = !targetArticle.isBlank() && normalize(defaultIfBlank(top.articleNo(), "")).equals(targetArticle);
        double queryCoverage = questionEvidenceCoverage(normalizedQuestion, top);
        boolean titleHit = isStrongTitleMatch(question, top.lawTitle())
                || isStrongTitleMatch(question, top.evidence().title());

        double coverageThreshold = definitionQuestion ? 0.72d : 0.60d;
        boolean strongAnchor = docNoHit || articleHit || titleHit || queryCoverage >= coverageThreshold;
        boolean clearLead;
        if (definitionQuestion) {
            // Definition questions can use single evidence, but only when top hit is clearly dominant.
            clearLead = top.score() >= 0.84d && (gap >= 0.18d || second == null || second.score() < 0.38d);
        } else {
            clearLead = top.score() >= 0.74d && (gap >= 0.12d || second == null || second.score() < 0.45d);
        }
        boolean hasValidTitle = !defaultIfBlank(top.lawTitle(), "").contains("未知法规");

        return hasValidTitle && strongAnchor && clearLead;
    }

    private boolean isBroadQuestion(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "有哪些", "哪些", "几种", "哪几种", "多少种", "方式有哪些",
                "风险点", "注意事项", "核心要求", "要点", "总结", "全流程", "全面", "概述");
    }

    private int resolveEvidenceLimit(String question, QueryIntent intent, List<EvidenceCandidate> filtered) {
        if (!isDefinitionQuestion(question, intent)) {
            return 6;
        }
        if (filtered == null || filtered.isEmpty()) {
            return 2;
        }
        double top = filtered.get(0).score();
        double second = filtered.size() > 1 ? filtered.get(1).score() : 0d;
        boolean strongSingleLead = top >= 0.78d && (filtered.size() == 1 || top - second >= 0.18d);
        return strongSingleLead ? 2 : 3;
    }

    private boolean isDefinitionQuestion(String question, QueryIntent intent) {
        if (intent != null && "DEFINITION".equals(intent.intentType())) {
            return true;
        }
        String normalized = normalize(question);
        return containsAny(normalized, "什么是", "是什么", "是指", "定义", "概念", "含义");
    }

    private boolean isCompareQuestion(String normalizedQuestion) {
        return normalizedQuestion.contains("与")
                && containsAny(normalizedQuestion, "适用边界", "区别", "差异", "关系", "比较");
    }

    private boolean isPolicyRatioQuestion(String normalizedQuestion) {
        return containsAny(normalizedQuestion,
                "价格减免", "价格扣除", "评审优惠", "扣除比例", "优惠比例", "百分之", "20%", "20％")
                || (normalizedQuestion.contains("20") && normalizedQuestion.contains("价格"));
    }

    private List<EvidenceCandidate> prioritizeRatioEvidence(List<EvidenceCandidate> sameDoc) {
        if (sameDoc == null || sameDoc.isEmpty()) {
            return List.of();
        }
        List<EvidenceCandidate> ratioHits = sameDoc.stream()
                .filter(this::hasRatioCue)
                .sorted((a, b) -> {
                    int domesticCmp = Boolean.compare(hasDomesticRatioCue(b), hasDomesticRatioCue(a));
                    if (domesticCmp != 0) {
                        return domesticCmp;
                    }
                    int nativeCmp = Boolean.compare(containsToken(candidateMergedText(b), "本国产品"),
                            containsToken(candidateMergedText(a), "本国产品"));
                    if (nativeCmp != 0) {
                        return nativeCmp;
                    }
                    return Double.compare(b.score(), a.score());
                })
                .toList();
        if (ratioHits.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<EvidenceCandidate> ordered = new LinkedHashSet<>();
        ordered.addAll(ratioHits);
        for (EvidenceCandidate candidate : sameDoc) {
            if (ordered.size() >= 3) {
                break;
            }
            ordered.add(candidate);
        }
        return new ArrayList<>(ordered);
    }

    private boolean hasDomesticRatioCue(EvidenceCandidate candidate) {
        String merged = candidateMergedText(candidate);
        return containsToken(merged, "本国产品") && hasRatioCue(merged);
    }

    private boolean hasRatioCue(EvidenceCandidate candidate) {
        String merged = candidateMergedText(candidate);
        return hasRatioCue(merged);
    }

    private String candidateMergedText(EvidenceCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        return normalize(defaultIfBlank(candidate.evidence().title(), "")
                + " " + defaultIfBlank(candidate.evidence().snippet(), "")
                + " " + defaultIfBlank(candidate.articleNo(), "")
                + " " + defaultIfBlank(candidate.lawTitle(), ""));
    }

    private boolean hasRatioCue(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return RATIO_CUE_PATTERN.matcher(normalizedText).find();
    }

    private double questionEvidenceCoverage(String normalizedQuestion, EvidenceCandidate candidate) {
        String merged = normalize(defaultIfBlank(candidate.evidence().title(), "")
                + " " + defaultIfBlank(candidate.evidence().snippet(), ""));
        if (merged.isBlank()) {
            return 0d;
        }
        int total = 0;
        int matched = 0;
        for (String token : normalizedQuestion.split("\\s+")) {
            String t = token.trim();
            if (t.length() < 2 || isQuestionStopword(t)) {
                continue;
            }
            total++;
            if (merged.contains(t)) {
                matched++;
            }
        }
        if (total == 0) {
            return 0d;
        }
        return (double) matched / total;
    }

    private List<EvidenceCandidate> dedupeCandidates(List<EvidenceCandidate> candidates) {
        Map<String, EvidenceCandidate> byKey = new LinkedHashMap<>();
        for (EvidenceCandidate candidate : candidates) {
            String key = normalize(candidate.lawTitle()) + "|"
                    + normalize(candidate.articleNo()) + "|"
                    + normalize(candidate.sourcePath());
            EvidenceCandidate previous = byKey.get(key);
            if (previous == null || candidate.score() > previous.score()) {
                byKey.put(key, candidate);
            }
        }
        return byKey.values().stream()
                .sorted(Comparator.comparingDouble(EvidenceCandidate::score).reversed())
                .toList();
    }

    private List<String> buildQueryKeywords(String question, QueryIntent intent) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (intent.keywords() != null) {
            for (String keyword : intent.keywords()) {
                String k = normalize(keyword);
                if (k.length() >= 2) {
                    keywords.add(k);
                }
            }
        }
        String normalizedQuestion = normalize(question);
        for (String token : normalizedQuestion.split("\\s+")) {
            String t = token.trim();
            if (t.length() >= 2 && !isQuestionStopword(t)) {
                keywords.add(t);
            }
        }
        // Semantic expansions for common policy phrasing differences.
        if (normalizedQuestion.contains("减免")) {
            keywords.add("扣除");
            keywords.add("优惠");
            keywords.add("价格扣除");
            keywords.add("评审优惠");
        }
        if (normalizedQuestion.contains("本国产品")) {
            keywords.add("本国产品标准");
            keywords.add("声明函");
            keywords.add("组件成本");
        }
        if (normalizedQuestion.contains("20") || normalizedQuestion.contains("二十")) {
            keywords.add("20%");
            keywords.add("20％");
            keywords.add("百分之二十");
        }
        if (isProcurementMethodQuestion(normalizedQuestion)) {
            keywords.add("采购方式");
            keywords.add("公开招标");
            keywords.add("邀请招标");
            keywords.add("竞争性谈判");
            keywords.add("单一来源采购");
            keywords.add("询价");
            keywords.add("竞争性磋商");
            keywords.add("政府采购法");
            keywords.add("第二十六条");
        }
        if (keywords.isEmpty()) {
            String compact = compact(normalizedQuestion);
            for (int n = Math.min(4, compact.length()); n >= 2; n--) {
                for (int i = 0; i <= compact.length() - n; i++) {
                    keywords.add(compact.substring(i, i + n));
                    if (keywords.size() >= 20) {
                        return new ArrayList<>(keywords);
                    }
                }
            }
        }
        return keywords.stream().limit(20).toList();
    }

    private String enrichRetrievalQuery(String retrievalQuery, String normalizedQuestion) {
        StringBuilder sb = new StringBuilder(defaultIfBlank(retrievalQuery, ""));
        if (normalizedQuestion.contains("减免")) {
            sb.append(" 价格扣除 评审优惠 优惠");
        }
        if (normalizedQuestion.contains("本国产品")) {
            sb.append(" 本国产品标准 声明函 组件成本");
        }
        if (normalizedQuestion.contains("20") || normalizedQuestion.contains("二十")) {
            sb.append(" 20% 20％ 百分之二十");
        }
        if (isProcurementMethodQuestion(normalizedQuestion)) {
            sb.append(" 政府采购法 第二十六条 采购方式 公开招标 邀请招标 竞争性谈判 单一来源采购 询价 竞争性磋商");
        }
        return sb.toString().trim();
    }

    private boolean isProcurementMethodQuestion(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return false;
        }
        boolean procurement = containsAny(normalizedQuestion, "政府采购", "采购");
        boolean method = containsAny(normalizedQuestion, "采购方式", "几种", "哪几种", "多少种", "方式");
        return procurement && method;
    }

    private boolean isQuestionStopword(String token) {
        return List.of("什么", "哪些", "怎么", "如何", "多少", "是否", "规定", "要求", "问题", "相关").contains(token);
    }

    private boolean isManifestLikeChunk(String sourcePath, String lawTitle, String snippet, String body) {
        String path = defaultIfBlank(sourcePath, "").toLowerCase(Locale.ROOT);
        if (path.endsWith("list.txt") || path.endsWith("list_new.txt") || path.endsWith("list_sorted.txt")) {
            return true;
        }
        String merged = normalize(defaultIfBlank(lawTitle, "") + " "
                + defaultIfBlank(snippet, "") + " " + defaultIfBlank(body, ""));
        return merged.contains("|/zcfg/")
                || merged.contains(".html|")
                || merged.contains("|http");
    }

    private String buildDisplayTitle(String lawTitle, String articleNo, String clauseNo, String docNo) {
        StringBuilder sb = new StringBuilder();
        sb.append(defaultIfBlank(lawTitle, "未知法规"));
        if (!articleNo.isBlank()) {
            sb.append(" ").append(articleNo);
        }
        if (!clauseNo.isBlank()) {
            sb.append(" ").append(clauseNo);
        }
        if (!docNo.isBlank() && !sb.toString().contains(docNo)) {
            sb.append("（").append(docNo).append("）");
        }
        return sb.toString();
    }

    private String buildSnippet(String body, List<String> keywords, int maxLen) {
        String normalized = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "[正文缺失]";
        }

        int bestIdx = -1;
        for (String keyword : keywords) {
            if (keyword == null || keyword.length() < 2) {
                continue;
            }
            int idx = normalize(normalized).indexOf(normalize(keyword));
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
            }
        }

        if (bestIdx < 0) {
            return truncate(normalized, maxLen);
        }

        int from = Math.max(0, bestIdx - 140);
        int to = Math.min(normalized.length(), from + maxLen);
        String excerpt = normalized.substring(from, to);
        if (from > 0) {
            excerpt = "..." + excerpt;
        }
        if (to < normalized.length()) {
            excerpt = excerpt + "...";
        }
        return excerpt;
    }

    private MetaBlock parseMeta(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new MetaBlock(Map.of(), false, false, false);
        }
        Map<String, String> data = new LinkedHashMap<>();
        Matcher matcher = META_LINE_PATTERN.matcher(rawContent);
        while (matcher.find()) {
            data.put(matcher.group(1).trim(), matcher.group(2).trim());
        }
        boolean scanned = "true".equalsIgnoreCase(data.getOrDefault("疑似扫描", ""));
        boolean garbled = "true".equalsIgnoreCase(data.getOrDefault("疑似乱码", ""));
        boolean summaryLike = "true".equalsIgnoreCase(data.getOrDefault("疑似摘要页", ""));
        return new MetaBlock(data, scanned, garbled, summaryLike);
    }

    private String stripMetaLines(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.replaceAll("(?m)^\\[[^\\]]+\\].*$", "").trim();
    }

    private String extractRequestedDocNo(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        Matcher matcher = DOC_NO_PATTERN.matcher(question);
        if (!matcher.find()) {
            // Be tolerant of spaced input such as:
            // "国 办 发 〔 2 0 2 5 〕 3 4 号"
            String compactQuestion = compact(question);
            matcher = DOC_NO_PATTERN.matcher(compactQuestion);
            if (!matcher.find()) {
                return "";
            }
        }
        String prefix = matcher.group(1);
        String year = matcher.group(2);
        String seq = matcher.group(3);
        return normalizeDocNo(prefix + year + seq + "号");
    }

    private String extractDocNoFromText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = DOC_NO_PATTERN.matcher(text);
        if (!matcher.find()) {
            matcher = DOC_NO_PATTERN.matcher(compact(text));
            if (!matcher.find()) {
                return "";
            }
        }
        String prefix = matcher.group(1);
        String year = matcher.group(2);
        String seq = matcher.group(3);
        return prefix + "〔" + year + "〕" + seq + "号";
    }

    private String repairLawTitle(String currentTitle, String sourcePath) {
        String normalizedCurrent = defaultIfBlank(currentTitle, "").trim();
        boolean currentLooksWeak = normalizedCurrent.length() < 6
                || normalizedCurrent.contains("办公打文件")
                || !LAW_TITLE_CUE_PATTERN.matcher(normalizedCurrent).find();
        if (!currentLooksWeak) {
            return normalizedCurrent;
        }
        String fromPath = extractLawTitleFromPath(sourcePath);
        if (!fromPath.isBlank()) {
            return fromPath;
        }
        return normalizedCurrent;
    }

    private String extractLawTitleFromPath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return "";
        }
        try {
            Path p = Paths.get(sourcePath);
            for (Path cursor = p; cursor != null; cursor = cursor.getParent()) {
                String name = cursor.getFileName() == null ? "" : cursor.getFileName().toString();
                if (name.isBlank()) {
                    continue;
                }
                String stem = name.replaceFirst("\\.[^.]+$", "");
                stem = stem.replaceFirst("^[0-9]+[-_.、 ]*", "").trim();
                if (stem.length() >= 6 && LAW_TITLE_CUE_PATTERN.matcher(stem).find()) {
                    return stem;
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private static String normalizeDocNo(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("（", "")
                .replace("）", "")
                .replace("〔", "")
                .replace("〕", "")
                .replace("(", "")
                .replace(")", "")
                .replace("[", "")
                .replace("]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private boolean hasExplicitRegionIntent(String q) {
        String text = normalize(q);
        return containsAny(text, "江苏", "扬州", "本市", "市本级", "省级", "地方", "苏财", "扬财", "省财政厅", "市财政局");
    }

    private boolean isTitleStyleQuery(String question) {
        String text = normalize(question);
        if (text.isBlank()) {
            return false;
        }
        return (text.contains("关于") || text.contains("印发") || text.contains("转发"))
                && containsAny(text, "通知", "办法", "条例", "意见", "规定", "标准", "方案", "细则", "法");
    }

    private boolean isStrongTitleMatch(String question, String lawTitle) {
        String q = compact(normalize(question));
        String t = compact(normalize(lawTitle));
        if (q.isBlank() || t.isBlank() || q.length() < 8 || t.length() < 8) {
            return false;
        }
        if (t.contains(q)) {
            return true;
        }
        // Tolerate small wording differences: compare long fragments.
        int maxN = Math.min(22, q.length());
        for (int n = maxN; n >= 10; n--) {
            for (int i = 0; i <= q.length() - n; i++) {
                String sub = q.substring(i, i + n);
                if (t.contains(sub)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNational(String regionLevel, String lawTitle, String snippet) {
        String merged = normalize(defaultIfBlank(regionLevel, "") + " " + lawTitle + " " + snippet);
        return merged.contains("national")
                || merged.contains("中华人民共和国")
                || merged.contains("国务院");
    }

    private boolean isLocal(String regionLevel, String lawTitle, String snippet) {
        String merged = normalize(defaultIfBlank(regionLevel, "") + " " + lawTitle + " " + snippet);
        return containsAny(merged, "provincial", "municipal", "江苏", "扬州", "本市", "市本级", "省财政厅", "市财政局");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsToken(String normalizedText, String token) {
        if (normalizedText == null || normalizedText.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        String t = normalize(token);
        if (t.isBlank()) {
            return false;
        }
        return normalizedText.contains(t);
    }

    private double keywordCoverage(String normalizedText, List<String> queryKeywords) {
        if (normalizedText == null || normalizedText.isBlank() || queryKeywords == null || queryKeywords.isEmpty()) {
            return 0d;
        }
        int total = 0;
        int matched = 0;
        for (String keyword : queryKeywords) {
            String k = normalize(keyword);
            if (k.length() < 2 || isQuestionStopword(k)) {
                continue;
            }
            total++;
            if (normalizedText.contains(k)) {
                matched++;
            }
        }
        if (total == 0) {
            return 0d;
        }
        return (double) matched / total;
    }

    private double lawFormScore(String lawTitle, String snippet, String docType, String attachmentType) {
        String merged = normalize(defaultIfBlank(lawTitle, "") + " " + defaultIfBlank(snippet, ""));
        double score = 0d;
        if (containsAny(merged, "政府采购法", "条例", "办法", "规定", "通知", "意见", "令", "司法解释")) {
            score += 0.05d;
        }
        if (containsAny(merged, "附件", "目录", "清单", "样表", "模板", "封面")) {
            score -= 0.05d;
        }
        if (containsAny(normalize(defaultIfBlank(docType, "")), "attachment", "附件")) {
            score -= 0.03d;
        }
        if (containsAny(normalize(defaultIfBlank(attachmentType, "")), "reference", "参考")) {
            score -= 0.04d;
        }
        return score;
    }

    private ComparePair extractComparePair(String question) {
        if (question == null || question.isBlank()) {
            return ComparePair.EMPTY;
        }
        Matcher matcher = COMPARE_PATTERN.matcher(question.replaceAll("\\s+", ""));
        if (!matcher.find()) {
            return ComparePair.EMPTY;
        }
        String left = normalizeCompareTerm(matcher.group(1));
        String right = normalizeCompareTerm(matcher.group(3));
        if (left.length() < 2 || right.length() < 2) {
            return ComparePair.EMPTY;
        }
        return new ComparePair(left, right);
    }

    private String normalizeCompareTerm(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String term = raw.trim()
                .replaceFirst("^(请解释|解释|说明|请按法规依据说明|请按依据说明|请按法规说明)", "")
                .replaceFirst("^(在[\\u4e00-\\u9fa5]{1,8}，)", "")
                .replaceAll("(的|之)$", "")
                .trim();
        return term;
    }

    private boolean isLlmUnavailable(String answer) {
        if (answer == null) {
            return true;
        }
        return answer.startsWith("MiniMax 鉴权失败")
                || answer.startsWith("MiniMax 调用失败")
                || answer.startsWith("MiniMax 调用异常")
                || answer.startsWith("MiniMax API Key 未配置");
    }

    private String buildFallbackAnswer(List<Evidence> evidences,
                                       String llmError,
                                       String requestedDocNo,
                                       boolean hasExactRequestedDocNo) {
        List<Evidence> top = evidences.stream().limit(3).toList();
        String docNoHint = "";
        if (!requestedDocNo.isBlank() && !hasExactRequestedDocNo) {
            docNoHint = "说明：当前证据未精确命中文号 " + requestedDocNo + "，以下为最相关替代依据。\n";
        }
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            Evidence e = top.get(i);
            refs.append(i + 1).append(". ").append(e.title()).append("\n")
                    .append("   摘要：").append(truncate(e.snippet(), 180)).append("\n");
        }
        return "当前大模型不可用，先基于检索证据给出参考结论。\n"
                + docNoHint
                + "结论：已命中多条相关依据，请按以下优先顺序核对。\n"
                + "依据列表：\n" + refs
                + "提示：" + llmError;
    }

    private boolean isMissAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        return answer.contains("未找到足够依据");
    }

    private boolean hasStrongEvidence(List<Evidence> evidences, String normalizedQuestion) {
        if (evidences == null || evidences.isEmpty()) {
            return false;
        }
        String[] qTokens = normalize(normalizedQuestion).split("\\s+");
        for (Evidence e : evidences) {
            String merged = normalize(defaultIfBlank(e.title(), "") + " " + defaultIfBlank(e.snippet(), ""));
            if (merged.length() < 30) {
                continue;
            }
            if (containsAny(merged, "第", "条", "款", "应当", "不得", "期限", "条件")) {
                return true;
            }
            int hit = 0;
            for (String token : qTokens) {
                if (token.length() < 2 || isQuestionStopword(token)) {
                    continue;
                }
                if (merged.contains(token)) {
                    hit++;
                }
            }
            if (hit >= 2) {
                return true;
            }
        }
        return false;
    }

    private String buildRescueSystemPrompt() {
        return "你是政府采购法律政策助手。必须基于给定证据回答，不得编造。"
                + "你当前处于二次核验模式：若证据中已出现明确法规标题、条号或实质性规范内容，不得输出“未找到足够依据”。"
                + "请输出：1) 结论；2) 依据条款（至少2条，若不足2条则全部列出）；3) 适用条件；4) 不确定点。"
                + "禁止输出路径信息。";
    }

    private String buildRescueUserPrompt(String question, List<Evidence> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(question).append("\n\n");
        sb.append("证据清单：\n");
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            sb.append("证据").append(i + 1).append("：").append(e.title()).append("\n")
                    .append("摘录：").append(truncate(defaultIfBlank(e.snippet(), ""), 900)).append("\n\n");
        }
        sb.append("请综合全部证据，不要只选单条。若证据存在冲突，先给上位法结论，再给地方细则补充。");
        return sb.toString();
    }

    private String generateGeneralLlmAnswer(String question) {
        String raw = llmClient.chat(buildGeneralSystemPrompt(), buildGeneralUserPrompt(question));
        if (isLlmUnavailable(raw)) {
            return "";
        }
        String sanitized = sanitizeAnswer(raw);
        if (sanitized.isBlank() || isMissAnswer(sanitized)) {
            return "";
        }
        return sanitized;
    }

    private String buildGeneralSystemPrompt() {
        return "你是政府采购助手。当前法规证据库未命中可直接条款。"
                + "请基于通用知识提供保守、清晰、可执行的参考回答。"
                + "不得伪造具体条号、文号；若不确定请明确提示需人工核对。";
    }

    private String buildGeneralUserPrompt(String question) {
        return "问题：" + question + "\n"
                + "请给出：1) 简明结论；2) 通用处理建议；3) 需要补充的关键信息。";
    }

    private String sanitizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = THINK_BLOCK_PATTERN.matcher(raw).replaceAll("").trim();
        int openThink = cleaned.toLowerCase(Locale.ROOT).indexOf("<think>");
        if (openThink >= 0) {
            cleaned = cleaned.substring(0, openThink).trim();
        }
        cleaned = SOURCE_PATH_LINE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = ABSOLUTE_PATH_PATTERN.matcher(cleaned).replaceAll("[已隐藏路径]");
        cleaned = cleaned.replaceAll("(?m)\\n{3,}", "\n\n").trim();
        if (cleaned.length() > MAX_ANSWER_LEN) {
            cleaned = cleaned.substring(0, MAX_ANSWER_LEN) + "...";
        }
        return cleaned;
    }

    private double clampScore(double score) {
        if (score < 0d) {
            return 0d;
        }
        if (score > 1d) {
            return 1d;
        }
        return score;
    }

    private String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", "");
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private record MetaBlock(Map<String, String> fields,
                             boolean scanned,
                             boolean garbled,
                             boolean summaryLike) {
        String get(String key) {
            return fields.getOrDefault(key, "");
        }
    }

    private record ComparePair(String left, String right) {
        static final ComparePair EMPTY = new ComparePair("", "");

        boolean isEmpty() {
            return left.isBlank() || right.isBlank();
        }
    }

    private record EvidenceCandidate(Evidence evidence,
                                     String sourcePath,
                                     String lawTitle,
                                     String articleNo,
                                     String docNo,
                                     String regionLevel,
                                     boolean summaryLike,
                                     boolean scanned,
                                     boolean garbled) {
        double score() {
            return evidence.score();
        }

        boolean isNational() {
            String merged = (defaultText(regionLevel) + " " + defaultText(lawTitle) + " " + defaultText(evidence.title()))
                    .toLowerCase(Locale.ROOT);
            return merged.contains("national")
                    || merged.contains("中华人民共和国")
                    || merged.contains("国务院");
        }

        boolean isLocal() {
            String merged = (defaultText(regionLevel) + " " + defaultText(lawTitle) + " " + defaultText(evidence.title()))
                    .toLowerCase(Locale.ROOT);
            return merged.contains("provincial")
                    || merged.contains("municipal")
                    || merged.contains("江苏")
                    || merged.contains("扬州")
                    || merged.contains("本市")
                    || merged.contains("市本级")
                    || merged.contains("省财政厅")
                    || merged.contains("市财政局");
        }

        boolean matchesDocNo(String requestedDocNo) {
            if (requestedDocNo == null || requestedDocNo.isBlank()) {
                return false;
            }
            String merged = normalizeDocNo(defaultText(docNo)
                    + " " + defaultText(evidence.title())
                    + " " + defaultText(evidence.snippet())
                    + " " + defaultText(sourcePath));
            return !merged.isBlank() && merged.contains(requestedDocNo);
        }

        private static String defaultText(String text) {
            return text == null ? "" : text;
        }
    }

    private record CachedQueryResult(QueryResult result, long cachedAtMs) {
    }

    public record QueryResult(String answer, List<Evidence> evidences) {
    }
}
