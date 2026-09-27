// JWT 本地过期判定：仅解码 payload 读取 exp（不校验签名），
// 用于路由守卫 / 浮窗打开时提前拦截「已过期但本地还残留 token」的情况。
// 服务端签名的有效性（如拉黑）仍以接口 401 为准（见 request.js）。

function base64UrlDecode(s) {
  let b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  while (b64.length % 4 !== 0) b64 += '='
  const bin = atob(b64)
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
  return new TextDecoder('utf-8').decode(bytes)
}

/** 读取 JWT payload（解码失败返回 null）。 */
export function decodeJwtPayload(token) {
  if (!token) return null
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    return JSON.parse(base64UrlDecode(parts[1]))
  } catch {
    return null
  }
}

/**
 * 判断 token 是否已按 exp 过期。
 * @param {string|null} token
 * @returns {boolean} true=已过期或无效；无 exp 声明视为未过期（保守，交由后端 401 判定）
 */
export function isTokenExpired(token) {
  if (!token) return true
  const payload = decodeJwtPayload(token)
  if (!payload || typeof payload.exp !== 'number') return false
  return payload.exp * 1000 <= Date.now()
}

/** 清除本地凭证并广播登录失效事件（App.vue 监听后同步 Pinia）。 */
export function clearLocalAuth() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  window.dispatchEvent(new CustomEvent('auth:expired'))
}
