package com.brandonbot.legalassistant.ui.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.service.QueryService;
import com.brandonbot.legalassistant.ui.dto.ProjectDtos;
import com.brandonbot.legalassistant.ui.model.UiConversation;
import com.brandonbot.legalassistant.ui.model.UiMessage;
import com.brandonbot.legalassistant.ui.model.UiProject;
import com.brandonbot.legalassistant.ui.service.UiAuthService;
import com.brandonbot.legalassistant.ui.service.UiProjectService;

@RestController
@RequestMapping("/api/v1/ui")
public class UiProjectController {

    private static final String MASKED_SOURCE_PATH = "已隐藏";

    private final UiAuthService authService;
    private final UiProjectService projectService;
    private final QueryService queryService;
    public UiProjectController(UiAuthService authService,
                               UiProjectService projectService,
                               QueryService queryService) {
        this.authService = authService;
        this.projectService = projectService;
        this.queryService = queryService;
    }

    @GetMapping("/projects")
    public List<ProjectDtos.ProjectSummary> projects(@RequestHeader("X-Auth-Token") String token) {
        String userId = authService.requireUser(token).userId();
        return projectService.listProjects(userId).stream()
                .map(p -> new ProjectDtos.ProjectSummary(p.id, p.name, p.description, p.updatedAt, p.conversations.size()))
                .toList();
    }

    @PostMapping("/projects")
    public ProjectDtos.ProjectSummary createProject(@RequestHeader("X-Auth-Token") String token,
                                                    @Valid @RequestBody ProjectDtos.CreateProjectRequest req) {
        String userId = authService.requireUser(token).userId();
        UiProject p = projectService.createProject(userId, req.name(), req.description());
        return new ProjectDtos.ProjectSummary(p.id, p.name, p.description, p.updatedAt, p.conversations.size());
    }

    @PatchMapping("/projects/{projectId}")
    public ProjectDtos.ProjectSummary updateProject(@RequestHeader("X-Auth-Token") String token,
                                                    @PathVariable String projectId,
                                                    @Valid @RequestBody ProjectDtos.UpdateProjectRequest req) {
        String userId = authService.requireUser(token).userId();
        UiProject p = projectService.updateProject(userId, projectId, req.name(), req.description());
        return new ProjectDtos.ProjectSummary(p.id, p.name, p.description, p.updatedAt, p.conversations.size());
    }

    @DeleteMapping("/projects/{projectId}")
    public void deleteProject(@RequestHeader("X-Auth-Token") String token,
                              @PathVariable String projectId) {
        String userId = authService.requireUser(token).userId();
        projectService.deleteProject(userId, projectId);
    }

    @GetMapping("/projects/{projectId}/conversations")
    public List<ProjectDtos.ConversationSummary> conversations(@RequestHeader("X-Auth-Token") String token,
                                                               @PathVariable String projectId) {
        String userId = authService.requireUser(token).userId();
        return projectService.listConversations(userId, projectId).stream()
                .map(c -> new ProjectDtos.ConversationSummary(c.id, c.title, c.updatedAt, c.messages.size()))
                .toList();
    }

    @PostMapping("/projects/{projectId}/conversations")
    public ProjectDtos.ConversationSummary createConversation(@RequestHeader("X-Auth-Token") String token,
                                                              @PathVariable String projectId,
                                                              @Valid @RequestBody ProjectDtos.CreateConversationRequest req) {
        String userId = authService.requireUser(token).userId();
        UiConversation c = projectService.createConversation(userId, projectId, req.title());
        return new ProjectDtos.ConversationSummary(c.id, c.title, c.updatedAt, c.messages.size());
    }

    @PostMapping("/conversations/{conversationId}/move")
    public ProjectDtos.ConversationSummary moveConversation(@RequestHeader("X-Auth-Token") String token,
                                                            @PathVariable String conversationId,
                                                            @Valid @RequestBody ProjectDtos.MoveConversationRequest req) {
        String userId = authService.requireUser(token).userId();
        UiConversation c = projectService.moveConversation(userId, conversationId, req.targetProjectId());
        return new ProjectDtos.ConversationSummary(c.id, c.title, c.updatedAt, c.messages.size());
    }

