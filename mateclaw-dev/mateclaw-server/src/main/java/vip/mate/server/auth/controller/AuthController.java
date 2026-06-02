package vip.mate.server.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.domain.auth.model.LoginRequest;
import vip.mate.domain.auth.model.LoginResponse;
import vip.mate.domain.auth.model.UserEntity;
import vip.mate.domain.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.common.exception.MateClawException;
import vip.mate.domain.workspace.core.annotation.RequireGlobalAdmin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证接口
 *
 * @author MateClaw Team
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @Operation(summary = "获取用户列表")
    @GetMapping("/users")
    @RequireGlobalAdmin
    public R<List<UserEntity>> listUsers() {
        return R.ok(authService.listUsers());
    }

    @Operation(summary = "创建用户")
    @PostMapping("/users")
    @RequireGlobalAdmin
    public R<UserEntity> createUser(@RequestBody UserEntity user) {
        return R.ok(authService.createUser(user));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public R<Map<String, Object>> me(Authentication auth) {
        UserEntity user = authService.findByUsername(auth.getName());
        Map<String, Object> result = new HashMap<>();
        result.put("nickname", user.getNickname());
        result.put("role", user.getRole());
        return R.ok(result);
    }

    @Operation(summary = "更新当前用户信息")
    @PutMapping("/me")
    public R<Void> updateMe(@RequestBody Map<String, Object> body, Authentication auth) {
        UserEntity user = authService.findByUsername(auth.getName());
        if (body.containsKey("nickname")) {
            user.setNickname(body.get("nickname").toString());
        }
        if (body.containsKey("email")) {
            user.setEmail(body.get("email").toString());
        }
        authService.updateProfile(user);
        return R.ok();
    }

    @Operation(summary = "修改密码")
    @PutMapping("/users/{id}/password")
    public R<Void> changePassword(
            @PathVariable Long id,
            @RequestParam String oldPassword,
            @RequestParam String newPassword,
            Authentication auth) {
        // Resolve user from the JWT principal — the {id} path segment is
        // informational. A user may only change their own password.
        UserEntity me = authService.findByUsername(auth.getName());
        if (me == null) {
            throw new MateClawException("err.auth.user_not_found", "用户不存在");
        }
        authService.changePassword(me.getId(), oldPassword, newPassword);
        return R.ok();
    }
}
