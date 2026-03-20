package com.brandonbot.legalassistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.brandonbot.legalassistant.config.AppProperties;
import com.brandonbot.legalassistant.dto.IngestResponse;
import com.brandonbot.legalassistant.model.DocumentChunk;
import com.brandonbot.legalassistant.model.LegalDocument;
import com.brandonbot.legalassistant.store.VectorStoreGateway;
import com.brandonbot.legalassistant.util.PathUtil;
import com.brandonbot.legalassistant.util.TextChunker;
import com.brandonbot.legalassistant.util.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class IngestService {
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万0-9]+[条章节款]");
    private static final Pattern ARTICLE_NO_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万0-9]+条");
    private static final Pattern CLAUSE_NO_PATTERN = Pattern.compile("第[一二三四五六七八九十百千万0-9]+款");
    private static final Pattern CLAUSE_ITEM_PATTERN = Pattern.compile("（[一二三四五六七八九十百千万0-9]+）");
    private static final Pattern LEGAL_ATTACHMENT_NAME = Pattern.compile("(办法|细则|标准|规定|条例|通知|决定|意见|合同|制度|规则|指引|规范)");
    private static final Pattern REFERENCE_ATTACHMENT_NAME = Pattern.compile("(样本|模板|清单|汇总|目录|台账|流程|封面|附表|表格|格式)");
    private static final Pattern ATTACHMENT_TITLE_SUFFIX = Pattern.compile("（附件:\\s*[^）]+）$");
    private static final Pattern SUMMARY_HINT = Pattern.compile("(发稿时间|访问量|当前位置|上一篇|下一篇|来源[:：]|打印本页|关闭窗口)");
    private static final Pattern REFERENCE_FILE_HINT = Pattern.compile("(封面|目录|样本|模板|清单|汇总|台账|流程|附表|格式|名录)");
    private static final Pattern LEGAL_FILE_HINT = Pattern.compile("(办法|细则|条例|规定|通知|决定|意见|规则|指引|法|实施)");
    private static final Pattern PAGE_TITLE_HINT = Pattern.compile("^第\\s*[0-9一二三四五六七八九十百千万]+\\s*页\\s*共\\s*[0-9一二三四五六七八九十百千万]+\\s*页.*$");
    private static final Pattern GENERIC_TITLE_HINT = Pattern.compile("^(内容|正文|附件|目录|首页|上一页|下一页|点击下载|下载)$");
    private static final Pattern LEADING_SERIAL_HINT = Pattern.compile("^[（(]?[0-9一二三四五六七八九十]+[)）.、]\\s*.+$");
    private static final Pattern CHUNK_META_LINE_PATTERN = Pattern.compile("(?m)^\\[[^\\]]+\\].*$");
    private static final String DOC_NO_PREFIX =
            "(财办库|财库|国办发|国发|苏财购函|苏财购|苏财规|扬财购|扬财规|扬财行|扬政务联字|扬银发|财税|财综|财金|财行|苏办发|国务院令|财政部令|公安部令|国家发展改革委令|发展改革委令)";
    private static final Pattern DOC_NO_YEAR_SEQ_PATTERN = Pattern.compile(
            DOC_NO_PREFIX + "\\s*[〔\\[（(【]?\\s*(20\\d{2})\\s*[〕\\]）)】]?\\s*([0-9]{1,4})\\s*号");
    private static final Pattern DOC_NO_ORDER_PATTERN = Pattern.compile(
            DOC_NO_PREFIX + "\\s*第\\s*([0-9]{1,4})\\s*号");
    private static final Pattern DATE_CN_PATTERN = Pattern.compile("(20\\d{2})\\s*年\\s*(1[0-2]|0?[1-9])\\s*月\\s*(3[01]|[12]?\\d)\\s*日");
    private static final Pattern DATE_ISO_PATTERN = Pattern.compile("(20\\d{2})[-/.](1[0-2]|0?[1-9])[-/.](3[01]|[12]?\\d)");
    private static final Pattern EFFECTIVE_DATE_HINT_PATTERN = Pattern.compile(
            "(?:自|从)?\\s*((?:20\\d{2})\\s*年\\s*(?:1[0-2]|0?[1-9])\\s*月\\s*(?:3[01]|[12]?\\d)\\s*日)\\s*(?:起)?(?:施行|实施|执行)");
    private static final Pattern ISSUER_FUZZY_PATTERN = Pattern.compile(
            "([\\p{IsHan}]{2,24}(?:国务院办公厅|国务院|全国人大常委会|财政部办公厅|财政部|省财政厅|市财政局|财政厅|财政局|办公厅|办公室|发展改革委|工业和信息化部|住房城乡建设部|生态环境部|农业农村部|乡村振兴局|供销合作总社|公共资源交易中心))");
    private static final int SUMMARY_BODY_THRESHOLD = 2400;
    private static final int SUMMARY_PREVIEW_MAX_LEN = 700;
    private static final int META_EXTRA_MAX_FIELDS = 18;
    private static final int META_EXTRA_MAX_LEN = 200;
    private static final int MIN_SUBSTANTIVE_CHUNK_CHARS = 36;

    private final AppProperties appProperties;
    private final TextExtractor textExtractor;
    private final TextChunker textChunker;
    private final VectorStoreGateway vectorStoreGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestService(AppProperties appProperties,
                         TextExtractor textExtractor,
                         TextChunker textChunker,
                         VectorStoreGateway vectorStoreGateway) {
        this.appProperties = appProperties;
        this.textExtractor = textExtractor;
        this.textChunker = textChunker;
        this.vectorStoreGateway = vectorStoreGateway;
    }

    public IngestResponse fullSync() {
        Path root = Path.of(appProperties.corpus().rootPath());
        if (!Files.exists(root)) {
            return new IngestResponse(0, 0, "法规目录不存在: " + root);
        }

        List<PrimaryDoc> primaryDocs = findPrimaryDocs(root);
        List<DocumentChunk> chunkBuffer = new ArrayList<>();
        AtomicInteger docCount = new AtomicInteger();

        for (PrimaryDoc primaryDocPath : primaryDocs) {
            try {
                LegalDocument primary = parsePrimaryDocument(primaryDocPath.path(), primaryDocPath.reason());
                if (!primary.text().isBlank()) {
                    appendChunks(chunkBuffer, primary);
                    docCount.incrementAndGet();
                }

                List<LegalDocument> attachments = parseAttachmentDocuments(
                        primaryDocPath.path(),
                        primary.id(),
                        primary.title(),
                        primary.regionLevel()
                );
                for (LegalDocument attachmentDoc : attachments) {
                    if (attachmentDoc.text().isBlank()) {
                        continue;
                    }
                    appendChunks(chunkBuffer, attachmentDoc);
                    docCount.incrementAndGet();
                }
            } catch (Exception ignored) {
                // Skip broken file and continue sync.
            }
        }

        vectorStoreGateway.clear();
        vectorStoreGateway.upsertChunks(chunkBuffer);

        return new IngestResponse(docCount.get(), chunkBuffer.size(), "同步完成");
    }

    private List<PrimaryDoc> findPrimaryDocs(Path root) {
        try {
            List<Path> files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .toList();

            if (files.isEmpty()) {
                return List.of();
            }

            Map<Path, List<Path>> byDir = new LinkedHashMap<>();
            for (Path file : files) {
                Path parent = file.getParent();
                if (parent == null) {
                    continue;
                }
                byDir.computeIfAbsent(parent, k -> new ArrayList<>()).add(file);
            }

            List<PrimaryDoc> primaryDocs = new ArrayList<>();
            for (Map.Entry<Path, List<Path>> entry : byDir.entrySet()) {
                primaryDocs.addAll(selectPrimaryDocs(entry.getKey(), entry.getValue()));
            }

            return primaryDocs.stream()
                    .distinct()
                    .sorted(Comparator.comparing(a -> a.path().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<PrimaryDoc> selectPrimaryDocs(Path dir, List<Path> dirFiles) {
        List<Path> supported = dirFiles.stream()
                .filter(this::isSupportedPrimary)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (supported.isEmpty()) {
            return List.of();
        }

        Path contentPage = supported.stream()
                .filter(this::isContentPage)
                .findFirst()
                .orElse(null);
        if (contentPage != null) {
            Path primary = contentPage;
            String reason = "命中 content.*";
            if (looksLikePortalSummary(contentPage, supported)) {
                Path preferred = choosePreferredPrimarySibling(supported, contentPage);
                if (preferred != null) {
                    primary = preferred;
                    reason = "content.* 疑似摘要，自动切换为正文文件";
                }
            }
            return List.of(new PrimaryDoc(primary, reason));
        }

        List<Path> nonReference = supported.stream()
                .filter(p -> !isLikelyReferenceFileName(p))
                .toList();
        List<Path> primaries = nonReference.isEmpty() ? supported : nonReference;
        String reason = nonReference.isEmpty()
                ? "目录无 content.*，全部文件按独立主文入库"
                : "目录无 content.*，过滤明显参考类文件后入库";
        return primaries.stream()
                .map(p -> new PrimaryDoc(p, reason))
                .toList();
    }

    private boolean isContentPage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.equals("content.md") || n.equals("content.html");
    }

    private boolean looksLikePortalSummary(Path contentPath, List<Path> siblings) {
        try {
            String text = textExtractor.extractWithMeta(contentPath).safeText();
            if (text == null || text.isBlank()) {
                return true;
            }
            String compact = text.replaceAll("\\s+", "");
            int hints = 0;
            if (SUMMARY_HINT.matcher(text).find()) {
                hints++;
            }
            if (compact.length() <= SUMMARY_BODY_THRESHOLD) {
                hints++;
            }
            if (text.contains(".pdf") || text.contains("附件")) {
                hints++;
            }
            int articleCount = countMatches(ARTICLE_NO_PATTERN, text);

            int siblingReadableMax = siblings.stream()
                    .filter(p -> !p.equals(contentPath))
                    .filter(p -> !isLikelyReferenceFileName(p))
                    .mapToInt(this::estimateReadableLengthSafe)
                    .max()
                    .orElse(0);

            boolean weakMainBody = compact.length() <= SUMMARY_BODY_THRESHOLD && articleCount <= 1;
            boolean siblingDominates = siblingReadableMax > 0
                    && siblingReadableMax >= Math.max(1200, compact.length() * 1.6);
            return (hints >= 2 && weakMainBody) || (weakMainBody && siblingDominates);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path choosePreferredPrimarySibling(List<Path> siblings, Path contentPage) {
        return siblings.stream()
                .filter(p -> !p.equals(contentPage))
                .filter(p -> !isLikelyReferenceFileName(p))
                .max(Comparator.comparingInt(this::primaryCandidateScore))
                .orElse(null);
    }

    private int primaryCandidateScore(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.endsWith(".pdf")) {
            score += 120;
        } else if (name.endsWith(".doc") || name.endsWith(".docx")) {
            score += 110;
        } else if (name.endsWith(".md") || name.endsWith(".txt")) {
            score += 90;
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            score += 75;
        } else if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
            score += 60;
        }
        if (LEGAL_FILE_HINT.matcher(name).find()) {
            score += 35;
        }
        if (REFERENCE_FILE_HINT.matcher(name).find()) {
            score -= 35;
        }
        try {
            long size = Files.size(p);
            score += (int) Math.min(30, Math.log10(Math.max(1, size)) * 6);
        } catch (Exception ignored) {
            // Ignore size failures.
        }
        return score;
    }

    private boolean isLikelyReferenceFileName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        // If a filename contains both reference hints and legal-document hints (e.g. "...通知...流程..."),
        // keep it as a legal candidate to avoid dropping substantive notice PDFs.
        if (REFERENCE_FILE_HINT.matcher(name).find() && LEGAL_FILE_HINT.matcher(name).find()) {
            return false;
        }
        return REFERENCE_FILE_HINT.matcher(name).find();
    }

    private void appendChunks(List<DocumentChunk> buffer, LegalDocument doc) {
        String lawTitle = normalizeLawTitle(doc.title());
        String docCategory = classifyDocCategory(lawTitle);
        boolean attachmentDoc = isAttachmentDocument(doc);
        List<String> chunks = textChunker.split(
                doc.text(),
                appProperties.retrieval().chunkSize(),
                appProperties.retrieval().chunkOverlap()
        );
        for (int i = 0; i < chunks.size(); i++) {
            String rawChunk = chunks.get(i);
            if (!attachmentDoc && !isSubstantiveChunk(rawChunk)) {
                continue;
            }
            String articleNo = extractArticleNo(rawChunk);
            String clauseNo = extractClauseNo(rawChunk);
            String enrichedChunk = enrichChunkWithMetadata(rawChunk, lawTitle, articleNo, clauseNo, docCategory);
            buffer.add(new DocumentChunk(
                    doc.id() + "#" + i,
                    doc.id(),
                    doc.title(),
                    doc.regionLevel(),
                    doc.sourcePath(),
                    enrichedChunk,
                    lawTitle,
                    articleNo,
                    docCategory
            ));
        }
    }

    private boolean isAttachmentDocument(LegalDocument doc) {
        if (doc == null) {
            return false;
        }
        String title = doc.title() == null ? "" : doc.title();
        String sourcePath = doc.sourcePath() == null ? "" : doc.sourcePath();
        String text = doc.text() == null ? "" : doc.text();
        return ATTACHMENT_TITLE_SUFFIX.matcher(title).find()
                || sourcePath.contains("/附件/")
                || sourcePath.contains("\\附件\\")
                || text.contains("[文档类型] 附件");
    }

    private boolean isSubstantiveChunk(String rawChunk) {
        if (rawChunk == null || rawChunk.isBlank()) {
            return false;
        }
        // Keep metadata-only primary chunks when OCR/extraction failed, so key files
        // are still searchable by title/doc-no and can be tracked for补录.
        if (isMetadataOnlyPrimaryFallbackChunk(rawChunk)) {
            return true;
        }
        String body = CHUNK_META_LINE_PATTERN.matcher(rawChunk).replaceAll("");
        String compact = body.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return false;
        }
        if (compact.length() >= MIN_SUBSTANTIVE_CHUNK_CHARS) {
            return true;
        }
        // Keep short but explicit legal article chunks.
        return ARTICLE_NO_PATTERN.matcher(body).find();
    }

    private boolean isMetadataOnlyPrimaryFallbackChunk(String rawChunk) {
        if (rawChunk == null || rawChunk.isBlank()) {
            return false;
        }
        if (!rawChunk.contains("[文档类型] 主文")) {
            return false;
        }
        boolean scanned = rawChunk.contains("[疑似扫描] true");
        boolean extractFailed = rawChunk.contains("[提示] 主文抽取失败");
        boolean emptyReadable = rawChunk.contains("[提示] 主文疑似扫描稿或可读文本为空");
        return scanned || extractFailed || emptyReadable;
    }

    private String normalizeLawTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未知法规";
        }
        return ATTACHMENT_TITLE_SUFFIX.matcher(title).replaceAll("").trim();
    }

    private String classifyDocCategory(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.contains("中华人民共和国") && t.endsWith("法")) {
            return "LAW";
        }
        if (t.contains("条例")) {
            return "ADMIN_REGULATION";
        }
        if (t.contains("办法") || t.contains("规定") || t.contains("规则") || t.contains("细则")) {
            return "MINISTERIAL_RULE";
        }
        if (t.contains("通知") || t.contains("意见") || t.contains("决定") || t.contains("指引")) {
            return "NORMATIVE_DOC";
        }
        return "OTHER";
    }

    private String extractArticleNo(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return "";
        }
        Matcher matcher = ARTICLE_NO_PATTERN.matcher(chunkText);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    private String extractClauseNo(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return "";
        }
        Matcher clauseMatcher = CLAUSE_NO_PATTERN.matcher(chunkText);
        if (clauseMatcher.find()) {
            return clauseMatcher.group();
        }
        Matcher itemMatcher = CLAUSE_ITEM_PATTERN.matcher(chunkText);
        if (itemMatcher.find()) {
            return itemMatcher.group();
        }
        return "";
    }

    private String enrichChunkWithMetadata(String chunkText,
                                           String lawTitle,
                                           String articleNo,
                                           String clauseNo,
                                           String docCategory) {
        StringBuilder sb = new StringBuilder();
        sb.append("[法规标题] ").append(lawTitle).append("\n")
                .append("[法规类别] ").append(docCategory).append("\n");
        if (articleNo != null && !articleNo.isBlank()) {
            sb.append("[条号] ").append(articleNo).append("\n");
        }
        if (clauseNo != null && !clauseNo.isBlank()) {
            sb.append("[款项] ").append(clauseNo).append("\n");
        }
        sb.append(chunkText == null ? "" : chunkText.trim());
        return sb.toString().trim();
    }

    private LegalDocument parsePrimaryDocument(Path path, String primaryReason) throws IOException {
        TextExtractor.ExtractionResult extracted;
        boolean extractionFailed = false;
        try {
            extracted = textExtractor.extractWithMeta(path);
        } catch (Exception ex) {
            extracted = new TextExtractor.ExtractionResult("", true, "error");
            extractionFailed = true;
        }
        String primaryText = extracted.safeText().trim();
        boolean summaryLike = isLikelySummaryText(primaryText, path);
        boolean garbled = looksLikeGarbledText(primaryText);
        SourceMeta rawMeta = readSourceMeta(path);
        String extractedTitle = extractTitle(path, primaryText);
        SourceMeta meta = resolveSourceMeta(path, extractedTitle, primaryText, rawMeta);
        String title = firstNonBlank(meta.title(), extractedTitle);
        if (meta.docNo() != null && !meta.docNo().isBlank() && !title.contains(meta.docNo())) {
            title = title + "（" + meta.docNo() + "）";
        }
        String region = PathUtil.inferRegionLevel(path);
        StringBuilder text = new StringBuilder();
        text.append("[文档类型] 主文\n")
                .append("[主文判定] ").append(primaryReason).append("\n")
                .append("[来源文件] ").append(path).append("\n");
        appendMetaLines(text, meta);
        text.append("[抽取策略] ").append(extracted.strategyLabel()).append("\n")
                .append("[疑似扫描] ").append(extracted.maybeScanned()).append("\n");
        text.append("[疑似乱码] ").append(garbled).append("\n");
        text.append("[疑似摘要页] ").append(summaryLike).append("\n");
        if (extractionFailed) {
            text.append("[提示] 主文抽取失败，已归档文件元信息用于后续补录/OCR。\n");
        } else if (extracted.maybeScanned() || primaryText.isBlank()) {
            text.append("[提示] 主文疑似扫描稿或可读文本为空。\n");
        } else if (garbled) {
            text.append("[提示] 主文文本疑似乱码，检索将自动降权，建议补充高质量来源。\n");
        } else if (summaryLike) {
            text.append("[提示] 当前页面疑似摘要/导航页，正文以同目录附件或兄弟正文文件为准。\n");
        }
        String bodyForIndex = summaryLike ? shrinkSummaryText(primaryText) : primaryText;
        if (!bodyForIndex.isBlank()) {
            text.append("\n").append(bodyForIndex);
        }
        return new LegalDocument(
                UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8)).toString(),
                title,
                region,
                path.toString(),
                text.toString()
        );
    }

    private List<LegalDocument> parseAttachmentDocuments(Path primaryPath,
                                                         String primaryId,
                                                         String primaryTitle,
                                                         String regionLevel) throws IOException {
        List<LegalDocument> docs = new ArrayList<>();
        List<Path> attachments = findAttachments(primaryPath);
        for (Path attachment : attachments) {
            TextExtractor.ExtractionResult parsed;
            boolean extractionFailed = false;
            try {
                parsed = textExtractor.extractWithMeta(attachment, isPdfFile(attachment));
            } catch (Exception ex) {
                parsed = new TextExtractor.ExtractionResult("", true, "error");
                extractionFailed = true;
            }

            SourceMeta rawMeta = readSourceMeta(attachment);
            String extractedTitle = extractTitle(attachment, parsed.safeText());
            SourceMeta meta = resolveSourceMeta(attachment, extractedTitle, parsed.safeText(), rawMeta);
            boolean garbled = looksLikeGarbledText(parsed.safeText());
            String attachmentType = classifyAttachmentType(attachment, parsed.safeText(), garbled);
            StringBuilder text = new StringBuilder();
            text.append("[文档类型] 附件\n")
                    .append("[附件属性] ").append(attachmentType).append("\n")
                    .append("[主文标题] ").append(primaryTitle).append("\n")
                    .append("[挂靠主文ID] ").append(primaryId).append("\n")
                    .append("[来源文件] ").append(attachment).append("\n");
            appendMetaLines(text, meta);
            text.append("[抽取策略] ").append(parsed.strategyLabel()).append("\n")
                    .append("[疑似扫描] ").append(parsed.maybeScanned()).append("\n");
            text.append("[疑似乱码] ").append(garbled).append("\n");
            text.append("[疑似摘要页] false\n");
            if (extractionFailed) {
                text.append("[提示] 附件抽取失败，已归档文件元信息用于后续补录/OCR。\n");
            } else if (parsed.maybeScanned() || !parsed.hasText()) {
                text.append("[提示] 该附件疑似扫描稿，文本提取质量可能有限。\n");
            } else if (garbled) {
                text.append("[提示] 该附件文本疑似乱码，检索将自动降权。\n");
            }
            if (!parsed.hasText()) {
                text.append("[提示] 当前未抽取到可读正文，已归档文件元信息用于后续补录/OCR。\n");
            } else {
                String attachmentBody = garbled
                        ? truncateText(parsed.safeText().trim(), SUMMARY_PREVIEW_MAX_LEN)
                        : parsed.safeText().trim();
                text.append("\n").append(attachmentBody);
            }

            String fileName = attachment.getFileName() == null ? "附件" : attachment.getFileName().toString();
            String namedAttachment = firstNonBlank(meta.title(), fileName);
            String docTitle = primaryTitle + "（附件: " + namedAttachment + "）";
            docs.add(new LegalDocument(
                    UUID.nameUUIDFromBytes(attachment.toString().getBytes(StandardCharsets.UTF_8)).toString(),
                    docTitle,
                    regionLevel,
                    attachment.toString(),
                    text.toString()
            ));
        }
        return docs;
    }

    private boolean isPdfFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String classifyAttachmentType(Path attachment, String text, boolean garbled) {
        String name = attachment.getFileName() == null ? "" : attachment.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".csv")) {
            return "参考型附件";
        }
        if (REFERENCE_ATTACHMENT_NAME.matcher(name).find()) {
            return "参考型附件";
        }
        if (garbled) {
            return "参考型附件";
        }
        if (LEGAL_ATTACHMENT_NAME.matcher(name).find()) {
            return "正文型附件";
        }
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.length() > 1200 || ARTICLE_PATTERN.matcher(compact).find()) {
            return "正文型附件";
        }
        return "参考型附件";
    }

    private List<Path> findAttachments(Path primaryPath) {
        Path parent = primaryPath.getParent();
        if (parent == null || !Files.exists(parent)) {
            return List.of();
        }

        List<Path> result = new ArrayList<>();

        // 1) common "附件/" directory
        Path attachmentDir = parent.resolve("附件");
        if (Files.isDirectory(attachmentDir)) {
            result.addAll(listSupportedFiles(attachmentDir));
        }

        // 2) files in same dir except content pages.
        // Guardrail: for directories that contain multiple standalone primary files,
        // do not cross-link siblings as attachments (prevents document pollution).
        if (!allowSiblingAttachments(parent)) {
            return result.stream().distinct().toList();
        }

        try (Stream<Path> stream = Files.list(parent)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedAttachment)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return !p.equals(primaryPath)
                                && !n.equals("content.md")
                                && !n.equals("content.html")
                                && !n.equals(".ds_store");
                    })
                    .sorted(Comparator.naturalOrder())
                    .forEach(result::add);
        } catch (IOException ignored) {
            // ignore
        }

        return result.stream().distinct().toList();
    }

    private boolean allowSiblingAttachments(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            boolean hasContentPage = files.stream().anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.equals("content.md") || n.equals("content.html");
            });
            if (hasContentPage) {
                return true;
            }
            long standalonePrimaryCount = files.stream().filter(this::isSupportedPrimary).count();
            return standalonePrimaryCount <= 1;
        } catch (IOException ignored) {
            return false;
        }
    }

    private List<Path> listSupportedFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedAttachment)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean isSupportedAttachment(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (isIgnoredIndexLikeFileName(n)) {
            return false;
        }
        return n.endsWith(".pdf")
                || n.endsWith(".doc")
                || n.endsWith(".docx")
                || n.endsWith(".xls")
                || n.endsWith(".xlsx")
                || n.endsWith(".txt")
                || n.endsWith(".md")
                || n.endsWith(".html")
                || n.endsWith(".htm");
    }

    private boolean isSupportedPrimary(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (isIgnoredIndexLikeFileName(n)) {
            return false;
        }
        return n.endsWith(".md")
                || n.endsWith(".txt")
                || n.endsWith(".html")
                || n.endsWith(".htm")
                || n.endsWith(".pdf")
                || n.endsWith(".doc")
                || n.endsWith(".docx")
                || n.endsWith(".xls")
                || n.endsWith(".xlsx")
                || n.endsWith(".csv")
                || n.endsWith(".ppt")
                || n.endsWith(".pptx");
    }

    private boolean isIgnoredIndexLikeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String n = fileName.trim().toLowerCase(Locale.ROOT);
        return n.equals("list.txt")
                || n.equals("list_new.txt")
                || n.equals("list_sorted.txt")
                || n.equals("links.txt")
                || n.equals("url.txt")
                || n.equals("urls.txt");
    }

    private boolean isLikelySummaryText(String text, Path sourcePath) {
        if (text == null || text.isBlank()) {
            return true;
        }
        if (!isContentPage(sourcePath)) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        int hints = 0;
        if (SUMMARY_HINT.matcher(text).find()) {
            hints++;
        }
        if (compact.length() <= SUMMARY_BODY_THRESHOLD) {
            hints++;
        }
        if ((text.contains("附件") || text.contains(".pdf")) && countMatches(ARTICLE_NO_PATTERN, text) <= 1) {
            hints++;
        }
        return hints >= 2;
    }

    private String shrinkSummaryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.replaceAll("(?m)^\\s*(发稿时间|访问量|当前位置|上一篇|下一篇|打印本页|关闭窗口).*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return truncateText(cleaned, SUMMARY_PREVIEW_MAX_LEN);
    }

    private boolean looksLikeGarbledText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() < 120) {
            return false;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B) {
                cjk++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        double cjkRatio = (double) cjk / compact.length();
        double latinRatio = (double) latin / compact.length();
        return cjkRatio < 0.12d && latinRatio > 0.45d;
    }

    private int estimateReadableLengthSafe(Path path) {
        try {
            return textExtractor.extractWithMeta(path).safeText().replaceAll("\\s+", "").length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int countMatches(Pattern pattern, String text) {
        if (pattern == null || text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String truncateText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String extractTitle(Path path, String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String header = trimmed.replaceFirst("^#+\\s*", "");
                if (!isPoorTitleCandidate(header)) {
                    return header;
                }
                continue;
            }
            if (!trimmed.isBlank() && trimmed.length() > 6 && !isPoorTitleCandidate(trimmed)) {
                return trimmed;
            }
        }
        return deriveFallbackTitle(path);
    }

    private String deriveFallbackTitle(Path path) {
        String folder = path.getParent() != null ? path.getParent().getFileName().toString() : "";
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        String fileStem = fileName.replaceFirst("\\.[^.]+$", "");

        String folderCandidate = sanitizeFallbackTitle(folder);
        String stemCandidate = sanitizeFallbackTitle(fileStem);

        boolean stemLooksLegal = LEGAL_FILE_HINT.matcher(stemCandidate).find()
                || DOC_NO_YEAR_SEQ_PATTERN.matcher(compact(stemCandidate)).find()
                || DOC_NO_ORDER_PATTERN.matcher(compact(stemCandidate)).find();

        if (!stemCandidate.isBlank() && !isPoorTitleCandidate(stemCandidate)
                && (stemLooksLegal || isPoorTitleCandidate(folderCandidate) || isGenericFolderName(folderCandidate))) {
            return stemCandidate;
        }
        if (!folderCandidate.isBlank() && !isPoorTitleCandidate(folderCandidate) && !isGenericFolderName(folderCandidate)) {
            return folderCandidate;
        }
        if (!stemCandidate.isBlank() && !isPoorTitleCandidate(stemCandidate)) {
            return stemCandidate;
        }
        return folder.isBlank() ? fileStem : folder;
    }

    private boolean isGenericFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return true;
        }
        String n = folderName.trim().toLowerCase(Locale.ROOT);
        return n.equals("corpus")
                || n.equals("data")
                || n.equals("tmp")
                || n.equals("temp")
                || n.equals("附件")
                || n.equals("attachment");
    }

    private String sanitizeFallbackTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceFirst("^\\d+[_\\-\\. ]*", "")
                .replaceAll("[_]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isPoorTitleCandidate(String title) {
        if (title == null) {
            return true;
        }
        String trimmed = title.replaceAll("\\s+", " ").trim();
        if (trimmed.isBlank()) {
            return true;
        }
        if (trimmed.length() < 4 || trimmed.length() > 140) {
            return true;
        }
        if (PAGE_TITLE_HINT.matcher(trimmed).matches()) {
            return true;
        }
        if (GENERIC_TITLE_HINT.matcher(trimmed).matches()) {
            return true;
        }
        if (LEADING_SERIAL_HINT.matcher(trimmed).matches()
                && trimmed.length() > 18
                && !LEGAL_FILE_HINT.matcher(trimmed).find()) {
            return true;
        }
        int han = 0;
        int letters = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B) {
                han++;
            } else if (Character.isLetterOrDigit(c)) {
                letters++;
            }
        }
        if (han == 0 && letters < 4) {
            return true;
        }
        return false;
    }

    private SourceMeta readSourceMeta(Path sourceFile) {
        Path parent = sourceFile.getParent();
        if (parent == null) {
            return new SourceMeta("", "", "", "", "", "", Map.of());
        }
        Path metaPath = parent.resolve("meta.json");
        if (!Files.isRegularFile(metaPath)) {
            return new SourceMeta("", "", "", "", "", "", Map.of());
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(metaPath, StandardCharsets.UTF_8));
            String title = extractMetaField(node, "title", "name", "doc_title", "标题");
            String docNo = extractMetaField(node, "doc_no", "docNo", "文号", "document_no");
            String issuer = extractMetaField(node, "issuer", "department", "发文机关", "发布机构");
            String publishDate = extractMetaField(node, "publish_date", "发布日期", "date", "发文日期");
            String effectiveDate = extractMetaField(node, "effective_date", "施行日期", "实施日期", "execute_date");
            String sourceUrl = extractMetaField(node, "source_url", "url", "link", "来源链接");
            Map<String, String> extras = extractMetaExtras(node, Set.of(
                    "title", "name", "doc_title", "标题",
                    "doc_no", "docNo", "文号", "document_no",
                    "issuer", "department", "发文机关", "发布机构",
                    "publish_date", "发布日期", "date", "发文日期",
                    "effective_date", "施行日期", "实施日期", "execute_date",
                    "source_url", "url", "link", "来源链接"
            ));
            return new SourceMeta(title, docNo, issuer, publishDate, effectiveDate, sourceUrl, extras);
        } catch (Exception ignored) {
            return new SourceMeta("", "", "", "", "", "", Map.of());
        }
    }

    private SourceMeta resolveSourceMeta(Path sourceFile,
                                         String extractedTitle,
                                         String extractedText,
                                         SourceMeta rawMeta) {
        String sourcePath = sourceFile == null ? "" : sourceFile.toString();
        String merged = compact(sourcePath + " " + defaultText(extractedTitle) + " " + defaultText(extractedText));

        String title = firstNonBlank(rawMeta.title(), extractedTitle, deriveFallbackTitle(sourceFile));
        title = sanitizeFallbackTitle(title);

        String docNo = normalizeDocNo(firstNonBlank(rawMeta.docNo(), extractDocNoFallback(merged)));
        String issuer = firstNonBlank(rawMeta.issuer(), extractIssuerFallback(merged));
        String publishDate = firstNonBlank(normalizeDate(rawMeta.publishDate()), extractPublishDateFallback(merged));
        String effectiveDate = firstNonBlank(normalizeDate(rawMeta.effectiveDate()), extractEffectiveDateFallback(merged));
        String sourceUrl = rawMeta.sourceUrl();
        Map<String, String> extras = rawMeta.extras() == null ? Map.of() : rawMeta.extras();

        return new SourceMeta(title, docNo, issuer, publishDate, effectiveDate, sourceUrl, extras);
    }

    private String extractDocNoFallback(String merged) {
        if (merged == null || merged.isBlank()) {
            return "";
        }
        Matcher m = DOC_NO_YEAR_SEQ_PATTERN.matcher(merged);
        if (m.find()) {
            return m.group(1) + "〔" + m.group(2) + "〕" + m.group(3) + "号";
        }
        Matcher m2 = DOC_NO_ORDER_PATTERN.matcher(merged);
        if (m2.find()) {
            return m2.group(1) + "第" + m2.group(2) + "号";
        }
        return "";
    }

    private String normalizeDocNo(String docNo) {
        if (docNo == null || docNo.isBlank()) {
            return "";
        }
        String merged = compact(docNo);
        Matcher m = DOC_NO_YEAR_SEQ_PATTERN.matcher(merged);
        if (m.find()) {
            return m.group(1) + "〔" + m.group(2) + "〕" + m.group(3) + "号";
        }
        Matcher m2 = DOC_NO_ORDER_PATTERN.matcher(merged);
        if (m2.find()) {
            return m2.group(1) + "第" + m2.group(2) + "号";
        }
        return docNo.trim();
    }

    private String extractIssuerFallback(String merged) {
        if (merged == null || merged.isBlank()) {
            return "";
        }
        List<String> knownIssuers = List.of(
                "国务院办公厅", "国务院", "全国人大常委会",
                "财政部办公厅", "财政部",
                "江苏省财政厅", "扬州市财政局",
                "住房城乡建设部办公厅", "工业和信息化部办公厅",
                "生态环境部办公厅", "农业农村部办公厅",
                "国家乡村振兴局综合司", "中华全国供销合作总社办公厅",
                "国家发展改革委", "发展改革委",
                "扬州市公共资源交易中心"
        );
        for (String issuer : knownIssuers) {
            if (merged.contains(compact(issuer))) {
                return issuer;
            }
        }
        Matcher matcher = ISSUER_FUZZY_PATTERN.matcher(merged);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String extractPublishDateFallback(String merged) {
        if (merged == null || merged.isBlank()) {
            return "";
        }
        List<String> dates = extractAllDates(merged);
        if (!dates.isEmpty()) {
            // Notices usually place签发日期 at tail; choose the latest discovered token in text order.
            return dates.get(dates.size() - 1);
        }
        return "";
    }

    private String extractEffectiveDateFallback(String merged) {
        if (merged == null || merged.isBlank()) {
            return "";
        }
        Matcher m = EFFECTIVE_DATE_HINT_PATTERN.matcher(merged);
        if (m.find()) {
            return normalizeDate(m.group(1));
        }
        return "";
    }

    private List<String> extractAllDates(String merged) {
        List<String> dates = new ArrayList<>();
        Matcher cn = DATE_CN_PATTERN.matcher(merged);
        while (cn.find()) {
            dates.add(normalizeDate(cn.group()));
        }
        Matcher iso = DATE_ISO_PATTERN.matcher(merged);
        while (iso.find()) {
            dates.add(normalizeDate(iso.group()));
        }
        return dates.stream().filter(d -> !d.isBlank()).toList();
    }

    private String normalizeDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return "";
        }
        String normalized = rawDate
                .replaceAll("\\s+", "")
                .replace('／', '/')
                .replace('－', '-')
                .replace('—', '-')
                .replace('.', '-');
        Matcher cn = DATE_CN_PATTERN.matcher(normalized);
        if (cn.find()) {
            int y = Integer.parseInt(cn.group(1));
            int m = Integer.parseInt(cn.group(2));
            int d = Integer.parseInt(cn.group(3));
            return String.format("%04d-%02d-%02d", y, m, d);
        }
        Matcher iso = DATE_ISO_PATTERN.matcher(normalized);
        if (iso.find()) {
            int y = Integer.parseInt(iso.group(1));
            int m = Integer.parseInt(iso.group(2));
            int d = Integer.parseInt(iso.group(3));
            return String.format("%04d-%02d-%02d", y, m, d);
        }
        return rawDate.trim();
    }

    private String extractMetaField(JsonNode node, String... aliases) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String alias : aliases) {
            JsonNode value = node.get(alias);
            if (value != null && value.isValueNode()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Map<String, String> extractMetaExtras(JsonNode node, Set<String> ignoreKeys) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> extras = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(key -> {
            if (extras.size() >= META_EXTRA_MAX_FIELDS) {
                return;
            }
            if (ignoreKeys.contains(key)) {
                return;
            }
            JsonNode value = node.get(key);
            if (value == null || !value.isValueNode()) {
                return;
            }
            String text = value.asText("").replaceAll("\\s+", " ").trim();
            if (text.isBlank()) {
                return;
            }
            if (text.length() > META_EXTRA_MAX_LEN) {
                text = text.substring(0, META_EXTRA_MAX_LEN) + "...";
            }
            extras.put(key, text);
        });
        return extras;
    }

    private void appendMetaLines(StringBuilder text, SourceMeta meta) {
        if (meta.docNo() != null && !meta.docNo().isBlank()) {
            text.append("[文号] ").append(meta.docNo()).append("\n");
        }
        if (meta.issuer() != null && !meta.issuer().isBlank()) {
            text.append("[发文机关] ").append(meta.issuer()).append("\n");
        }
        if (meta.publishDate() != null && !meta.publishDate().isBlank()) {
            text.append("[发布日期] ").append(meta.publishDate()).append("\n");
        }
        if (meta.effectiveDate() != null && !meta.effectiveDate().isBlank()) {
            text.append("[施行日期] ").append(meta.effectiveDate()).append("\n");
        }
        if (meta.sourceUrl() != null && !meta.sourceUrl().isBlank()) {
            text.append("[来源链接] ").append(meta.sourceUrl()).append("\n");
        }
        if (meta.extras() != null && !meta.extras().isEmpty()) {
            String extra = meta.extras().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            if (!extra.isBlank()) {
                text.append("[元数据] ").append(extra).append("\n");
            }
        }
    }

    private String compact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replace("【", "")
                .replace("】", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record PrimaryDoc(Path path, String reason) {
    }

    private record SourceMeta(String title,
                              String docNo,
                              String issuer,
                              String publishDate,
                              String effectiveDate,
                              String sourceUrl,
                              Map<String, String> extras) {
    }
}
