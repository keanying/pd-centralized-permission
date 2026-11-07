#!/bin/sh
# 容器启动时按环境变量生成运行时配置 config.js 与 Nginx 站点配置（同一镜像适用于任意环境）。
set -eu

HTML_DIR=/usr/share/nginx/html
: "${PDS_ISSUER:?必须设置 PDS_ISSUER（PDS 认证中心地址，如 https://pds.example.com）}"
PDS_TITLE="${PDS_TITLE:-PDS 统一权限}"
PDS_CLIENT_ID="${PDS_CLIENT_ID:-pds-console}"
# 设置 PDS_UPSTREAM 时由本 Nginx 反向代理 /api 与 /oauth2/token（同源，无需跨域）；
# 不设置时浏览器直连 PDS_API_BASE_URL（默认等于 PDS_ISSUER），需在 PDS 配置 pds.cors.allowed-origins。
PDS_UPSTREAM="${PDS_UPSTREAM:-}"
if [ -n "$PDS_UPSTREAM" ]; then
  PDS_API_BASE_URL="${PDS_API_BASE_URL:-}"
else
  PDS_API_BASE_URL="${PDS_API_BASE_URL:-$PDS_ISSUER}"
fi

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

cat > "$HTML_DIR/config.js" <<JS
window.__PDS_CONFIG__ = {
  title: "$(json_escape "$PDS_TITLE")",
  issuer: "$(json_escape "$PDS_ISSUER")",
  apiBaseUrl: "$(json_escape "$PDS_API_BASE_URL")",
  clientId: "$(json_escape "$PDS_CLIENT_ID")"
};
JS

PROXY_BLOCK=""
if [ -n "$PDS_UPSTREAM" ]; then
  PROXY_BLOCK="
    location /api/ {
        proxy_pass ${PDS_UPSTREAM%/}/api/;
        proxy_set_header Host \$proxy_host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Origin \"\";
    }
    location = /oauth2/token {
        proxy_pass ${PDS_UPSTREAM%/}/oauth2/token;
        proxy_set_header Host \$proxy_host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header Origin \"\";
    }"
fi

cat > /etc/nginx/conf.d/default.conf <<NGINX
server {
    listen 80;
    server_name _;
    root $HTML_DIR;

    # 运行时配置不缓存
    location = /config.js { add_header Cache-Control "no-store"; }
    # 带哈希的静态资源长期缓存
    location /assets/ { add_header Cache-Control "public, max-age=31536000, immutable"; }
$PROXY_BLOCK
    # 前端路由（history 模式）回退
    location / { try_files \$uri \$uri/ /index.html; }
}
NGINX

echo "PDS 管理后台：issuer=$PDS_ISSUER apiBaseUrl=${PDS_API_BASE_URL:-（同源）} upstream=${PDS_UPSTREAM:-（无）}"
