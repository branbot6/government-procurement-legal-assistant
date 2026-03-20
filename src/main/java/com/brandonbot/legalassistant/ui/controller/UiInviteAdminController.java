package com.brandonbot.legalassistant.ui.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.ui.dto.InviteDtos;
import com.brandonbot.legalassistant.ui.service.UiAuthService;
import com.brandonbot.legalassistant.ui.service.UiInviteCodeService;

@RestController
@RequestMapping("/api/v1/ui/admin/invites")
public class UiInviteAdminController {

    private final UiAuthService authService;
    private final UiInviteCodeService inviteCodeService;

    public UiInviteAdminController(UiAuthService authService,
                                   UiInviteCodeService inviteCodeService) {
        this.authService = authService;
        this.inviteCodeService = inviteCodeService;
    }

    @PostMapping("/import")
    public InviteDtos.ImportInvitesResponse importInvites(
            @RequestHeader("X-Auth-Token") String token,
            @Valid @RequestBody InviteDtos.ImportInvitesRequest req) {
        authService.requireAdmin(token);
        UiInviteCodeService.ImportResult result = inviteCodeService.importCodes(req.codes());
        return new InviteDtos.ImportInvitesResponse(result.imported(), result.skipped(), result.totalActive());
    }

    @PostMapping("/create-random")
    public InviteDtos.ImportInvitesResponse createRandom(
            @RequestHeader("X-Auth-Token") String token,
            @Valid @RequestBody InviteDtos.CreateRandomInvitesRequest req) {
        authService.requireAdmin(token);
        UiInviteCodeService.ImportResult result = inviteCodeService.createRandomCodes(req.count(), req.length());
        return new InviteDtos.ImportInvitesResponse(result.imported(), result.skipped(), result.totalActive());
    }

    @PostMapping("/deactivate/{code}")
    public void deactivate(
            @RequestHeader("X-Auth-Token") String token,
            @PathVariable String code) {
        authService.requireAdmin(token);
        inviteCodeService.deactivate(code);
    }

    @GetMapping
    public InviteDtos.InviteListResponse list(
            @RequestHeader("X-Auth-Token") String token,
            @RequestParam(defaultValue = "200") int limit) {
        authService.requireAdmin(token);
        var rows = inviteCodeService.list(limit).stream()
                .map(r -> new InviteDtos.InviteItem(r.code(), r.active(), r.usedBy(), r.usedAt(), r.createdAt()))
                .toList();
        var stats = inviteCodeService.stats();
        return new InviteDtos.InviteListResponse(rows, stats.total(), stats.active(), stats.used());
    }
}
