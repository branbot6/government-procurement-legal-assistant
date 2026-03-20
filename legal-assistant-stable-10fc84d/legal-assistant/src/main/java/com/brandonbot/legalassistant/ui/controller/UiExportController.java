package com.brandonbot.legalassistant.ui.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.ui.service.UiAuthService;
import com.brandonbot.legalassistant.ui.service.UiExportService;

@RestController
@RequestMapping("/api/v1/ui/export")
public class UiExportController {

    private final UiAuthService authService;
    private final UiExportService exportService;

    public UiExportController(UiAuthService authService, UiExportService exportService) {
        this.authService = authService;
        this.exportService = exportService;
    }

    @GetMapping("/projects/{projectId}")
    public ResponseEntity<byte[]> exportProject(@RequestHeader("X-Auth-Token") String token,
                                                @PathVariable String projectId,
                                                @RequestParam(defaultValue = "pdf") String format) {
        String userId = authService.requireUser(token).userId();
        byte[] content = exportService.exportProject(userId, projectId, format);
        return asDownload(content, "project-" + projectId, format);
    }

    @GetMapping("/projects/{projectId}/conversations/{conversationId}")
    public ResponseEntity<byte[]> exportConversation(@RequestHeader("X-Auth-Token") String token,
                                                     @PathVariable String projectId,
                                                     @PathVariable String conversationId,
                                                     @RequestParam(defaultValue = "pdf") String format) {
        String userId = authService.requireUser(token).userId();
        byte[] content = exportService.exportConversation(userId, projectId, conversationId, format);
        return asDownload(content, "conversation-" + conversationId, format);
    }

    private ResponseEntity<byte[]> asDownload(byte[] bytes, String baseName, String format) {
        String ext = "docx".equalsIgnoreCase(format) || "doc".equalsIgnoreCase(format) ? "docx" : "pdf";
        MediaType mediaType = "pdf".equals(ext)
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(baseName + "." + ext).build().toString())
                .contentType(mediaType)
                .body(bytes);
    }
}
