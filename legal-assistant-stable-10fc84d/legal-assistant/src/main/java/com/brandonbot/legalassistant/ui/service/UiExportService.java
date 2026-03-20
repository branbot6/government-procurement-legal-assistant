package com.brandonbot.legalassistant.ui.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandonbot.legalassistant.ui.model.UiConversation;
import com.brandonbot.legalassistant.ui.model.UiMessage;
import com.brandonbot.legalassistant.ui.model.UiProject;

@Service
public class UiExportService {

    private final UiProjectService uiProjectService;

    public UiExportService(UiProjectService uiProjectService) {
        this.uiProjectService = uiProjectService;
    }

    public byte[] exportConversation(String userId, String projectId, String conversationId, String format) {
        UiProject project = uiProjectService.requireProject(userId, projectId);
        UiConversation conv = uiProjectService.getConversation(userId, projectId, conversationId);
        List<String> lines = renderConversation(project, conv);
        return exportLines(lines, format);
    }

    public byte[] exportProject(String userId, String projectId, String format) {
        UiProject project = uiProjectService.requireProject(userId, projectId);
        List<String> lines = new ArrayList<>();
        lines.add("Project: " + project.name);
        lines.add("Description: " + project.description);
        lines.add("Conversations: " + project.conversations.size());
        lines.add("---");
        for (UiConversation c : project.conversations) {
            lines.addAll(renderConversation(project, c));
            lines.add("\n");
        }
        return exportLines(lines, format);
    }

    private List<String> renderConversation(UiProject project, UiConversation conv) {
        List<String> lines = new ArrayList<>();
        lines.add("Project: " + project.name);
        lines.add("Conversation: " + conv.title);
        lines.add("Messages: " + conv.messages.size());
        lines.add("---");
        for (UiMessage m : conv.messages) {
            lines.add("[" + m.role + "] " + m.content);
        }
        return lines;
    }

    private byte[] exportLines(List<String> lines, String format) {
        String f = format == null ? "pdf" : format.trim().toLowerCase();
        return switch (f) {
            case "pdf" -> toPdf(lines);
            case "doc", "docx" -> toDocx(lines);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 pdf 或 docx");
        };
    }

    private byte[] toDocx(List<String> lines) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : lines) {
                XWPFParagraph p = doc.createParagraph();
                p.createRun().setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCX 导出失败");
        }
    }

    private byte[] toPdf(List<String> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float margin = 40;
            float y = page.getMediaBox().getHeight() - margin;
            float leading = 14;

            PDFont font = loadPdfFont(doc);
            boolean unicodeFont = font instanceof PDType0Font;
            cs.setFont(font, 11);
            cs.beginText();
            cs.newLineAtOffset(margin, y);

            for (String line : lines) {
                for (String wrapped : wrap(line, 95)) {
                    if (y < margin) {
                        cs.endText();
                        cs.close();
                        page = new PDPage(PDRectangle.LETTER);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        cs.setFont(font, 11);
                        y = page.getMediaBox().getHeight() - margin;
                        cs.beginText();
                        cs.newLineAtOffset(margin, y);
                    }
                    cs.showText(unicodeFont ? wrapped : safeAscii(wrapped));
                    cs.newLineAtOffset(0, -leading);
                    y -= leading;
                }
            }
            cs.endText();
            cs.close();

            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF 导出失败");
        }
    }

    private List<String> wrap(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            out.add("");
            return out;
        }
        String t = text;
        while (t.length() > maxLen) {
            out.add(t.substring(0, maxLen));
            t = t.substring(maxLen);
        }
        out.add(t);
        return out;
    }

    private String safeAscii(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private PDFont loadPdfFont(PDDocument doc) {
        List<Path> candidates = List.of(
                Path.of("/System/Library/Fonts/PingFang.ttc"),
                Path.of("/System/Library/Fonts/STHeiti Light.ttc"),
                Path.of("/System/Library/Fonts/Supplemental/Songti.ttc"),
                Path.of("/Library/Fonts/Arial Unicode.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf")
        );
        for (Path path : candidates) {
            try {
                if (Files.exists(path)) {
                    return PDType0Font.load(doc, Files.newInputStream(path), true);
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }
}
