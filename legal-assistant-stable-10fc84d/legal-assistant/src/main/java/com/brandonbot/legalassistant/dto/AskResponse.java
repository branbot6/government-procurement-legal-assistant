package com.brandonbot.legalassistant.dto;

import java.util.List;

public record AskResponse(
        String answer,
        List<EvidenceDto> evidences
) {
    public record EvidenceDto(
            String title,
            String snippet,
            String sourcePath,
            double score
    ) {
    }
}
