package com.brandonbot.legalassistant.model;

public record Evidence(
        String title,
        String snippet,
        String sourcePath,
        double score
) {
}
