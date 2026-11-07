-- 通用化：应用分类（对应客户总体架构分层）与应用准入
ALTER TABLE app_info ADD COLUMN category VARCHAR(32);
ALTER TABLE app_info ADD COLUMN access_control BOOLEAN NOT NULL DEFAULT FALSE;
