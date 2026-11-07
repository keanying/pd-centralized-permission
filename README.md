# pd_centralized_permission

**PDS 统一权限**：统一登录 · 组织架构 · 角色授权 · 人员明细权限 · 景区隔离 · 字段脱敏 · 访问审计（Java）

PDS 面向客户总体架构中的**全部系统**通用：应用层（票付通、景数通、数字大脑、畅游、各类 APP）、PDQ / PBI / PAP（PDA）、PDT / PDK / PDD、PDG、PDE、PDP / PDL / PDR、PDC、PBS。各系统按《[应用接入指南](docs/03-应用接入指南.md)》以同一方式接入。

![PDS 统一登录页](docs/images/pds-login.png)

## 文档

| 文档 | 说明 |
|---|---|
| [00-总体架构与PDA接入方案](docs/00-总体架构与PDA接入方案.md) | PDK / PDS / PDA 总体架构、与客户架构的对应关系、身份透传、权限点目录、品牌规范 |
| [01-PDS产品功能文档](docs/01-PDS产品功能文档.md) | 功能清单、授权模型、对各系统的控制方案、接口清单、验收要点 |
| [02-PDS技术方案](docs/02-PDS技术方案.md) | 认证中心、授权引擎、景区隔离、脱敏、审计、事件、SDK、一期实现说明 |
| [03-应用接入指南](docs/03-应用接入指南.md) | 任一系统接入 PDS 的步骤、权限清单、客户端类型、令牌交换、鉴权集成 |

## 工程结构

```
pd_centralized_permission/
├── pds-common/          # 统一响应、错误码、业务异常、ID 生成
├── pds-authz-engine/    # 有效权限计算引擎（纯 Java，无框架依赖）+ 单元测试
├── pds-server/          # Spring Boot 服务：OIDC 认证中心 + 管理 API + 鉴权开放 API + 审计
│   └── src/main/resources/db/migration   # Flyway 表结构
└── docs/
```

技术栈：Java 21、Spring Boot 4.1、Spring Security 7.1（内置 OAuth2 授权服务器）、MyBatis-Plus 3.5、Flyway、PostgreSQL 16（测试用 H2 PostgreSQL 兼容模式）、Caffeine、Thymeleaf。

## 快速开始

### 本地体验（内存数据库，无需安装 PostgreSQL）

```bash
mvn -DskipTests package
```

```bash
java -jar pds-server/target/pds-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

- 统一登录页：<http://localhost:9000/login>，初始账号 `admin`，密码 `Pds@Admin2026`（仅 `local` 配置）
- OIDC 发现文档：<http://localhost:9000/.well-known/openid-configuration>
- `local` 配置预置了 `pdk-console`、`pda-gateway`、`pda-plugin`、`pdk-service` 四个开发用客户端，见 `pds-server/src/main/resources/application-local.yml`

### 使用 PostgreSQL

```bash
PDS_DB_URL=jdbc:postgresql://localhost:5432/pds PDS_DB_USERNAME=pds PDS_DB_PASSWORD=*** \
PDS_ISSUER=https://pds.example.com PDS_JWT_KEYSTORE=file:/etc/pds/jwt.p12 PDS_JWT_KEYSTORE_PASSWORD=*** \
java -jar pds-server/target/pds-server-0.1.0-SNAPSHOT.jar
```

首次启动自动建表并创建初始超级管理员 `admin`；未设置 `PDS_ADMIN_PASSWORD` 时随机生成密码并在日志中输出一次，首次登录须修改。初始化完成后请分别创建系统管理员、安全管理员、审计管理员（三员分立），并停用超级管理员。

生成 JWT 签名密钥（生产必须配置，否则每次重启已签发令牌失效）：

```bash
keytool -genkeypair -alias pds -keyalg RSA -keysize 2048 -validity 3650 -storetype PKCS12 -keystore jwt.p12 -dname "CN=PDS"
```

### 运行测试

```bash
mvn test
```

- `pds-authz-engine`：12 个引擎单元测试（禁止优先、组织继承、有效期、数据范围并集、资源动作层级、景区约束、密级、停用权限点、按应用过滤）
- `pds-server`：12 个端到端集成测试（授权码 + PKCE 单点登录、首次登录强制改密、登录锁定、令牌交换、应用准入、权限清单、资源授权、人员明细禁止、鉴权开放接口、三员分立、停用即吊销、审计哈希链）

指定 PostgreSQL 运行集成测试：

```bash
mvn -pl pds-server test -Dspring.datasource.url=jdbc:postgresql://localhost:5432/pds -Dspring.datasource.username=pds -Dspring.datasource.password=***
```

## 接口一览

| 分类 | 接口 |
|---|---|
| OIDC | `/.well-known/openid-configuration`、`/oauth2/authorize`、`/oauth2/token`（授权码/刷新/客户端凭证/令牌交换）、`/oauth2/jwks`、`/userinfo`、`/connect/logout`、`/oauth2/revoke` |
| 组织架构 | `GET /api/v1/orgs/tree`、`POST/PUT/DELETE /api/v1/orgs`、`GET/POST /api/v1/positions` |
| 人员 | `GET/POST /api/v1/users`、`PUT /api/v1/users/{id}`、`PUT /api/v1/users/{id}/orgs`、`POST /api/v1/users/{id}/disable|leave|enable|reset-password` |
| 用户组 | `GET/POST /api/v1/groups`、`PUT /api/v1/groups/{id}/members` |
| 应用 | `GET /api/v1/apps`、`POST /api/v1/apps/{app}/manifest`、`GET /api/v1/apps/{app}/permissions`、`POST /api/v1/apps/{app}/clients` |
| 资源 | `POST /api/v1/resources`、`DELETE /api/v1/resources/{type}/{refId}`、`GET/POST/DELETE /api/v1/resources/{type}/{refId}/grants` |
| 角色 | `GET/POST /api/v1/roles`、`PUT /api/v1/roles/{id}/permissions`、`GET/POST /api/v1/roles/{id}/bindings`、`DELETE /api/v1/role-bindings/{id}` |
| 人员明细权限 | `GET /api/v1/users/{id}/effective?app=`、`GET/POST/DELETE /api/v1/users/{id}/perms`、`PUT /api/v1/users/{id}/scenic-scope`、`PUT /api/v1/security-levels` |
| 鉴权开放 | `POST /api/v1/authz/check`、`POST /api/v1/authz/batch-check`、`GET /api/v1/authz/snapshot`、`GET /api/v1/authz/explain`、`GET /api/v1/authz/resources/accessible` |
| 审计 | `GET /api/v1/audit/events`、`GET /api/v1/audit/verify`、`POST /api/v1/audit/events`（应用上报） |
| 个人中心 | `GET /api/v1/me`、`POST /api/v1/me/password` |

统一响应：`{"code":0,"message":"成功","data":{...}}`；错误码见 `pds-common` 的 `ErrorCode`，提示信息均为中文。

## 品牌

主色 `#1E50E6`（PDS 蓝），强调色 `#22E59A`（绿）。登录页标志 `pds-server/src/main/resources/static/assets/pds-logo.svg` 为按品牌稿绘制的矢量近似版，拿到设计源文件后直接替换同名文件即可。

相关仓库：[pd_knowledge_service](https://github.com/keanying/pd_knowledge_service)（PDK 知识服务）
