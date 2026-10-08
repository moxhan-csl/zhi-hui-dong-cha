import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiErrorBody } from '@/types/api'

export const TOKEN_KEY = 'zd_token'
export const REFRESH_TOKEN_KEY = 'zd_refresh_token'

export function getTokens() {
  return {
    token: localStorage.getItem(TOKEN_KEY) || '',
    refreshToken: localStorage.getItem(REFRESH_TOKEN_KEY) || '',
  }
}

export function setTokens(token: string, refreshToken: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

export class HttpError extends Error {
  status: number
  code: string
  retryAfter?: number
  constructor(status: number, body: Partial<ApiErrorBody> = {}, message?: string) {
    super(message || body.message || `请求失败(${status || '网络异常'})`)
    this.status = status
    this.code = body.code || 'ERROR'
    this.retryAfter = body.retryAfter
  }
}

/** 认证请求头（SSE fetch 也复用） */
export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const { token } = getTokens()
  const h: Record<string, string> = { ...extra }
  if (token) h.Authorization = `Bearer ${token}`
  return h
}

const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use((cfg: InternalAxiosRequestConfig) => {
  const { token } = getTokens()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

interface RefreshResult { token: string; refreshToken?: string }

let refreshing: Promise<boolean> | null = null

async function tryRefresh(): Promise<boolean> {
  const { refreshToken } = getTokens()
  if (!refreshToken) return false
  try {
    const res = await axios.post<RefreshResult>('/api/auth/refresh', { refreshToken })
    setTokens(res.data.token, res.data.refreshToken || refreshToken)
    return true
  } catch {
    return false
  }
}

let redirecting = false
async function forceLogin() {
  clearTokens()
  if (redirecting) return
  redirecting = true
  try {
    const { default: router } = await import('@/router')
    const cur = router.currentRoute.value
    if (cur.path !== '/login') {
      await router.push({ path: '/login', query: { redirect: cur.fullPath } })
    }
  } catch {
    if (window.location.pathname !== '/login') window.location.href = '/login'
  } finally {
    setTimeout(() => { redirecting = false }, 1000)
  }
}

http.interceptors.response.use(
  (r) => r,
  async (error: AxiosError<ApiErrorBody>) => {
    const resp = error.response
    const cfg = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    // 401 → 尝试用 refreshToken 换取新 token 并重放一次
    if (resp?.status === 401 && cfg && !cfg._retried && !String(cfg.url).includes('/auth/')) {
      cfg._retried = true
      if (!refreshing) refreshing = tryRefresh().finally(() => { refreshing = null })
      const ok = await refreshing
      if (ok) {
        cfg.headers.set('Authorization', `Bearer ${getTokens().token}`)
        return http.request(cfg)
      }
      forceLogin()
    }
    return Promise.reject(
      new HttpError(resp?.status ?? 0, resp?.data ?? {}, error.response ? undefined : '网络异常，请检查后端服务')
    )
  }
)

/**
 * 统一请求封装：返回 data。
 * silent=true 时不自动弹 toast（登录、429 等页面自行处理的场景使用），错误仍然抛出 HttpError。
 */
export async function request<T>(config: AxiosRequestConfig & { silent?: boolean }): Promise<T> {
  const { silent, ...axiosCfg } = config
  try {
    const res = await http.request<any, { data: T }>(axiosCfg)
    return res.data
  } catch (e) {
    const err = e instanceof HttpError ? e : new HttpError(0, {}, String((e as Error)?.message || e))
    if (!silent) {
      if (err.status === 403) ElMessage.error('权限不足')
      else if (err.status !== 401) ElMessage.error(err.message)
    }
    throw err
  }
}

export { http }
export default http
