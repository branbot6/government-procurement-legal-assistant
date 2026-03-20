package com.brandonbot.legalassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Corpus corpus,
        Retrieval retrieval,
        Minimax minimax,
        OpenSearch openSearch,
        FastMode fastMode,
        Embedding embedding
) {
    public record Corpus(String rootPath) {
    }

    public record Retrieval(int topK, int chunkSize, int chunkOverlap) {
    }

    public record Minimax(String baseUrl, String apiKey, String model, String groupId, int timeoutSeconds) {
    }

    public record OpenSearch(String url, String indexName, String username, String password) {
    }

    public record FastMode(boolean enabled,
                           String rootPath,
                           int retrievalTopK,
                           int rerankTopK,
                           double singleEvidenceLeadMin) {
    }

    public record Embedding(boolean enabled,
                            String baseUrl,
                            String endpoint,
                            String model,
                            int timeoutSeconds,
                            int batchSize) {
    }
}
