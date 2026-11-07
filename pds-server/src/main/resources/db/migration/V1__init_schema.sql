-- PDS 统一权限：初始表结构（PostgreSQL；测试使用 H2 PostgreSQL 兼容模式）

-- ============ 组织架构 ============
CREATE TABLE org_unit (
    id          VARCHAR(32)  PRIMARY KEY,
    parent_id   VARCHAR(32),
    path        VARCHAR(1024) NOT NULL,          -- 物化路径：/org_root/S001/D0103/
    type        VARCHAR(16)  NOT NULL,           -- GROUP / COMPANY / SCENIC / DEPT / TEAM
    scenic_id   VARCHAR(32),                     -- 所属景区（景区节点本身的 scenic_id = 自身 id）
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    leader_id   VARCHAR(32),
    sort        INTEGER      NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_org_unit_parent ON org_unit (parent_id);
CREATE INDEX idx_org_unit_path ON org_unit (path);

CREATE TABLE org_position (
    id          VARCHAR(32)  PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    scenic_id   VARCHAR(32),
    status      SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sys_user (
    id                   VARCHAR(32)  PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL UNIQUE,
    emp_no               VARCHAR(64)  UNIQUE,
    name                 VARCHAR(64)  NOT NULL,
    mobile               VARCHAR(32),
    email                VARCHAR(128),
    user_type            VARCHAR(16)  NOT NULL DEFAULT 'FULL_TIME',   -- FULL_TIME / OUTSOURCE / INTERN / TEMP
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE / DISABLED / LEFT
    primary_org_id       VARCHAR(32),
    scenic_id            VARCHAR(32),
    password_hash        VARCHAR(200) NOT NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    password_changed_at  TIMESTAMP WITH TIME ZONE,
    failed_attempts      INTEGER      NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP WITH TIME ZONE,
    last_login_at        TIMESTAMP WITH TIME ZONE,
    last_login_ip        VARCHAR(64),
    token_epoch          BIGINT       NOT NULL DEFAULT 0,             -- 早于此时间（秒）签发的令牌一律失效
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_sys_user_org ON sys_user (primary_org_id);

-- 人员任职：一人可多部门/多岗位
CREATE TABLE sys_user_org (
    id          VARCHAR(32) PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    org_id      VARCHAR(32) NOT NULL,
    position_id VARCHAR(32),
    is_primary  BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_user_org_user ON sys_user_org (user_id);
CREATE INDEX idx_user_org_org ON sys_user_org (org_id);

CREATE TABLE sys_group (
    id          VARCHAR(32)  PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    scenic_id   VARCHAR(32),
    description VARCHAR(512),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sys_group_member (
    group_id VARCHAR(32) NOT NULL,
    user_id  VARCHAR(32) NOT NULL,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_group_member_user ON sys_group_member (user_id);

-- ============ 应用与资源 ============
CREATE TABLE app_info (
    code             VARCHAR(32)  PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512),
    manifest_version VARCHAR(64),
    status           SMALLINT     NOT NULL DEFAULT 1,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 应用的 OAuth 客户端（客户端本身存放在 oauth2_registered_client）
CREATE TABLE app_client (
    client_id  VARCHAR(100) PRIMARY KEY,
    app_code   VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE perm_point (
    code             VARCHAR(128) PRIMARY KEY,
    app_code         VARCHAR(32)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    type             VARCHAR(16)  NOT NULL,          -- MENU / BUTTON / API / FUNCTION / DATA / AI_TOOL / AI_MODEL / AI_SKILL
    risk             VARCHAR(8)   NOT NULL DEFAULT 'LOW',
    description      VARCHAR(512),
    status           SMALLINT     NOT NULL DEFAULT 1,
    manifest_version VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_perm_point_app ON perm_point (app_code);

CREATE TABLE perm_resource_type (
    type_code VARCHAR(64)  PRIMARY KEY,               -- 如 pdk:kb
    app_code  VARCHAR(32)  NOT NULL,
    name      VARCHAR(128) NOT NULL,
    actions   VARCHAR(128) NOT NULL DEFAULT 'READ,WRITE,ADMIN'
);

CREATE TABLE perm_resource (
    id         VARCHAR(32)  PRIMARY KEY,
    type_code  VARCHAR(64)  NOT NULL,
    ref_id     VARCHAR(128) NOT NULL,
    app_code   VARCHAR(32)  NOT NULL,
    name       VARCHAR(256) NOT NULL,
    scenic_id  VARCHAR(32)  NOT NULL,                 -- GROUP 表示集团共享
    owner_id   VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_perm_resource UNIQUE (type_code, ref_id)
);

-- ============ 授权 ============
CREATE TABLE auth_role (
    id          VARCHAR(32)  PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    level       VARCHAR(16)  NOT NULL DEFAULT 'GROUP',     -- GROUP / SCENIC
    scenic_id   VARCHAR(32),
    description VARCHAR(512),
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE auth_role_perm (
    id           VARCHAR(32)  PRIMARY KEY,
    role_id      VARCHAR(32)  NOT NULL,
    perm_code    VARCHAR(128) NOT NULL,
    data_scope   VARCHAR(16),
    scope_values VARCHAR(2000),
    CONSTRAINT uk_role_perm UNIQUE (role_id, perm_code)
);

CREATE TABLE auth_role_binding (
    id               VARCHAR(32) PRIMARY KEY,
    role_id          VARCHAR(32) NOT NULL,
    subject_type     VARCHAR(16) NOT NULL,                 -- USER / POSITION / ORG / GROUP
    subject_id       VARCHAR(32) NOT NULL,
    include_children BOOLEAN     NOT NULL DEFAULT FALSE,
    valid_from       TIMESTAMP WITH TIME ZONE,
    valid_to         TIMESTAMP WITH TIME ZONE,
    created_by       VARCHAR(32),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_role_binding_subject ON auth_role_binding (subject_type, subject_id);
CREATE INDEX idx_role_binding_role ON auth_role_binding (role_id);

-- 人员明细权限：permCode 与 resource_id 二选一
CREATE TABLE auth_user_perm (
    id           VARCHAR(32)  PRIMARY KEY,
    user_id      VARCHAR(32)  NOT NULL,
    perm_code    VARCHAR(128),
    resource_id  VARCHAR(32),
    action       VARCHAR(8),
    effect       VARCHAR(8)   NOT NULL,                    -- ALLOW / DENY
    data_scope   VARCHAR(16),
    scope_values VARCHAR(2000),
    valid_from   TIMESTAMP WITH TIME ZONE,
    valid_to     TIMESTAMP WITH TIME ZONE,
    reason       VARCHAR(256) NOT NULL,
    granted_by   VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_user_perm_user ON auth_user_perm (user_id);

CREATE TABLE auth_resource_grant (
    id               VARCHAR(32)  PRIMARY KEY,
    resource_id      VARCHAR(32)  NOT NULL,
    subject_type     VARCHAR(16)  NOT NULL,
    subject_id       VARCHAR(32)  NOT NULL,
    include_children BOOLEAN      NOT NULL DEFAULT FALSE,
    action           VARCHAR(8)   NOT NULL,
    effect           VARCHAR(8)   NOT NULL,
    valid_from       TIMESTAMP WITH TIME ZONE,
    valid_to         TIMESTAMP WITH TIME ZONE,
    reason           VARCHAR(256),
    granted_by       VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_resource_grant_resource ON auth_resource_grant (resource_id);
CREATE INDEX idx_resource_grant_subject ON auth_resource_grant (subject_type, subject_id);

CREATE TABLE auth_user_scenic (
    user_id   VARCHAR(32) NOT NULL,
    scenic_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, scenic_id)
);

CREATE TABLE auth_security_level (
    id               VARCHAR(32) PRIMARY KEY,
    subject_type     VARCHAR(16) NOT NULL,
    subject_id       VARCHAR(32) NOT NULL,
    include_children BOOLEAN     NOT NULL DEFAULT FALSE,
    max_level        SMALLINT    NOT NULL,                 -- 0 公开 / 1 内部 / 2 秘密 / 3 机密
    CONSTRAINT uk_security_level UNIQUE (subject_type, subject_id)
);

-- 全局授权版本号：任何授权相关变更时递增，应用据此判断快照是否过期
CREATE TABLE authz_version (
    id      SMALLINT PRIMARY KEY,
    version BIGINT   NOT NULL
);
INSERT INTO authz_version (id, version) VALUES (1, 1);

-- ============ 审计 ============
CREATE TABLE audit_event (
    seq         BIGINT        PRIMARY KEY,               -- 单调递增序号（哈希链顺序）
    id          VARCHAR(40)   NOT NULL UNIQUE,
    ts          TIMESTAMP WITH TIME ZONE NOT NULL,
    app         VARCHAR(32)   NOT NULL,
    event_type  VARCHAR(32)   NOT NULL,
    user_id     VARCHAR(64),
    user_name   VARCHAR(64),
    via         VARCHAR(100),                            -- 代理方（如智能体客户端）
    scenic_id   VARCHAR(32),
    ip          VARCHAR(64),
    target_type VARCHAR(64),
    target_ids  VARCHAR(2000),
    action      VARCHAR(128),
    result      VARCHAR(16)   NOT NULL,                  -- SUCCESS / FAILURE / ALLOW / DENY
    detail      TEXT,
    trace_id    VARCHAR(64),
    prev_hash   VARCHAR(64)   NOT NULL,
    hash        VARCHAR(64)   NOT NULL
);
CREATE INDEX idx_audit_ts ON audit_event (ts);
CREATE INDEX idx_audit_user ON audit_event (user_id, ts);
CREATE INDEX idx_audit_type ON audit_event (event_type, ts);

-- ============ OAuth2 授权服务器（Spring Authorization Server 标准表，PostgreSQL 版） ============
CREATE TABLE oauth2_registered_client (
    id                            VARCHAR(100)  NOT NULL,
    client_id                     VARCHAR(100)  NOT NULL,
    client_id_issued_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret                 VARCHAR(200)  DEFAULT NULL,
    client_secret_expires_at      TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    client_name                   VARCHAR(200)  NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris     VARCHAR(1000) DEFAULT NULL,
    scopes                        VARCHAR(1000) NOT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100) NOT NULL,
    registered_client_id          VARCHAR(100) NOT NULL,
    principal_name                VARCHAR(200) NOT NULL,
    authorization_grant_type      VARCHAR(100) NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    TEXT DEFAULT NULL,
    state                         VARCHAR(500) DEFAULT NULL,
    authorization_code_value      TEXT DEFAULT NULL,
    authorization_code_issued_at  TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    authorization_code_metadata   TEXT DEFAULT NULL,
    access_token_value            TEXT DEFAULT NULL,
    access_token_issued_at        TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    access_token_expires_at       TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    access_token_metadata         TEXT DEFAULT NULL,
    access_token_type             VARCHAR(100) DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           TEXT DEFAULT NULL,
    oidc_id_token_issued_at       TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    oidc_id_token_expires_at      TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    oidc_id_token_metadata        TEXT DEFAULT NULL,
    refresh_token_value           TEXT DEFAULT NULL,
    refresh_token_issued_at       TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    refresh_token_expires_at      TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    refresh_token_metadata        TEXT DEFAULT NULL,
    user_code_value               TEXT DEFAULT NULL,
    user_code_issued_at           TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    user_code_expires_at          TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    user_code_metadata            TEXT DEFAULT NULL,
    device_code_value             TEXT DEFAULT NULL,
    device_code_issued_at         TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    device_code_expires_at        TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    device_code_metadata          TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
