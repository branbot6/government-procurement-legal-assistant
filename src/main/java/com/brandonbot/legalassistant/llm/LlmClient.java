package com.brandonbot.legalassistant.llm;

public interface LlmClient {

    String chat(String systemPrompt, String userPrompt);
}
