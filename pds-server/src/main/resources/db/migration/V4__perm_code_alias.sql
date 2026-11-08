-- 权限编码修改后的兼容别名：已接入系统继续按原编码鉴权（检查、快照、清单同步均识别），迁移完成后可移除
CREATE TABLE perm_code_alias (
    old_code   VARCHAR(128) PRIMARY KEY,
    new_code   VARCHAR(128) NOT NULL,
    app_code   VARCHAR(32)  NOT NULL,
    reason     VARCHAR(256),
    created_by VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_perm_code_alias_new ON perm_code_alias (new_code);
