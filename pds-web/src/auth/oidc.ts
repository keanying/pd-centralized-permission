import { config, appUrl } from '@/config'

/**
 * 浏览器单页应用的 OIDC 登录：授权码 + PKCE（公共客户端，无密钥）。
 * 令牌只保存在 sessionStorage（关闭浏览器即失效）；过期后重新跳转认证中心，
 * 认证中心会话仍有效时无需再次输入密码（单点登录）。
 */
const KEY = 'pds.console.tokens'
const PENDING = 'pds.console.pending'

export interface Tokens {
  accessToken: string
  idToken?: string
  expiresAt: number
}

function base64url(bytes: ArrayBuffer) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomString(len = 48) {
  const bytes = crypto.getRandomValues(new Uint8Array(len))
  return base64url(bytes.buffer).slice(0, len)
}

const redirectUri = () => appUrl + 'auth/callback'

export function getTokens(): Tokens | null {
  const raw = sessionStorage.getItem(KEY)
  if (!raw) return null
  const t = JSON.parse(raw) as Tokens
  return t.expiresAt > Date.now() + 15_000 ? t : null
}

export async function login(returnTo = window.location.pathname + window.location.search) {
  const verifier = randomString(64)
  const state = randomString(24)
  const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)))
  sessionStorage.setItem(PENDING, JSON.stringify({ verifier, state, returnTo }))
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: config.clientId,
    redirect_uri: redirectUri(),
    scope: 'openid profile',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  window.location.assign(`${config.issuer}/oauth2/authorize?${params}`)
}

/** 处理认证中心回调，返回登录前的页面路径。 */
export async function handleCallback(search: string): Promise<string> {
  const q = new URLSearchParams(search)
  if (q.get('error')) {
    throw new Error(q.get('error_description') || (q.get('error') === 'access_denied' ? '您没有进入 PDS 管理后台的权限' : q.get('error')!))
  }
  const pending = JSON.parse(sessionStorage.getItem(PENDING) || 'null')
  if (!pending || pending.state !== q.get('state')) {
    throw new Error('登录状态已失效，请重新登录')
  }
  sessionStorage.removeItem(PENDING)
  const res = await fetch(`${config.apiBaseUrl}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code: q.get('code') || '',
      redirect_uri: redirectUri(),
      client_id: config.clientId,
      code_verifier: pending.verifier,
    }),
  })
  if (!res.ok) throw new Error('换取令牌失败，请重新登录')
  const body = await res.json()
  const tokens: Tokens = {
    accessToken: body.access_token,
    idToken: body.id_token,
    expiresAt: Date.now() + (body.expires_in ?? 1800) * 1000,
  }
  sessionStorage.setItem(KEY, JSON.stringify(tokens))
  const base = config.basePath.replace(/\/$/, '')
  const returnTo: string = pending.returnTo || '/'
  return returnTo.startsWith(base) ? returnTo.slice(base.length) || '/' : returnTo
}

/** 单点登出：清除本地令牌并结束认证中心会话。 */
export function logout() {
  const t = sessionStorage.getItem(KEY) ? (JSON.parse(sessionStorage.getItem(KEY)!) as Tokens) : null
  sessionStorage.removeItem(KEY)
  const params = new URLSearchParams({ post_logout_redirect_uri: appUrl, client_id: config.clientId })
  if (t?.idToken) params.set('id_token_hint', t.idToken)
  window.location.assign(`${config.issuer}/connect/logout?${params}`)
}

/** 解析访问令牌中的声明（仅用于展示）。 */
export function claims(): Record<string, any> {
  const t = getTokens()
  if (!t) return {}
  const payload = t.accessToken.split('.')[1]
  return JSON.parse(decodeURIComponent(escape(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))))
}
