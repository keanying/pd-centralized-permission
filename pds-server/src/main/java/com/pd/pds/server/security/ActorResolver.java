package com.pd.pds.server.security;

import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从访问令牌解析当前调用方。人员令牌带 uid 声明；服务令牌（client_credentials）没有 uid。
 */
@Component
public class ActorResolver {

    public CurrentActor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        Jwt jwt = token.getToken();
        Set<String> scopes = new HashSet<>();
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof List<?> list) {
            list.forEach(s -> scopes.add(String.valueOf(s)));
        } else if (scope instanceof String s) {
            scopes.addAll(List.of(s.split(" ")));
        }
        String via = null;
        Object act = jwt.getClaims().get("act");
        if (act instanceof Map<?, ?> m && m.get("sub") != null) {
            via = String.valueOf(m.get("sub"));
        }
        String uid = jwt.getClaimAsString("uid");
        String clientId = uid == null ? jwt.getSubject() : firstAudience(jwt);
        return new CurrentActor(uid, jwt.getClaimAsString("name"), clientId, via, Set.copyOf(scopes), clientIp());
    }

    private static String firstAudience(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        return aud == null || aud.isEmpty() ? null : aud.get(0);
    }

    public static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                return fwd.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        }
        return null;
    }
}
