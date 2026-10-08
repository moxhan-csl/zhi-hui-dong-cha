import { defineStore } from 'pinia'
import type { Role, UserInfo } from '@/types/api'
import { clearTokens, getTokens, setTokens } from '@/api/http'
import { fetchMe } from '@/api/auth'

const USER_KEY = 'zd_user'

export const useUserStore = defineStore('user', {
  state: () => ({
    user: null as UserInfo | null,
    initialized: false,
  }),
  getters: {
    logged: (s) => !!s.user && !!getTokens().token,
    role: (s) => s.user?.role ?? null,
    /** 角色等级：EMPLOYEE < KM_ADMIN < SYS_ADMIN */
    level: (s) => ({ EMPLOYEE: 1, KM_ADMIN: 2, SYS_ADMIN: 3 } as Record<Role, number>)[s.user?.role || 'EMPLOYEE'],
  },
  actions: {
    setLoginResult(r: { token: string; refreshToken: string; user: UserInfo }) {
      setTokens(r.token, r.refreshToken)
      this.user = r.user
      localStorage.setItem(USER_KEY, JSON.stringify(r.user))
      this.initialized = true
    },
    setUser(u: UserInfo) {
      this.user = u
      localStorage.setItem(USER_KEY, JSON.stringify(u))
    },
    /** 刷新页面时用 /auth/me 恢复用户态 */
    async restore(): Promise<boolean> {
      if (this.initialized) return !!this.user
      this.initialized = true
      const { token } = getTokens()
      if (!token) return false
      try {
        const u = await fetchMe()
        this.setUser(u)
        return true
      } catch {
        const cached = localStorage.getItem(USER_KEY)
        if (cached) {
          try {
            this.user = JSON.parse(cached)
            return true
          } catch { /* ignore */ }
        }
        this.logout()
        return false
      }
    },
    logout() {
      this.user = null
      this.initialized = true
      clearTokens()
      localStorage.removeItem(USER_KEY)
    },
    /** 是否允许访问要求 minRole 的功能 */
    atLeast(min: Role): boolean {
      const order: Record<Role, number> = { EMPLOYEE: 1, KM_ADMIN: 2, SYS_ADMIN: 3 }
      return this.level >= order[min]
    },
  },
})
