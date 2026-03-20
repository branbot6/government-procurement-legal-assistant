package com.brandonbot.legalassistant.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.brandonbot.legalassistant.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class BgeEmbeddingClient implements EmbeddingClient {

    private final AppProperties appProperties;
    private final WebClient webClient;

    public BgeEmbeddingClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        String baseUrl = appProperties.embedding() == null ? "" : appProperties.embedding().baseUrl();
        WebClient.Builder builder = WebClient.builder();
        if (!isBlank(baseUrl)) {
            builder.baseUrl(baseUrl.trim());
        }
        this.webClient = builder.build();
    }

    @Override
    public boolean available() {
        AppProperties.Embedding cfg = appProperties.embedding();
        return cfg != null
                && cfg.enabled()
                && !isBlank(cfg.baseUrl())
                && !isBlank(cfg.endpoint())
                && !isBlank(cfg.model());
    }

    @Override
    public float[] embed(String text) {
        if (isBlank(text)) {
            return null;
        }
        List<float[]> vectors = embedBatch(List.of(text));
        if (vectors.isEmpty()) {
            return null;
        }
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (!available() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = texts.stream()
                .map(t -> t == null ? "" : t.trim())
                .toList();
        if (sanitized.stream().allMatch(String::isBlank)) {
            return List.of();
        }

        AppProperties.Embedding cfg = appProperties.embedding();
        int batchSize = Math.max(1, cfg.batchSize());
        List<float[]> merged = new ArrayList<>(sanitized.size());
        for (int start = 0; start < sanitized.size(); start += batchSize) {
            int end = Math.min(start + batchSize, sanitized.size());
            List<String> batch = sanitized.subList(start, end);
            List<float[]> part = requestBatch(batch, cfg);
            if (part.isEmpty()) {
                for (int i = start; i < end; i++) {
                    merged.add(null);
                }
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                merged.add(i < part.size() ? part.get(i) : null);
            }
        }
        return merged;
    }

    private List<float[]> requestBatch(List<String> batch, AppProperties.Embedding cfg) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        int timeout = Math.max(5, cfg.timeoutSeconds());
        Map<String, Object> req = Map.of(
                "model", cfg.model(),
                "input", batch
        );
        try {
            JsonNode root = webClient.post()
                    .uri(cfg.endpoint())
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(timeout));
            return parseEmbeddings(root, batch.size());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<float[]> parseEmbeddings(JsonNode root, int expectedSize) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>();

        if (root.isArray()) {
            for (JsonNode n : root) {
                float[] v = toVector(n);
                if (v != null) {
                    out.add(v);
                }
            }
            return normalizeSize(out, expectedSize);
        }

        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                JsonNode emb = n.get("embedding");
                float[] v = toVector(emb);
                if (v != null) {
                    out.add(v);
                }
            }
            return normalizeSize(out, expectedSize);
        }

        JsonNode embeddings = root.get("embeddings");
        if (embeddings != null && embeddings.isArray()) {
            for (JsonNode n : embeddings) {
                float[] v = toVector(n);
                if (v != null) {
                    out.add(v);
                }
            }
            return normalizeSize(out, expectedSize);
        }

        JsonNode single = root.get("embedding");
        float[] vector = toVector(single);
        if (vector == null) {
            return List.of();
        }
        out.add(vector);
        return normalizeSize(out, expectedSize);
    }

    private List<float[]> normalizeSize(List<float[]> vectors, int expectedSize) {
        if (vectors.isEmpty() || expectedSize <= 0) {
            return vectors;
        }
        if (vectors.size() >= expectedSize) {
            return vectors.subList(0, expectedSize);
        }
        List<float[]> padded = new ArrayList<>(vectors);
        while (padded.size() < expectedSize) {
            padded.add(null);
        }
        return padded;
    }

    private float[] toVector(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        float[] out = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = (float) node.get(i).asDouble(0d);
        }
        return out;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
