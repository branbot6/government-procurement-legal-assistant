package com.brandonbot.legalassistant.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
