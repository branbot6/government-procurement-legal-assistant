package com.brandonbot.legalassistant.dto;

public record IngestResponse(
        int documents,
        int chunks,
        String message
) {
}
