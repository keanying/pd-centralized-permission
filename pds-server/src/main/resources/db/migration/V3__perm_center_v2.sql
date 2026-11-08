-- ======================================================================
-- V2 权限中心：权限树、节点类型、任意主体授权、组织继承控制、系统角色增强、下发
-- ======================================================================

-- ---------- 权限树：权限点升级为树节点 ----------
-- status：0 停用 / 1 生效 / 2 草稿（新建未发布，编码可改）
-- pending：已发布节点的待发布修改（JSON），发布时生效
ALTER TABLE perm_point ADD COLUMN id VARCHAR(32);
ALTER TABLE perm_point ADD COLUMN parent_id VARCHAR(32);
ALTER TABLE perm_point ADD COLUMN sort INTEGER NOT NULL DEFAULT 0;
ALTER TABLE perm_point ADD COLUMN security_level INTEGER;
ALTER TABLE perm_point ADD COLUMN grantable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE perm_point ADD COLUMN need_mfa BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE perm_point ADD COLUMN data_scope_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE perm_point ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANIFEST';
ALTER TABLE perm_point ADD COLUMN customized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE perm_point ADD COLUMN pending TEXT;
ALTER TABLE perm_point ADD COLUMN created_by VARCHAR(32);
CREATE INDEX idx_perm_point_parent ON perm_point (parent_id);

