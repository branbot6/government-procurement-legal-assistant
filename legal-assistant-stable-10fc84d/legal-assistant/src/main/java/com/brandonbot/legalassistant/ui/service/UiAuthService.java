package com.brandonbot.legalassistant.ui.service;

import java.time.Instant;
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
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public UiAuthService(UiDataService uiDataService,
                         UiInviteCodeService inviteCodeService,
                         @Value("${app.ui.invite-code:LEGAL-2026}") String inviteCode,
                         @Value("${app.ui.invite-enabled:true}") String inviteEnabledRaw,
                         @Value("${app.ui.bootstrap-user-enabled:true}") String bootstrapUserEnabledRaw,
                         @Value("${app.ui.bootstrap-username:BranYang}") String bootstrapUsername,
                         @Value("${app.ui.bootstrap-password:202810}") String bootstrapPassword,
                         @Value("${app.ui.bootstrap-password-hint:默认密码 202810}") String bootstrapPasswordHint) {
        this.uiDataService = uiDataService;
        this.inviteCodeService = inviteCodeService;
        this.inviteCode = inviteCode;
        this.inviteEnabled = parseBoolean(inviteEnabledRaw);
        this.bootstrapUserEnabled = parseBoolean(bootstrapUserEnabledRaw);
        this.bootstrapUsername = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        this.bootstrapPasswordHint = bootstrapPasswordHint == null ? "" : bootstrapPasswordHint.trim();
    }

    @PostConstruct
    public void ensureBootstrapUser() {
        if (!bootstrapUserEnabled) {
            return;
        }
        String username = bootstrapUsername.isBlank() ? "BranYang" : bootstrapUsername;
        String password = bootstrapPassword.isBlank() ? "202810" : bootstrapPassword;
        String passwordHint = bootstrapPasswordHint.isBlank() ? "默认密码 202810" : bootstrapPasswordHint;

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
                user.passwordHash = SecurityUtil.sha256(password);
                user.passwordHint = passwordHint;
                user.admin = data.users.isEmpty();
                user.createdAt = Instant.now().toEpochMilli();
                data.users.add(user);
            } else {
                // Ensure local default credentials remain usable for direct login.
                user.passwordHash = SecurityUtil.sha256(password);
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
            user.passwordHash = SecurityUtil.sha256(password);
            user.passwordHint = passwordHint.trim();
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

        if (!user.passwordHash.equals(SecurityUtil.sha256(password))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, user.id);
        return new AuthResult(token, user.username, user.passwordHint, user.id, user.admin);
    }

    public UserView requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String userId = sessions.get(token.trim());
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        }
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

    public String findHint(String username) {
        String u = normalizeUsername(username);
        return uiDataService.snapshot().users.stream()
                .filter(x -> x.username.equalsIgnoreCase(u))
                .map(x -> x.passwordHint)
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

    public record AuthResult(String token, String username, String passwordHint, String userId, boolean admin) {}
    public record UserView(String userId, String username, String passwordHint, boolean admin) {}
}
