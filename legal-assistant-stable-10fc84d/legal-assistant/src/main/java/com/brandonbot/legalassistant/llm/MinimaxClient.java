package com.brandonbot.legalassistant.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.brandonbot.legalassistant.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class MinimaxClient implements LlmClient {

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MinimaxClient(WebClient minimaxWebClient, AppProperties appProperties, Duration llmTimeout) {
        this.webClient = minimaxWebClient;
        this.appProperties = appProperties;
        this.timeout = llmTimeout;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String apiKey = normalizedApiKey();
        if (apiKey == null
                || apiKey.isBlank()
                || "replace-me".equalsIgnoreCase(apiKey)) {
            return "MiniMax API Key 未配置，请检查 .env 中的 MINIMAX_API_KEY 并重启服务。";
        }

        try {
            String raw;
            boolean legacy = hasGroupId();
            if (hasGroupId()) {
                raw = callLegacyWithGroupId(systemPrompt, userPrompt);
                if (shouldFallbackFromLegacy(raw)) {
                    // Fallback to OpenAI-compatible endpoint when GroupId path rejects the key.
                    raw = callOpenAiCompatible(systemPrompt, userPrompt);
                    legacy = false;
                }
            } else {
                raw = callOpenAiCompatible(systemPrompt, userPrompt);
            }
            return extractText(raw, legacy);
        } catch (WebClientResponseException.Unauthorized ex) {
            return "MiniMax 鉴权失败（401 Unauthorized）。"
                    + "请检查 MINIMAX_API_KEY / APP_MINIMAX_BASE_URL / APP_MINIMAX_GROUP_ID。"
                    + "响应摘要: " + summarizeResponseBody(ex.getResponseBodyAsString());
        } catch (WebClientResponseException ex) {
            return "MiniMax 调用失败（HTTP " + ex.getStatusCode().value() + "）。请检查 APP_MINIMAX_BASE_URL / APP_MINIMAX_MODEL 配置。";
        } catch (Exception ex) {
            return "MiniMax 调用异常，请查看服务日志。";
        }
    }

    private boolean hasGroupId() {
        String groupId = normalizedGroupId();
        return groupId != null && !groupId.isBlank();
    }

    private String callOpenAiCompatible(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", appProperties.minimax().model(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0
        );

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);
    }

    private String callLegacyWithGroupId(String systemPrompt, String userPrompt) {
        String mergedPrompt = systemPrompt + "\n\n" + userPrompt;
        Map<String, Object> senderTypeBody = Map.of(
                "model", appProperties.minimax().model(),
                "messages", List.of(
                        Map.of("sender_type", "USER", "text", mergedPrompt)
                )
        );

        String first = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/text/chatcompletion_v2")
                        .queryParam("GroupId", normalizedGroupId())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(senderTypeBody)
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);

        // Some accounts/models expect role/content style even on legacy endpoint.
        if (isLegacyInvalidRole(first)) {
            Map<String, Object> roleBody = Map.of(
                    "model", appProperties.minimax().model(),
                    "messages", List.of(
                            Map.of("role", "user", "content", mergedPrompt)
                    )
            );
            return webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/text/chatcompletion_v2")
                            .queryParam("GroupId", normalizedGroupId())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(roleBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(timeout);
        }

        return first;
    }

    private String extractText(String raw, boolean legacyMode) {
        if (raw == null || raw.isBlank()) {
            return "模型无响应";
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (legacyMode) {
                JsonNode base = root.path("base_resp");
                int statusCode = base.path("status_code").asInt(0);
                if (statusCode != 0) {
                    String statusMsg = base.path("status_msg").asText("unknown error");
                    return "MiniMax 调用失败（legacy status " + statusCode + "）： " + statusMsg;
                }
            }
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (message.has("content")) {
                    return message.path("content").asText();
                }
            }
            if (root.has("reply")) {
                return root.path("reply").asText();
            }
            return raw;
        } catch (Exception ex) {
            return raw;
        }
    }

    private boolean isLegacyInvalidRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode base = root.path("base_resp");
            int statusCode = base.path("status_code").asInt(0);
            String statusMsg = base.path("status_msg").asText("");
            return statusCode != 0 && statusMsg.toLowerCase().contains("invalid role");
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isLegacyAuthOrGroupError(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode base = root.path("base_resp");
            int statusCode = base.path("status_code").asInt(0);
            String statusMsg = base.path("status_msg").asText("").toLowerCase();
            return statusCode == 2049
                    || statusMsg.contains("invalid api key")
                    || statusMsg.contains("group")
                    || statusMsg.contains("unauthorized");
        } catch (Exception ex) {
            return false;
        }
    }

    private String normalizedApiKey() {
        String raw = appProperties.minimax().apiKey();
        return normalizeCredential(raw);
    }

    private String normalizedGroupId() {
        String raw = appProperties.minimax().groupId();
        return normalizeCredential(raw);
    }

    private String summarizeResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 220 ? compact.substring(0, 220) + "..." : compact;
    }

    private boolean shouldFallbackFromLegacy(String raw) {
        if (isLegacyAuthOrGroupError(raw)) {
            return true;
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("invalid api key")
                || lower.contains("\"status_code\":2049")
                || lower.contains("groupid")
                || lower.contains("unauthorized");
    }

    private String normalizeCredential(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }
}
