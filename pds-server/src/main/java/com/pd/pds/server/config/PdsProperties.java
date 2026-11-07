package com.pd.pds.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * PDS 配置项（前缀 pds）。
 */
@Data
@ConfigurationProperties("pds")
public class PdsProperties {

    /** 认证中心对外地址，即令牌 iss，例如 https://pds.example.com。 */
    private String issuer = "http://localhost:9000";

    private Jwt jwt = new Jwt();
    private Console console = new Console();
    private Cors cors = new Cors();
    private Login login = new Login();
    private Bootstrap bootstrap = new Bootstrap();

    @Data
    public static class Jwt {
        /** PKCS12 密钥库路径；为空时启动生成临时 RSA 密钥（仅限开发/测试，重启后已签发令牌失效）。 */
        private String keystorePath;
        private String keystorePassword;
        private String keyAlias = "pds";
        /** 令牌中 scenic_scope 最多直接携带的景区数，超出则只携带 authz_ver，由快照获取。 */
        private int maxScenicInToken = 20;
    }

    /**
     * PDS 管理后台（pds-web）。前端可单独部署，也可编译后随 PDS 一起发布（访问 {basePath}/）。
     * 两种方式都通过运行时 config.js 配置，同一份前端构建产物可用于任意环境。
     */
    @Data
    public static class Console {
        /** 是否注册/同步管理后台客户端（公共客户端 + PKCE）。 */
        private boolean enabled = true;
        private String clientId = "pds-console";
        /** 随 PDS 一起发布时的访问路径。 */
        private String basePath = "/console";
        /** 管理后台回调地址；为空时默认 {issuer}{basePath}/auth/callback。单独部署时填写前端域名下的回调地址。 */
        private List<String> redirectUris = new ArrayList<>();
        /** 退出登录后跳转地址；为空时默认 {issuer}{basePath}/。 */
        private List<String> postLogoutRedirectUris = new ArrayList<>();
        /** 前端调用 PDS 接口的地址；为空表示与页面同源（随 PDS 发布或经反向代理）。 */
        private String apiBaseUrl = "";
        private String title = "PDS 统一权限";
    }

    /** 跨域：前端单独部署且不经反向代理时，填写前端来源（如 https://pds-admin.example.com）。 */
    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Data
    public static class Login {
        private int maxFailedAttempts = 5;
        private Duration lockDuration = Duration.ofMinutes(30);
    }

    @Data
    public static class Bootstrap {
        private String adminUsername = "admin";
        /** 初始超级管理员密码；为空时随机生成并在首次启动日志中输出一次。 */
        private String adminPassword;
        /** 初始超级管理员首次登录是否强制修改密码。 */
        private boolean adminMustChangePassword = true;
        /** 启动时确保存在的 OAuth 客户端（便于开发与联调；生产建议通过管理接口创建）。 */
        private List<ClientDefinition> clients = new ArrayList<>();
    }

    @Data
    public static class ClientDefinition {
        private String clientId;
        private String clientSecret;
        private String name;
        private String app;
        private List<String> grantTypes = new ArrayList<>();
        private List<String> redirectUris = new ArrayList<>();
        private List<String> postLogoutRedirectUris = new ArrayList<>();
        private List<String> scopes = new ArrayList<>();
        private boolean requirePkce = true;
        /** 公共客户端（浏览器单页应用，无密钥，强制 PKCE）。 */
        private boolean publicClient;
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofHours(8);
    }
}
