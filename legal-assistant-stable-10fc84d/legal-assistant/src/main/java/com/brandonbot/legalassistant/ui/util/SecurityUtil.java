package com.brandonbot.legalassistant.ui.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class SecurityUtil {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final Pattern LEGACY_SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");

    private SecurityUtil() {}

    public static String hashPassword(String rawPassword) {
        return BCRYPT.encode(rawPassword == null ? "" : rawPassword);
    }

    public static boolean matchesPassword(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (isLegacySha256(storedHash)) {
            return sha256(rawPassword).equals(storedHash);
        }
        return BCRYPT.matches(rawPassword == null ? "" : rawPassword, storedHash);
    }

    public static boolean isLegacySha256(String hash) {
        if (hash == null) {
            return false;
        }
        return LEGACY_SHA256_HEX.matcher(hash.trim().toLowerCase()).matches();
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }
}
