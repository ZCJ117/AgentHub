package vip.mate.server.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import vip.mate.domain.auth.model.UserEntity;
import vip.mate.domain.auth.pat.PersonalAccessTokenEntity;
import vip.mate.domain.auth.pat.PersonalAccessTokenService;
import vip.mate.domain.auth.service.AuthService;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBridgeHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return true;
        }
        HttpServletRequest httpReq = servletRequest.getServletRequest();

        String token = httpReq.getParameter("token");
        if (!StringUtils.hasText(token)) {
            log.debug("[Bridge WS] No token in query — anonymous connection");
            return true;
        }

        try {
            if (token.startsWith(PersonalAccessTokenService.PAT_PREFIX)) {
                Optional<PersonalAccessTokenEntity> maybe = patService.findActiveByPlaintext(token);
                if (maybe.isPresent()) {
                    PersonalAccessTokenEntity pat = maybe.get();
                    UserEntity user = authService.findById(pat.getUserId());
                    if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
                        attributes.put("userId", user.getId());
                        attributes.put("workspaceId", 1L); // default workspace
                        log.info("[Bridge WS] Authenticated via PAT: userId={}", user.getId());
                    }
                }
            } else {
                Claims claims = authService.parseClaims(token);
                if (claims != null) {
                    String username = claims.getSubject();
                    UserEntity user = authService.findByUsername(username);
                    if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
                        attributes.put("userId", user.getId());
                        attributes.put("workspaceId", 1L);
                        attributes.put("username", username);
                        log.info("[Bridge WS] Authenticated via JWT: userId={}", user.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Bridge WS] Token parse failed during handshake: {}", e.getMessage());
        }

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
