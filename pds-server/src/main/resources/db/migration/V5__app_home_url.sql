-- 应用访问地址：门户（如 PDA 问答首页）的“业务应用入口”据此单点登录跳转
ALTER TABLE app_info ADD COLUMN home_url VARCHAR(512);
