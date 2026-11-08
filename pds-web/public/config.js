// PDS 管理后台运行时配置（开发默认值）。部署时替换本文件即可，无需重新构建。
window.__PDS_CONFIG__ = {
  // 页面标题
  title: 'PDS 统一权限',
  // 系统名称（工作台等处显示）
  brandName: 'PDS',
  // PDS 认证中心地址（浏览器跳转登录用）
  issuer: 'http://127.0.0.1:9000',
  // 接口地址：为空表示与页面同源（随 PDS 发布，或经反向代理/开发代理）
  apiBaseUrl: '',
  // 管理后台在 PDS 中登记的客户端（公共客户端 + PKCE）
  clientId: 'pds-console',
}
