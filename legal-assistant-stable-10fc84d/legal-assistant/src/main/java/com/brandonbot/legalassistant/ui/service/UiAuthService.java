package com.brandonbot.legalassistant.ui.service;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandonbot.legalassistant.ui.model.UiDataFile;
import com.brandonbot.legalassistant.ui.model.UiUser;
import com.brandonbot.legalassistant.ui.util.SecurityUtil;
import com.brandonbot.legalassistant.ui.service.UiInviteCodeService.InviteValidation;

@Service
public class UiAuthService {

    private final UiDataService uiDataService;
    private final UiInviteCodeService inviteCodeService;
    private final String inviteCode;
    private final boolean inviteEnabled;
    private final boolean bootstrapUserEnabled;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final String bootstrapPasswordHint;
    private final long sessionTtlMillis;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public UiAuthService(UiDataService uiDataService,
                         UiInviteCodeService inviteCodeService,
                         @Value("${app.ui.invite-code:LEGAL-2026}") String inviteCode,
                         @Value("${app.ui.invite-enabled:true}") String inviteEnabledRaw,
                         @Value("${app.ui.bootstrap-user-enabled:false}") String bootstrapUserEnabledRaw,
                         @Value("${app.ui.bootstrap-username:}") String bootstrapUsername,
                         @Value("${app.ui.bootstrap-password:}") String bootstrapPassword,
                         @Value("${app.ui.bootstrap-password-hint:}") String bootstrapPasswordHint,
                         @Value("${app.ui.session-ttl-minutes:60}") String sessionTtlMinutesRaw) {
        this.uiDataService = uiDataService;
        this.inviteCodeService = inviteCodeService;
        this.inviteCode = inviteCode;
        this.inviteEnabled = parseBoolean(inviteEnabledRaw);
        this.bootstrapUserEnabled = parseBoolean(bootstrapUserEnabledRaw);
        this.bootstrapUsername = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        this.bootstrapPasswordHint = bootstrapPasswordHint == null ? "" : bootstrapPasswordHint.trim();
        this.sessionTtlMillis = parseSessionTtlMillis(sessionTtlMinutesRaw);
    }

    @PostConstruct
    public void ensureBootstrapUser() {
        if (!bootstrapUserEnabled) {
            return;
        }
        String username = bootstrapUsername.trim();
        String password = bootstrapPassword;
        String passwordHint = bootstrapPasswordHint.trim();
        if (username.isBlank() || password.isBlank()) {
            return;
        }

        UiDataFile data = uiDataService.snapshot();
        synchronized (uiDataService) {
            UiUser user = data.users.stream()
                    .filter(x -> x.username.equalsIgnoreCase(username))
                    .findFirst()
                    .orElse(null);
            if (user == null) {
                user = new UiUser();
                user.id = UUID.randomUUID().toString();
                user.username = username;
                user.passwordHash = SecurityUtil.hashPassword(password);
                user.passwordHint = passwordHint;
                user.admin = data.users.isEmpty();
                user.createdAt = Instant.now().toEpochMilli();
                data.users.add(user);
            } else {
                if (user.passwordHint == null || user.passwordHint.isBlank()) {
                    user.passwordHint = passwordHint;
                }
            }
            uiDataService.save();
        }
    }

    public AuthResult register(String username, String password, String passwordHint, String reqInviteCode) {
        InviteValidation inviteValidation = new InviteValidation(false, "");
        if (inviteEnabled) {
            inviteValidation = inviteCodeService.validateForRegister(reqInviteCode, inviteCode);
        }
        String u = normalizeUsername(username);
        UiDataFile data = uiDataService.snapshot();

        synchronized (uiDataService) {
            boolean exists = data.users.stream().anyMatch(x -> x.username.equalsIgnoreCase(u));
            if (exists) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
            }
            UiUser user = new UiUser();
            user.id = UUID.randomUUID().toString();
            user.username = u;
            user.passwordHash = SecurityUtil.hashPassword(password);
            user.passwordHint = passwordHint == null ? "" : passwordHint.trim();
            user.admin = data.users.isEmpty();
            user.createdAt = Instant.now().toEpochMilli();
            data.users.add(user);
            uiDataService.save();
            if (inviteEnabled) {
                inviteCodeService.consumeIfManaged(inviteValidation, user.id);
            }
            return login(u, password);
        }
    }

    public AuthResult login(String username, String password) {
        String u = normalizeUsername(username);
        UiUser user = uiDataService.snapshot().users.stream()
                .filter(x -> x.username.equalsIgnoreCase(u))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误"));

        if (!SecurityUtil.matchesPassword(password, user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        if (SecurityUtil.isLegacySha256(user.passwordHash)) {
            synchronized (uiDataService) {
                user.passwordHash = SecurityUtil.hashPassword(password);
                uiDataService.save();
            }
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        cleanupExpiredSessions(now);
        sessions.put(token, new SessionInfo(user.id, now, now));
        return new AuthResult(token, user.username, user.passwordHint, user.id, user.admin);
    }

    public UserView requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String normalizedToken = token.trim();
        long now = System.currentTimeMillis();
        cleanupExpiredSessions(now);
        SessionInfo session = sessions.get(normalizedToken);
        if (session == null || isExpired(session, now)) {
            sessions.remove(normalizedToken);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        }
        sessions.put(normalizedToken, session.withLastAccessAt(now));
        String userId = session.userId();
        UiUser user = uiDataService.snapshot().users.stream()
                .filter(x -> x.id.equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        return new UserView(user.id, user.username, user.passwordHint, user.admin);
    }

    public UserView requireAdmin(String token) {
        UserView user = requireUser(token);
        if (!user.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限访问管理功能");
        }
        return user;
    }

    public String findHintForRequester(String token, String username) {
        UserView requester = requireUser(token);
        String u = normalizeUsername(username);
        if (!requester.admin() && !requester.username().equalsIgnoreCase(u)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅可查看本人密码提示");
        }
        return uiDataService.snapshot().users.stream()
                .filter(x -> x.username.equalsIgnoreCase(u))
                .map(x -> x.passwordHint == null ? "" : x.passwordHint)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    public boolean isInviteEnabled() {
        return inviteEnabled;
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号不能为空");
        }
        return username.trim();
    }

    private boolean parseBoolean(String raw) {
        if (raw == null) {
            return false;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private long parseSessionTtlMillis(String rawMinutes) {
        long minutes = 60L;
        try {
            if (rawMinutes != null && !rawMinutes.isBlank()) {
                minutes = Long.parseLong(rawMinutes.trim());
            }
        } catch (Exception ignored) {
            minutes = 60L;
        }
        if (minutes < 5) {
            minutes = 5;
        }
        return minutes * 60_000L;
    }

    private void cleanupExpiredSessions(long now) {
        Iterator<Map.Entry<String, SessionInfo>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SessionInfo> entry = it.next();
            if (isExpired(entry.getValue(), now)) {
                it.remove();
            }
        }
    }

    private boolean isExpired(SessionInfo session, long now) {
        if (session == null) {
            return true;
        }
        return now - session.lastAccessAtMs() > sessionTtlMillis;
    }

    public record AuthResult(String token, String username, String passwordHint, String userId, boolean admin) {}
    public record UserView(String userId, String username, String passwordHint, boolean admin) {}
    private record SessionInfo(String userId, long issuedAtMs, long lastAccessAtMs) {
        private SessionInfo withLastAccessAt(long timestampMs) {
            return new SessionInfo(userId, issuedAtMs, timestampMs);
        }
    }
}
