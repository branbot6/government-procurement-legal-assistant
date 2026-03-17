package com.brandonbot.legalassistant.util;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

@Component
public class TextExtractor {
    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    // Chinese policy PDFs often need higher threshold to detect "fake text layer" scans.
    private static final int SCANNED_PDF_TEXT_THRESHOLD = 260;
    private static final Duration OCR_TIMEOUT = Duration.ofMinutes(8);
    private static final Duration TESSERACT_PAGE_TIMEOUT = Duration.ofSeconds(35);
    private static final int TESSERACT_MAX_PAGES =
            Integer.parseInt(System.getenv().getOrDefault("APP_PDF_RASTER_OCR_MAX_PAGES", "40"));
    private static final boolean PDF_OCR_FALLBACK_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("APP_PDF_OCR_FALLBACK_ENABLED", "true"));
    private static final String OCR_LANG =
            System.getenv().getOrDefault("APP_PDF_OCR_LANG", "chi_sim+eng");
    private static final String OCR_JOBS =
            System.getenv().getOrDefault("APP_PDF_OCR_JOBS", "1");
    private static final Set<String> AVAILABLE_OCR_LANGS = detectOcrLangs();
    private static final String RESOLVED_OCR_LANG = resolveOcrLang(OCR_LANG, AVAILABLE_OCR_LANGS);

    public String extract(Path path) throws IOException {
        return extractWithMeta(path).text();
    }

    public ExtractionResult extractWithMeta(Path path) throws IOException {
        return extractWithMeta(path, false);
    }

    public ExtractionResult extractWithMeta(Path path, boolean forcePdfOcr) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".txt")) {
            return new ExtractionResult(Files.readString(path, StandardCharsets.UTF_8), false, "plain");
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            String html = Files.readString(path, StandardCharsets.UTF_8);
            return new ExtractionResult(Jsoup.parse(html).text(), false, "html");
        }
        if (name.endsWith(".pdf")) {
            if (!isPdfByMagic(path)) {
                return extractByRealTypeForMisnamedPdf(path);
            }
            String text = extractPdf(path);
            String strategy = "pdf-native";
            boolean maybeScanned = compactLen(text) < SCANNED_PDF_TEXT_THRESHOLD;
            if (maybeScanned) {
                // Second pass via Tika with OCR disabled to only read hidden text layer.
                // This avoids slow/noisy default English OCR on Chinese scans.
                String viaTika = extractByTika(path, true);
                if (compactLen(viaTika) > compactLen(text)) {
                    text = viaTika;
                    strategy = "pdf+tika";
                    maybeScanned = compactLen(text) < SCANNED_PDF_TEXT_THRESHOLD;
                }
                if (maybeScanned && PDF_OCR_FALLBACK_ENABLED) {
                    String viaOcr = tryExternalOcr(path);
                    if (compactLen(viaOcr) > compactLen(text)) {
                        text = viaOcr;
                        strategy = "pdf+ocr";
                        maybeScanned = compactLen(text) < SCANNED_PDF_TEXT_THRESHOLD;
                    }
                }
            }
            if (forcePdfOcr) {
                String viaOcr = tryExternalOcr(path);
                if (compactLen(viaOcr) > compactLen(text)) {
                    text = viaOcr;
                    strategy = "pdf+ocr-forced";
                }
                maybeScanned = compactLen(text) < SCANNED_PDF_TEXT_THRESHOLD;
            }
            return new ExtractionResult(text, maybeScanned, strategy);
        }
        if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
            String text = extractExcelAsTable(path);
            if (!text.isBlank()) {
                return new ExtractionResult(text, false, "excel-table");
            }
            return new ExtractionResult(extractByTika(path, false), false, "tika");
        }
        if (name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".ppt") || name.endsWith(".pptx")
                || name.endsWith(".csv")) {
            String text = extractByTika(path, false);
            return new ExtractionResult(text, false, "tika");
        }
        return new ExtractionResult("", false, "unsupported");
    }

    private ExtractionResult extractByRealTypeForMisnamedPdf(Path path) throws IOException {
        String zipKind = detectOfficeZipKind(path);
        if ("xlsx".equals(zipKind)) {
            String text = extractExcelAsTable(path);
            if (!text.isBlank()) {
                return new ExtractionResult(text, false, "excel-table-mime");
            }
            return new ExtractionResult(extractByTika(path, false), false, "tika-mime-xlsx");
        }
        if ("docx".equals(zipKind) || "pptx".equals(zipKind)) {
            return new ExtractionResult(extractByTika(path, false), false, "tika-mime-" + zipKind);
        }
        if (looksLikeHtml(path)) {
            String html = Files.readString(path, StandardCharsets.UTF_8);
            return new ExtractionResult(Jsoup.parse(html).text(), false, "html-mime");
        }
        if (looksLikeOleContainer(path)) {
            String text = extractExcelAsTable(path);
            if (!text.isBlank()) {
                return new ExtractionResult(text, false, "excel-table-mime-ole");
            }
            return new ExtractionResult(extractByTika(path, false), false, "tika-mime-ole");
        }
        return new ExtractionResult(extractByTika(path, false), false, "tika-mime");
    }

    private String extractPdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String extractByTika(Path path, boolean disablePdfOcr) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            if (disablePdfOcr) {
                PDFParserConfig pdfCfg = new PDFParserConfig();
                pdfCfg.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
                context.set(PDFParserConfig.class, pdfCfg);
            } else if (!RESOLVED_OCR_LANG.isBlank()) {
                TesseractOCRConfig ocrCfg = new TesseractOCRConfig();
                ocrCfg.setLanguage(RESOLVED_OCR_LANG);
                context.set(TesseractOCRConfig.class, ocrCfg);
            }
            parser.parse(in, handler, metadata, context);
            return handler.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private String extractExcelAsTable(Path path) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("\n# 表格: ").append(sheet.getSheetName()).append("\n");
                int rowCount = 0;
                for (Row row : sheet) {
                    StringBuilder rowBuilder = new StringBuilder();
                    int lastCell = Math.max(row.getLastCellNum(), 0);
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                        if (c > 0) {
                            rowBuilder.append(" | ");
                        }
                        rowBuilder.append(value);
                    }
                    String line = rowBuilder.toString().trim();
                    if (!line.isBlank()) {
                        sb.append(line).append("\n");
                        rowCount++;
                    }
                }
                if (rowCount == 0) {
                    sb.append("[空表]\n");
                }
            }
        } catch (Exception ex) {
            return "";
        }
        return sb.toString();
    }

    private String tryExternalOcr(Path pdfPath) {
        if (!isCommandAvailable("ocrmypdf")) {
            log.debug("ocrmypdf not found, skip OCR fallback for {}", pdfPath);
            return "";
        }
        if (RESOLVED_OCR_LANG.isBlank()) {
            log.warn("OCR disabled for {} because no requested OCR language is available. requested={}, available={}",
                    pdfPath, OCR_LANG, AVAILABLE_OCR_LANGS);
            return "";
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("legal-ocr-");
            Path outPdf = tempDir.resolve("ocr.pdf");
            Path sidecar = tempDir.resolve("ocr.txt");
            Path ocrLog = tempDir.resolve("ocr.log");

            int firstCode = runOcrmypdf(pdfPath, outPdf, sidecar, ocrLog, false);
            String firstText = readSidecar(sidecar);
            if (compactLen(firstText) > 0) {
                return firstText;
            }
            // Some malformed scans need force OCR mode.
            if (firstCode != 0 && firstCode != 6) {
                int secondCode = runOcrmypdf(pdfPath, outPdf, sidecar, ocrLog, true);
                String secondText = readSidecar(sidecar);
                if (compactLen(secondText) > 0) {
                    return secondText;
                }
                if (secondCode != 0 && secondCode != 6) {
                    log.warn("ocrmypdf failed for {} (lang={}, code={} then {}). log={}",
                            pdfPath, RESOLVED_OCR_LANG, firstCode, secondCode, readLogHead(ocrLog, 280));
                }
            }

            // Final fallback: rasterize pages then OCR via tesseract directly.
            String tesseractText = tryRasterizedTesseract(pdfPath);
            if (compactLen(tesseractText) > 0) {
                return tesseractText;
            }
            return "";
        } catch (Exception ex) {
            log.warn("OCR fallback failed for {}: {}", pdfPath, ex.getMessage());
            return "";
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private int runOcrmypdf(Path pdfPath,
                            Path outPdf,
                            Path sidecar,
                            Path logFile,
                            boolean forceMode) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                "ocrmypdf",
                forceMode ? "--force-ocr" : "--redo-ocr",
                "--jobs", OCR_JOBS,
                "-l", RESOLVED_OCR_LANG,
                "--sidecar", sidecar.toString(),
                pdfPath.toString(),
                outPdf.toString()
        ));
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        boolean finished = process.waitFor(OCR_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -999;
        }
        return process.exitValue();
    }

    private String readSidecar(Path sidecar) {
        try {
            if (Files.exists(sidecar)) {
                return Files.readString(sidecar, StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            log.debug("Read sidecar failed: {}", ex.getMessage());
        }
        return "";
    }

    private String readLogHead(Path logFile, int maxChars) {
        try {
            if (!Files.exists(logFile)) {
                return "";
            }
            String txt = Files.readString(logFile, StandardCharsets.UTF_8);
            if (txt.length() <= maxChars) {
                return txt.replaceAll("\\s+", " ").trim();
            }
            return txt.substring(0, maxChars).replaceAll("\\s+", " ").trim() + "...";
        } catch (Exception ex) {
            return "";
        }
    }

    private String tryRasterizedTesseract(Path pdfPath) {
        if (!isCommandAvailable("pdftoppm") || !isCommandAvailable("tesseract")) {
            return "";
        }
        if (RESOLVED_OCR_LANG.isBlank()) {
            return "";
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("legal-raster-ocr-");
            int pageCount = detectPdfPageCount(pdfPath);
            int maxPages = pageCount <= 0 ? TESSERACT_MAX_PAGES : Math.min(pageCount, TESSERACT_MAX_PAGES);
            if (maxPages <= 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
                Path prefix = tempDir.resolve(String.format(Locale.ROOT, "page-%04d", pageNo));
                Path image = Path.of(prefix.toString() + ".png");

                Process render = new ProcessBuilder(
                        "pdftoppm",
                        "-r", "250",
                        "-f", String.valueOf(pageNo),
                        "-singlefile",
                        "-png",
                        pdfPath.toString(),
                        prefix.toString()
                ).redirectErrorStream(true).start();
                boolean renderDone = render.waitFor(TESSERACT_PAGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (!renderDone || render.exitValue() != 0 || !Files.exists(image)) {
                    continue;
                }

                Process ocr = new ProcessBuilder(
                        "tesseract",
                        image.toString(),
                        "stdout",
                        "-l", RESOLVED_OCR_LANG,
                        "--psm", "6"
                ).redirectErrorStream(true).start();
                boolean done = ocr.waitFor(TESSERACT_PAGE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (!done) {
                    ocr.destroyForcibly();
                    continue;
                }
                if (ocr.exitValue() != 0) {
                    continue;
                }
                String pageText = new String(ocr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!pageText.isBlank()) {
                    sb.append(pageText).append('\n');
                }
                Files.deleteIfExists(image);
            }
            return sb.toString();
        } catch (Exception ex) {
            log.debug("Rasterized tesseract fallback failed for {}: {}", pdfPath, ex.getMessage());
            return "";
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-lc", "command -v " + cmd).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isPdfByMagic(Path path) {
        byte[] header = readHeader(path, 5);
        return header.length >= 5
                && header[0] == '%'
                && header[1] == 'P'
                && header[2] == 'D'
                && header[3] == 'F'
                && header[4] == '-';
    }

    private boolean looksLikeOleContainer(Path path) {
        byte[] h = readHeader(path, 8);
        if (h.length < 8) {
            return false;
        }
        return (h[0] & 0xFF) == 0xD0
                && (h[1] & 0xFF) == 0xCF
                && (h[2] & 0xFF) == 0x11
                && (h[3] & 0xFF) == 0xE0
                && (h[4] & 0xFF) == 0xA1
                && (h[5] & 0xFF) == 0xB1
                && (h[6] & 0xFF) == 0x1A
                && (h[7] & 0xFF) == 0xE1;
    }

    private boolean looksLikeHtml(Path path) {
        byte[] h = readHeader(path, 2048);
        if (h.length == 0) {
            return false;
        }
        String s = new String(h, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return s.contains("<html") || s.contains("<!doctype html") || s.contains("<head") || s.contains("<body");
    }

    private String detectOfficeZipKind(Path path) {
        byte[] h = readHeader(path, 4);
        if (h.length < 4) {
            return "";
        }
        boolean zip = (h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5 || h[2] == 7) && (h[3] == 4 || h[3] == 6 || h[3] == 8));
        if (!zip) {
            return "";
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("word/")) {
                    return "docx";
                }
                if (n.startsWith("xl/")) {
                    return "xlsx";
                }
                if (n.startsWith("ppt/")) {
                    return "pptx";
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private byte[] readHeader(Path path, int maxBytes) {
        if (maxBytes <= 0) {
            return new byte[0];
        }
        byte[] buf = new byte[maxBytes];
        try (InputStream in = Files.newInputStream(path)) {
            int n = in.read(buf);
            if (n <= 0) {
                return new byte[0];
            }
            if (n == maxBytes) {
                return buf;
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    private int detectPdfPageCount(Path pdfPath) {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            return doc.getNumberOfPages();
        } catch (Exception ex) {
            return 0;
        }
    }

    private int compactLen(String text) {
        if (text == null) {
            return 0;
        }
        return text.replaceAll("\\s+", "").trim().length();
    }

    private static Set<String> detectOcrLangs() {
        if (!isCommandAvailableStatic("tesseract")) {
            return Set.of();
        }
        try {
            Process p = new ProcessBuilder("tesseract", "--list-langs").start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                return Set.of();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(out.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .filter(s -> !s.startsWith("List of available languages"))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception ex) {
            return Set.of();
        }
    }

    private static String resolveOcrLang(String configured, Set<String> available) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        if (available == null || available.isEmpty()) {
            return configured;
        }
        List<String> picked = Arrays.stream(configured.split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(available::contains)
                .toList();
        if (picked.isEmpty()) {
            return "";
        }
        return String.join("+", picked);
    }

    private static boolean isCommandAvailableStatic(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-lc", "command -v " + cmd).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    public record ExtractionResult(String text, boolean maybeScanned, String strategy) {
        public String safeText() {
            return text == null ? "" : text;
        }

        public boolean hasText() {
            return !safeText().isBlank();
        }

        public String strategyLabel() {
            return strategy == null ? "unknown" : strategy.toLowerCase(Locale.ROOT);
        }
    }
}
