package com.pd.pds.server.config;

import com.pd.pds.server.auth.AppAccessValidator;
import com.pd.pds.server.auth.LoginHandlers;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 三条过滤链：
 * <ol>
 *   <li>OIDC/OAuth2 端点（/oauth2/**、/.well-known/**、/userinfo、/connect/logout）；</li>
 *   <li>管理与开放 API（/api/**）：无状态，Bearer JWT；</li>
 *   <li>其余页面：统一登录页、修改密码页（表单登录 + 会话）。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                        AppAccessValidator appAccess) throws Exception {
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> validator =
                new OAuth2AuthorizationCodeRequestAuthenticationValidator().andThen(appAccess);
        http.oauth2AuthorizationServer(server -> {
                    http.securityMatcher(server.getEndpointsMatcher());
                    server.oidc(Customizer.withDefaults());
                    // 应用准入：在授权码请求的标准校验（回调地址、范围）之后追加
                    server.authorizationEndpoint(ep -> ep.authenticationProviders(providers -> providers.forEach(p -> {
                        if (p instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider provider) {
                            provider.setAuthenticationValidator(validator);
                        }
                    })));
                })
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(j -> j.decoder(jwtDecoder)))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(j -> j.decoder(jwtDecoder))
                        .authenticationEntryPoint((req, res, ex) -> json(res, HttpStatus.UNAUTHORIZED, 20000, "未登录或登录已过期")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> json(res, HttpStatus.FORBIDDEN, 21000, "无访问权限")));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain webChain(HttpSecurity http, LoginHandlers handlers) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/assets/**", "/favicon.ico", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login")
                        .successHandler(handlers.successHandler())
                        .failureHandler(handlers.failureHandler()))
                .logout(l -> l.logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    private static void json(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, int code, String message)
            throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
