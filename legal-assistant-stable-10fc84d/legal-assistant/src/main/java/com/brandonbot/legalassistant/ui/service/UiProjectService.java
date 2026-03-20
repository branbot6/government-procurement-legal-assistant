package com.brandonbot.legalassistant.ui.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandonbot.legalassistant.ui.model.UiConversation;
import com.brandonbot.legalassistant.ui.model.UiDataFile;
import com.brandonbot.legalassistant.ui.model.UiMessage;
import com.brandonbot.legalassistant.ui.model.UiProject;

@Service
public class UiProjectService {

    private final UiDataService uiDataService;

    public UiProjectService(UiDataService uiDataService) {
        this.uiDataService = uiDataService;
    }

    public List<UiProject> listProjects(String userId) {
        return uiDataService.snapshot().projects.stream()
                .filter(p -> p.ownerUserId.equals(userId))
                .sorted(Comparator.comparingLong((UiProject p) -> p.updatedAt).reversed())
                .toList();
    }

    public UiProject createProject(String userId, String name, String description) {
        UiDataFile data = uiDataService.snapshot();
        synchronized (uiDataService) {
            UiProject p = new UiProject();
            p.id = UUID.randomUUID().toString();
            p.ownerUserId = userId;
            p.name = name.trim();
            p.description = description == null ? "" : description.trim();
            p.createdAt = Instant.now().toEpochMilli();
            p.updatedAt = p.createdAt;
            data.projects.add(p);
            uiDataService.save();
            return p;
        }
    }

    public UiProject updateProject(String userId, String projectId, String name, String description) {
        synchronized (uiDataService) {
            UiProject p = findOwnedProject(userId, projectId);
            p.name = name.trim();
            p.description = description == null ? "" : description.trim();
            p.updatedAt = Instant.now().toEpochMilli();
            uiDataService.save();
            return p;
        }
    }

    public void deleteProject(String userId, String projectId) {
        synchronized (uiDataService) {
            UiDataFile data = uiDataService.snapshot();
            boolean removed = data.projects.removeIf(p -> p.id.equals(projectId) && p.ownerUserId.equals(userId));
            if (!removed) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
            }
            uiDataService.save();
        }
    }

    public List<UiConversation> listConversations(String userId, String projectId) {
        return findOwnedProject(userId, projectId).conversations.stream()
                .sorted(Comparator.comparingLong((UiConversation c) -> c.updatedAt).reversed())
                .toList();
    }

    public UiConversation createConversation(String userId, String projectId, String title) {
        synchronized (uiDataService) {
            UiProject p = findOwnedProject(userId, projectId);
            UiConversation c = new UiConversation();
            c.id = UUID.randomUUID().toString();
            c.projectId = projectId;
            c.title = title.trim();
            c.createdAt = Instant.now().toEpochMilli();
            c.updatedAt = c.createdAt;
            p.conversations.add(c);
            p.updatedAt = c.updatedAt;
            uiDataService.save();
            return c;
        }
    }

    public UiConversation getConversation(String userId, String projectId, String conversationId) {
        UiProject p = findOwnedProject(userId, projectId);
        return p.conversations.stream()
                .filter(c -> c.id.equals(conversationId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在"));
    }

    public UiConversation moveConversation(String userId, String conversationId, String targetProjectId) {
        synchronized (uiDataService) {
            UiDataFile data = uiDataService.snapshot();
            UiProject source = null;
            UiConversation conversation = null;
            for (UiProject p : data.projects) {
                if (!p.ownerUserId.equals(userId)) {
                    continue;
                }
                for (UiConversation c : p.conversations) {
                    if (c.id.equals(conversationId)) {
                        source = p;
                        conversation = c;
                        break;
                    }
                }
                if (conversation != null) {
                    break;
                }
            }
            if (conversation == null || source == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在");
            }

            UiProject target = findOwnedProject(userId, targetProjectId);
            if (source.id.equals(target.id)) {
                return conversation;
            }

            source.conversations.removeIf(c -> c.id.equals(conversationId));
            conversation.projectId = target.id;
            long now = Instant.now().toEpochMilli();
            conversation.updatedAt = now;
            source.updatedAt = now;
            target.updatedAt = now;
            target.conversations.add(conversation);
            uiDataService.save();
            return conversation;
        }
    }

    public UiMessage addMessage(String userId, String projectId, String conversationId, String role, String content) {
        synchronized (uiDataService) {
            UiProject p = findOwnedProject(userId, projectId);
            UiConversation c = p.conversations.stream()
                    .filter(x -> x.id.equals(conversationId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在"));
            UiMessage m = new UiMessage();
            m.id = UUID.randomUUID().toString();
            m.conversationId = c.id;
            m.role = role.trim();
            m.content = content.trim();
            m.createdAt = Instant.now().toEpochMilli();
            c.messages.add(m);
            c.updatedAt = m.createdAt;
            p.updatedAt = m.createdAt;
            uiDataService.save();
            return m;
        }
    }

    public List<HistoryRow> history(String userId) {
        return listProjects(userId).stream()
                .flatMap(p -> p.conversations.stream().map(c -> new HistoryRow(
                        p.id,
                        p.name,
                        c.id,
                        c.title,
                        c.updatedAt,
                        c.messages.size()
                )))
                .sorted(Comparator.comparingLong(HistoryRow::updatedAt).reversed())
                .toList();
    }

    public UiProject requireProject(String userId, String projectId) {
        return findOwnedProject(userId, projectId);
    }

    private UiProject findOwnedProject(String userId, String projectId) {
        return uiDataService.snapshot().projects.stream()
                .filter(p -> p.id.equals(projectId) && p.ownerUserId.equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
    }

    public record HistoryRow(String projectId, String projectName, String conversationId, String conversationTitle,
                             long updatedAt, int messageCount) {
    }
}
