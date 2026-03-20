package com.brandonbot.legalassistant.ui.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

public class InviteDtos {
    public record ImportInvitesRequest(@NotEmpty List<String> codes) {}
    public record ImportInvitesResponse(int imported, int skipped, int totalActive) {}

    public record CreateRandomInvitesRequest(
            @Min(1) @Max(200) int count,
            @Min(4) @Max(32) int length
    ) {}

    public record InviteItem(
            String code,
            boolean active,
            String usedBy,
            Long usedAt,
            long createdAt
    ) {}

    public record InviteListResponse(List<InviteItem> items, int total, int active, int used) {}
}
