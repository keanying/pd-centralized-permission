# PDS 管理后台单独部署镜像：构建一次，按环境变量运行于任意环境
FROM node:22-alpine AS build
WORKDIR /app
# 通过 corepack 使用 package.json 中 packageManager 指定的 pnpm 版本
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/40-pds-runtime.sh /docker-entrypoint.d/40-pds-runtime.sh
RUN chmod +x /docker-entrypoint.d/40-pds-runtime.sh && rm -f /usr/share/nginx/html/config.js
EXPOSE 80
