package com.brandonbot.legalassistant.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.dto.IngestResponse;
import com.brandonbot.legalassistant.service.IngestService;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/sync")
    public IngestResponse sync() {
        return ingestService.fullSync();
    }
}
