package com.pd.pds.server.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.pd.pds.server.auth.TokenEpochValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * 认证中心基础设施：客户端/授权记录 JDBC 存储、签名密钥、令牌解码与校验。
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(PdsProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.getIssuer()).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(PdsProperties props) throws Exception {
        PdsProperties.Jwt cfg = props.getJwt();
        RSAKey key;
        if (StringUtils.hasText(cfg.getKeystorePath())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            char[] pwd = cfg.getKeystorePassword() == null ? new char[0] : cfg.getKeystorePassword().toCharArray();
            try (InputStream in = new DefaultResourceLoader().getResource(cfg.getKeystorePath()).getInputStream()) {
                ks.load(in, pwd);
            }
            PrivateKey priv = (PrivateKey) ks.getKey(cfg.getKeyAlias(), pwd);
            Certificate cert = ks.getCertificate(cfg.getKeyAlias());
            key = new RSAKey.Builder((RSAPublicKey) cert.getPublicKey()).privateKey((RSAPrivateKey) priv)
                    .keyID(cfg.getKeyAlias()).build();
        } else {
            log.warn("未配置 pds.jwt.keystore-path，使用启动时生成的临时 RSA 密钥（仅限开发/测试：重启后已签发的令牌全部失效）");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("pds-dev").build();
        }
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    /** 校验签名、有效期、签发者，以及人员令牌是否已被吊销（停用/重置密码/强制下线）。 */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, PdsProperties props, TokenEpochValidator epochValidator) {
        JwtDecoder decoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(props.getIssuer()), epochValidator));
        }
        return decoder;
    }
}
