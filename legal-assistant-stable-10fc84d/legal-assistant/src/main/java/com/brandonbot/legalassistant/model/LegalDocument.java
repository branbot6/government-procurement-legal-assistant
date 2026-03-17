package com.brandonbot.legalassistant.model;

public record LegalDocument(
        String id,
        String title,
        String regionLevel,
        String sourcePath,
        String text
) {
}
