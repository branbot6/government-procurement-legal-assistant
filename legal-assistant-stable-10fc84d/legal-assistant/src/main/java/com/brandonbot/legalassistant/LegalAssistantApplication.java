package com.brandonbot.legalassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LegalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegalAssistantApplication.class, args);
    }
}
