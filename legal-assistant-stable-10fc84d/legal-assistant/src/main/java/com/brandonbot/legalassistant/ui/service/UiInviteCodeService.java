package com.brandonbot.legalassistant.ui.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UiInviteCodeService {

    private final JdbcTemplate jdbcTemplate;

    public UiInviteCodeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ui_invite_codes (" +
                        "code VARCHAR(64) PRIMARY KEY, " +
                        "active BOOLEAN NOT NULL, " +
                        "used_by VARCHAR(64), " +
                        "used_at BIGINT, " +
                        "created_at BIGINT NOT NULL" +
                        ")"
        );
    }

    public InviteValidation validateForRegister(String rawCode, String masterCode) {
        String code = normalizeCode(rawCode);
        if (masterCode != null && !masterCode.isBlank() && masterCode.equals(code)) {
            return new InviteValidation(false, code);
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ui_invite_codes WHERE code = ?",
                Integer.class,
                code
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码错误");
        }

        Boolean active = jdbcTemplate.queryForObject(
                "SELECT active FROM ui_invite_codes WHERE code = ?",
                Boolean.class,
                code
        );
        if (active == null || !active) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码不可用");
        }

        Long usedAt = jdbcTemplate.queryForObject(
                "SELECT used_at FROM ui_invite_codes WHERE code = ?",
                Long.class,
                code
        );
        if (usedAt != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码已使用");
        }

        return new InviteValidation(true, code);
    }

    public void consumeIfManaged(InviteValidation validation, String userId) {
        if (validation == null || !validation.managed()) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE ui_invite_codes SET used_by = ?, used_at = ? WHERE code = ? AND used_at IS NULL",
                userId,
                Instant.now().toEpochMilli(),
                validation.code()
        );
    }

    public ImportResult importCodes(List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            return new ImportResult(0, 0, totalActive());
        }

        Set<String> codes = new LinkedHashSet<>();
        for (String raw : rawCodes) {
            if (raw == null) continue;
            String c = raw.trim();
            if (!c.isBlank()) {
                codes.add(c);
            }
        }

        int imported = 0;
        int skipped = 0;
        long now = Instant.now().toEpochMilli();

        for (String c : codes) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM ui_invite_codes WHERE code = ?",
                    Integer.class,
                    c
            );
            if (count != null && count > 0) {
                skipped++;
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO ui_invite_codes(code, active, used_by, used_at, created_at) VALUES (?, ?, ?, ?, ?)",
                    c,
                    true,
                    null,
                    null,
                    now
            );
            imported++;
        }

        return new ImportResult(imported, skipped, totalActive());
    }

    public ImportResult createRandomCodes(int count, int length) {
        List<String> generated = new ArrayList<>();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        Random random = new Random();
        while (generated.size() < count) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            generated.add(sb.toString());
        }
        return importCodes(generated);
    }

    public void deactivate(String code) {
        String normalized = normalizeCode(code);
        int updated = jdbcTemplate.update(
                "UPDATE ui_invite_codes SET active = FALSE WHERE code = ?",
                normalized
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在");
        }
    }

    public List<InviteRow> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query(
                "SELECT code, active, used_by, used_at, created_at FROM ui_invite_codes ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new InviteRow(
                        rs.getString("code"),
                        rs.getBoolean("active"),
                        rs.getString("used_by"),
                        rs.getObject("used_at") == null ? null : rs.getLong("used_at"),
                        rs.getLong("created_at")
                ),
                safeLimit
        );
    }

    public InviteStats stats() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ui_invite_codes", Integer.class);
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ui_invite_codes WHERE active = TRUE", Integer.class);
        Integer used = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ui_invite_codes WHERE used_at IS NOT NULL", Integer.class);
        return new InviteStats(total == null ? 0 : total, active == null ? 0 : active, used == null ? 0 : used);
    }

    private int totalActive() {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ui_invite_codes WHERE active = TRUE",
                Integer.class
        );
        return active == null ? 0 : active;
    }

    private String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "邀请码错误");
        }
        return raw.trim();
    }

    public record InviteValidation(boolean managed, String code) {}
    public record ImportResult(int imported, int skipped, int totalActive) {}
    public record InviteRow(String code, boolean active, String usedBy, Long usedAt, long createdAt) {}
    public record InviteStats(int total, int active, int used) {}
}