-- 节点类型（字典，全部可在后台增删改；以下仅为初始数据）
CREATE TABLE perm_node_type (
    code               VARCHAR(32)  PRIMARY KEY,
    name               VARCHAR(64)  NOT NULL,
    icon               VARCHAR(32),
    color              VARCHAR(16),
    parent_types       VARCHAR(512),           -- 允许的父节点类型，逗号分隔；空 = 不限（可作为应用下的一级节点）
    grantable          BOOLEAN      NOT NULL DEFAULT TRUE,
    data_scope         BOOLEAN      NOT NULL DEFAULT FALSE,
    default_risk       VARCHAR(8)   NOT NULL DEFAULT 'LOW',
    description        VARCHAR(256),
    sort               INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO perm_node_type (code, name, icon, color, parent_types, grantable, data_scope, default_risk, description, sort, created_at, updated_at) VALUES
('DOMAIN',      '业务域', 'folder',   '#3B82F6', NULL, TRUE,  TRUE,  'LOW', '按业务划分的权限分组，授予业务域即授予其下全部非高风险权限', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DATA_DOMAIN', '数据域', 'database', '#0D9488', NULL, TRUE,  TRUE,  'LOW', '按数据主题划分，与 PDK 治理台的数据域一致', 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('MODULE',      '模块',   'module',   '#6366F1', NULL, TRUE,  FALSE, 'LOW', NULL, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('MENU',        '菜单',   'menu',     '#6366F1', NULL, TRUE,  TRUE,  'LOW', NULL, 40, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('PAGE',        '页面',   'page',     '#6366F1', NULL, TRUE,  TRUE,  'LOW', NULL, 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('BUTTON',      '按钮',   'button',   '#4A7A22', NULL, TRUE,  FALSE, 'LOW', NULL, 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('FUNCTION',    '功能',   'button',   '#4A7A22', NULL, TRUE,  TRUE,  'LOW', NULL, 65, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('API',         '接口',   'api',      '#64748B', NULL, TRUE,  TRUE,  'LOW', NULL, 70, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DATA',        '数据',   'table',    '#0D9488', NULL, TRUE,  TRUE,  'LOW', '数据集、表、指标', 80, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('FIELD',       '字段',   'field',    '#D97706', 'DATA', TRUE, FALSE, 'LOW', NULL, 90, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('AI_GROUP',    'AI 能力', 'ai',      '#A855F7', NULL, TRUE,  FALSE, 'LOW', '智能体、技能、工具、模型的分组', 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('AI_AGENT',    '智能体', 'ai',       '#A855F7', NULL, TRUE,  FALSE, 'LOW', NULL, 110, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('AI_SKILL',    '技能',   'ai',       '#A855F7', NULL, TRUE,  FALSE, 'LOW', NULL, 120, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('AI_TOOL',     '工具',   'tool',     '#A855F7', NULL, TRUE,  FALSE, 'LOW', NULL, 130, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('AI_MODEL',    '模型',   'model',    '#A855F7', NULL, TRUE,  FALSE, 'LOW', NULL, 140, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 权限树发布记录
CREATE TABLE perm_tree_version (
    id           VARCHAR(32)  PRIMARY KEY,
    app_code     VARCHAR(32)  NOT NULL,
    version      VARCHAR(32)  NOT NULL,
    summary      VARCHAR(512),
    changes      TEXT,
    published_by VARCHAR(32),
    published_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_perm_tree_version_app ON perm_tree_version (app_code, published_at);

-- ---------- 任意主体的权限节点授权（人员/组织/岗位/用户组） ----------
CREATE TABLE auth_grant (
    id               VARCHAR(32)  PRIMARY KEY,
    subject_type     VARCHAR(16)  NOT NULL,
    subject_id       VARCHAR(32)  NOT NULL,
    include_children BOOLEAN      NOT NULL DEFAULT FALSE,   -- 组织：本节点及下级（向下继承）
    perm_code        VARCHAR(128) NOT NULL,
    effect           VARCHAR(8)   NOT NULL,
    data_scope       VARCHAR(16),
    scope_values     VARCHAR(2000),
    valid_from       TIMESTAMP WITH TIME ZONE,
    valid_to         TIMESTAMP WITH TIME ZONE,
    reason           VARCHAR(256),
    source           VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',   -- MANUAL / TEMPLATE / APPLY / SYNC
    granted_by       VARCHAR(32),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_auth_grant_subject ON auth_grant (subject_type, subject_id);
CREATE INDEX idx_auth_grant_perm ON auth_grant (perm_code);

-- 人员明细中的权限点授权迁入 auth_grant（资源授权仍留在 auth_user_perm）
INSERT INTO auth_grant (id, subject_type, subject_id, include_children, perm_code, effect, data_scope, scope_values,
                        valid_from, valid_to, reason, source, granted_by, created_at, updated_at)
SELECT id, 'USER', user_id, FALSE, perm_code, effect, data_scope, scope_values, valid_from, valid_to, reason, 'MANUAL',
       granted_by, created_at, created_at
FROM auth_user_perm WHERE perm_code IS NOT NULL;
DELETE FROM auth_user_perm WHERE perm_code IS NOT NULL;

-- ---------- 组织继承控制 ----------
ALTER TABLE org_unit ADD COLUMN inherit_from_parent BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sys_user_org ADD COLUMN inherit_perms BOOLEAN NOT NULL DEFAULT TRUE;

-- ---------- 系统角色 ----------
ALTER TABLE auth_role ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';   -- BUILTIN / GLOBAL / APP / SCENIC
ALTER TABLE auth_role ADD COLUMN app_code VARCHAR(32);
ALTER TABLE auth_role ADD COLUMN parent_role_id VARCHAR(32);
ALTER TABLE auth_role ADD COLUMN status SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE auth_role ADD COLUMN owner_id VARCHAR(32);
ALTER TABLE auth_role ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
UPDATE auth_role SET type = 'BUILTIN' WHERE builtin = TRUE;
UPDATE auth_role SET type = 'SCENIC' WHERE builtin = FALSE AND level = 'SCENIC';

CREATE TABLE auth_role_mutex (
    role_id       VARCHAR(32) NOT NULL,
    other_role_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (role_id, other_role_id)
);

CREATE TABLE auth_role_mapping (
    id            VARCHAR(32)  PRIMARY KEY,
    role_id       VARCHAR(32)  NOT NULL,
    app_code      VARCHAR(32)  NOT NULL,
    external_code VARCHAR(128) NOT NULL,
    CONSTRAINT uk_role_mapping UNIQUE (role_id, app_code)
);

CREATE TABLE auth_role_version (
    id         VARCHAR(32) PRIMARY KEY,
    role_id    VARCHAR(32) NOT NULL,
    version    INTEGER     NOT NULL,
    summary    VARCHAR(512),
    snapshot   TEXT        NOT NULL,
    created_by VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_role_version_role ON auth_role_version (role_id, version);

-- ---------- 下发：订阅、事件、投递 ----------
CREATE TABLE dist_subscription (
    app_code     VARCHAR(32)  PRIMARY KEY,
    token_claim  BOOLEAN      NOT NULL DEFAULT TRUE,
    snapshot     BOOLEAN      NOT NULL DEFAULT TRUE,
    webhook      BOOLEAN      NOT NULL DEFAULT FALSE,
    scim         BOOLEAN      NOT NULL DEFAULT FALSE,
    webhook_url  VARCHAR(512),
    secret       VARCHAR(128),
    events       VARCHAR(512),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE dist_event (
    id         VARCHAR(32)  PRIMARY KEY,
    type       VARCHAR(64)  NOT NULL,
    ref_type   VARCHAR(32),
    ref_id     VARCHAR(64),
    payload    TEXT         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_dist_event_ref ON dist_event (ref_type, ref_id);

CREATE TABLE dist_delivery (
    id          VARCHAR(32)  PRIMARY KEY,
    event_id    VARCHAR(32)  NOT NULL,
    app_code    VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,        -- PENDING / SUCCESS / RETRYING / FAILED
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  VARCHAR(512),
    latency_ms  INTEGER,
    next_at     TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_dist_delivery_event ON dist_delivery (event_id);
CREATE INDEX idx_dist_delivery_due ON dist_delivery (status, next_at);
