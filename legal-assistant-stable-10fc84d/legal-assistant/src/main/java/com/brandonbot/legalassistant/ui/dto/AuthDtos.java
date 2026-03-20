package com.brandonbot.legalassistant.ui.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthDtos {
    public record RegisterRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String passwordHint,
            String inviteCode
    ) {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record AuthResponse(String token, String username, String passwordHint, boolean admin) {}
    public record MeResponse(String username, String passwordHint, boolean admin) {}
    public record AuthConfigResponse(boolean inviteEnabled) {}

    public record HintResponse(String username, String passwordHint) {}
}
