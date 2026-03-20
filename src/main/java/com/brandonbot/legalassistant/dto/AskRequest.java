package com.brandonbot.legalassistant.dto;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(
        @NotBlank String question,
        String mode
) {
}
