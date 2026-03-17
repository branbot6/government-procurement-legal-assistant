package com.brandonbot.legalassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupIngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestRunner.class);

    private final IngestService ingestService;

    public StartupIngestRunner(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("skipIngest")) {
            log.info("skipIngest enabled, skip startup sync");
            return;
        }
        var result = ingestService.fullSync();
        log.info("startup sync done: docs={}, chunks={}, msg={}", result.documents(), result.chunks(), result.message());
    }
}
