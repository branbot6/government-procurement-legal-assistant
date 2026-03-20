package com.brandonbot.legalassistant.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StartupIngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestRunner.class);
    private static final long MIN_VECTOR_STORE_BYTES = 256L;

    private final IngestService ingestService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StartupIngestRunner(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("skipIngest")) {
            Path vectorStoreFile = resolveVectorStoreFile();
            if (hasUsableVectorStore(vectorStoreFile)) {
                log.info("skipIngest enabled, existing index found, skip startup sync: {}", vectorStoreFile);
                return;
            }
            log.info("skipIngest enabled but index missing/invalid, run startup sync: {}", vectorStoreFile);
        }
        var result = ingestService.fullSync();
        log.info("startup sync done: docs={}, chunks={}, msg={}", result.documents(), result.chunks(), result.message());
    }

    private boolean hasUsableVectorStore(Path file) {
        try {
            if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
                return false;
            }
            long size = Files.size(file);
            if (size < MIN_VECTOR_STORE_BYTES) {
                return false;
            }
            JsonNode root = objectMapper.readTree(Files.readString(file));
            return root != null && root.isArray() && !root.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path resolveVectorStoreFile() {
        String configured = System.getenv("APP_VECTOR_STORE_FILE");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        Path dataRun = Paths.get("/var/data/.run/vector-store.json");
        if (Files.exists(dataRun)) {
            return dataRun;
        }
        return Path.of(System.getProperty("user.dir"), ".run", "vector-store.json");
    }
}
