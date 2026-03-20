package com.brandonbot.legalassistant.model;

public record DocumentChunk(
        String chunkId,
        String documentId,
        String title,
        String regionLevel,
        String sourcePath,
        String content,
        String lawTitle,
        String articleNo,
        String docCategory
) {
}
