package com.brandonbot.legalassistant.ui.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public class ProjectDtos {
    public record CreateProjectRequest(@NotBlank String name, String description) {}
    public record UpdateProjectRequest(@NotBlank String name, String description) {}
    public record ProjectSummary(String id, String name, String description, long updatedAt, int conversationCount) {}

    public record CreateConversationRequest(@NotBlank String title) {}
    public record ConversationSummary(String id, String title, long updatedAt, int messageCount) {}
    public record MoveConversationRequest(@NotBlank String targetProjectId) {}

    public record AddMessageRequest(@NotBlank String role, @NotBlank String content) {}
    public record MessageView(String id, String role, String content, long createdAt) {}

    public record ConversationView(String id, String title, long updatedAt, List<MessageView> messages) {}
    public record ConversationSnapshotItem(
            String projectId,
            String projectName,
            String conversationId,
            String title,
            long updatedAt,
            int messageCount,
            List<MessageView> messages
    ) {}

    public record HistoryItem(
            String projectId,
            String projectName,
            String conversationId,
            String conversationTitle,
            long updatedAt,
            int messageCount
    ) {}
}
