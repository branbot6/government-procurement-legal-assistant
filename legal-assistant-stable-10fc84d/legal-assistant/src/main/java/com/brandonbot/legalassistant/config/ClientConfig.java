package com.brandonbot.legalassistant.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ClientConfig {

    @Bean
    public WebClient minimaxWebClient(AppProperties appProperties) {
        return WebClient.builder()
                .baseUrl(appProperties.minimax().baseUrl())
                .build();
    }

    @Bean
    public Duration llmTimeout(AppProperties appProperties) {
        return Duration.ofSeconds(appProperties.minimax().timeoutSeconds());
    }
}
