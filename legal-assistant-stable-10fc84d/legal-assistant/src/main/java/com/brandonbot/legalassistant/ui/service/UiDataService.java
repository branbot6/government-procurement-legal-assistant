package com.brandonbot.legalassistant.ui.service;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import com.brandonbot.legalassistant.ui.model.UiDataFile;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class UiDataService {

    private static final String STORE_ID = "ui-data";

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Path legacyJsonFile = Path.of(System.getProperty("user.dir"), ".run", "ui-data.json");
    private UiDataFile data = new UiDataFile();

    public UiDataService(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        ensureTable();
        load();
    }

    public synchronized UiDataFile snapshot() {
        return data;
    }

    public synchronized void save() {
        try {
            String payload = objectMapper.writeValueAsString(data);
            jdbcTemplate.update(
                    "MERGE INTO ui_store KEY(id) VALUES (?, ?)",
                    STORE_ID,
                    payload
            );
        } catch (Exception ignored) {
            // best effort only
        }
    }

    private synchronized void load() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ui_store WHERE id = ?", Integer.class, STORE_ID);
            if (count != null && count > 0) {
                String payload = jdbcTemplate.queryForObject("SELECT payload FROM ui_store WHERE id = ?", String.class, STORE_ID);
                data = objectMapper.readValue(payload, UiDataFile.class);
                if (data == null) {
                    data = new UiDataFile();
                }
                return;
            }

            // One-time migration from legacy JSON file to DB.
            migrateLegacyJsonIfPresent();
            count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ui_store WHERE id = ?", Integer.class, STORE_ID);
            if (count != null && count > 0) {
                String payload = jdbcTemplate.queryForObject("SELECT payload FROM ui_store WHERE id = ?", String.class, STORE_ID);
                data = objectMapper.readValue(payload, UiDataFile.class);
                if (data == null) {
                    data = new UiDataFile();
                }
                return;
            }

            data = new UiDataFile();
        } catch (Exception ignored) {
            data = new UiDataFile();
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ui_store (" +
                        "id VARCHAR(128) PRIMARY KEY, " +
                        "payload CLOB NOT NULL" +
                        ")"
        );
    }

    private void migrateLegacyJsonIfPresent() {
        try {
            if (!Files.exists(legacyJsonFile)) {
                return;
            }
            String json = Files.readString(legacyJsonFile);
            UiDataFile legacy = objectMapper.readValue(json, UiDataFile.class);
            if (legacy == null) {
                return;
            }
            this.data = legacy;
            save();
            Path backup = legacyJsonFile.resolveSibling("ui-data.json.migrated");
            Files.move(legacyJsonFile, backup);
        } catch (Exception ignored) {
            // Keep service usable even if migration fails.
        }
    }
}
