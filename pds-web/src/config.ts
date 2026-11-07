/** 运行时配置（来自 config.js），提供默认值并规范化。 */
const raw = window.__PDS_CONFIG__ ?? ({} as PdsRuntimeConfig)

export const config = {
  title: raw.title || 'PDS 统一权限',
  issuer: (raw.issuer || window.location.origin).replace(/\/+$/, ''),
  apiBaseUrl: (raw.apiBaseUrl || '').replace(/\/+$/, ''),
  clientId: raw.clientId || 'pds-console',
  /** 前端访问路径（构建期决定），如 / 或 /console/ */
  basePath: import.meta.env.BASE_URL,
}

/** 页面根地址，如 https://pds.example.com/console/ */
export const appUrl = window.location.origin + config.basePath