    @GetMapping("/projects/{projectId}/conversations/{conversationId}")
    public ProjectDtos.ConversationView getConversation(@RequestHeader("X-Auth-Token") String token,
                                                        @PathVariable String projectId,
                                                        @PathVariable String conversationId) {
        String userId = authService.requireUser(token).userId();
        UiConversation c = projectService.getConversation(userId, projectId, conversationId);
        return toView(c);
    }

    @PostMapping("/projects/{projectId}/conversations/{conversationId}/messages")
    public ProjectDtos.MessageView addMessage(@RequestHeader("X-Auth-Token") String token,
                                              @PathVariable String projectId,
                                              @PathVariable String conversationId,
                                              @Valid @RequestBody ProjectDtos.AddMessageRequest req) {
        String userId = authService.requireUser(token).userId();
        UiMessage m = projectService.addMessage(userId, projectId, conversationId, req.role(), req.content());
        return new ProjectDtos.MessageView(m.id, m.role, m.content, m.createdAt);
    }

    @PostMapping("/projects/{projectId}/conversations/{conversationId}/ask")
    public AskAndSaveResponse ask(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable String projectId,
                                  @PathVariable String conversationId,
                                  @Valid @RequestBody AskRequest req) {
        String userId = authService.requireUser(token).userId();
        projectService.addMessage(userId, projectId, conversationId, "user", req.question());

        QueryService.QueryResult result = queryService.ask(req.question(), req.mode());
        UiMessage assistant = projectService.addMessage(userId, projectId, conversationId, "assistant", result.answer());

        List<EvidenceItem> evidenceItems = result.evidences().stream()
                .map(e -> new EvidenceItem(e.title(), MASKED_SOURCE_PATH, e.score()))
                .toList();
        return new AskAndSaveResponse(new ProjectDtos.MessageView(assistant.id, assistant.role, assistant.content, assistant.createdAt),
                evidenceItems);
    }

    @GetMapping("/history")
    public List<ProjectDtos.HistoryItem> history(@RequestHeader("X-Auth-Token") String token) {
        String userId = authService.requireUser(token).userId();
        return projectService.history(userId).stream()
                .map(h -> new ProjectDtos.HistoryItem(h.projectId(), h.projectName(), h.conversationId(), h.conversationTitle(),
                        h.updatedAt(), h.messageCount()))
                .toList();
    }

    @GetMapping("/conversations/snapshot")
    public List<ProjectDtos.ConversationSnapshotItem> snapshot(@RequestHeader("X-Auth-Token") String token) {
        String userId = authService.requireUser(token).userId();
        return projectService.listProjects(userId).stream()
                .flatMap(p -> p.conversations.stream()
                        .map(c -> new ProjectDtos.ConversationSnapshotItem(
                                p.id,
                                p.name,
                                c.id,
                                c.title,
                                c.updatedAt,
                                c.messages.size(),
                                c.messages.stream()
                                        .map(m -> new ProjectDtos.MessageView(m.id, m.role, m.content, m.createdAt))
                                        .toList()
                        )))
                .toList();
    }

    @GetMapping("/files/fast/download")
    public ResponseEntity<String> downloadFastFile(@RequestParam("ref") String ref) {
        return ResponseEntity.status(410)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Fast download endpoint disabled. Please use official source links.");
    }

    private ProjectDtos.ConversationView toView(UiConversation c) {
        return new ProjectDtos.ConversationView(
                c.id,
                c.title,
                c.updatedAt,
                c.messages.stream().map(m -> new ProjectDtos.MessageView(m.id, m.role, m.content, m.createdAt)).toList()
        );
    }

    public record AskRequest(@NotBlank String question, String mode) {}
    public record EvidenceItem(String title, String sourcePath, double score) {}
    public record AskAndSaveResponse(ProjectDtos.MessageView assistantMessage, List<EvidenceItem> evidences) {}
}
