package com.brandonbot.legalassistant.ui.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.ui.dto.AuthDtos;
import com.brandonbot.legalassistant.ui.service.UiAuthService;

@RestController
@RequestMapping("/api/v1/ui/auth")
public class UiAuthController {

    private final UiAuthService authService;

    public UiAuthController(UiAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthDtos.AuthResponse register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        UiAuthService.AuthResult res = authService.register(req.username(), req.password(), req.passwordHint(), req.inviteCode());
        return new AuthDtos.AuthResponse(res.token(), res.username(), res.passwordHint(), res.admin());
    }

    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        UiAuthService.AuthResult res = authService.login(req.username(), req.password());
        return new AuthDtos.AuthResponse(res.token(), res.username(), res.passwordHint(), res.admin());
    }

    @GetMapping("/me")
    public AuthDtos.MeResponse me(@RequestHeader("X-Auth-Token") String token) {
        UiAuthService.UserView user = authService.requireUser(token);
        return new AuthDtos.MeResponse(user.username(), user.passwordHint(), user.admin());
    }

    @GetMapping("/hint")
    public AuthDtos.HintResponse hint(@RequestHeader("X-Auth-Token") String token,
                                      @RequestParam("username") String username) {
        return new AuthDtos.HintResponse(username, authService.findHintForRequester(token, username));
    }

    @GetMapping("/config")
    public AuthDtos.AuthConfigResponse config() {
        return new AuthDtos.AuthConfigResponse(authService.isInviteEnabled());
    }
}
