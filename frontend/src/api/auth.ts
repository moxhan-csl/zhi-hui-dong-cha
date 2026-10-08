import { request, setTokens, getTokens } from './http'
import type { LoginResult, UserInfo } from '@/types/api'

export function login(account: string, password: string) {
  return request<LoginResult>({
    method: 'POST',
    url: '/auth/login',
    data: { account, password },
    silent: true,
  })
}

export async function loginByRefresh(refreshToken: string) {
  const data = await request<LoginResult>({
    method: 'POST',
    url: '/auth/refresh',
    data: { refreshToken },
    silent: true,
  })
  if (data.token) setTokens(data.token, data.refreshToken || getTokens().refreshToken)
  return data
}

export function fetchMe() {
  return request<UserInfo>({ method: 'GET', url: '/auth/me', silent: true })
}
