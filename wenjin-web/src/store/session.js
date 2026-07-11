import { defineStore } from 'pinia'
import { useCourseStore } from './course.js'

export const TOKEN_KEY = 'wj_token'
export const USER_KEY = 'wj_user'

/**
 * 安全解析 localStorage 中的用户 JSON；坏数据 / 非对象 → null。
 * @param {string | null} raw
 * @returns {object | null}
 */
export function parseStoredUser(raw) {
  if (raw == null || raw === '') return null
  try {
    const user = JSON.parse(raw)
    if (!user || typeof user !== 'object' || Array.isArray(user)) return null
    return user
  } catch {
    return null
  }
}

/**
 * 全局会话：token + user。localStorage 仅作展示/恢复缓存，权限以 /api/me 为准。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    token: /** @type {string | null} */ (null),
    user: /** @type {Record<string, unknown> | null} */ (null),
    hydrated: false,
    /** 启动时 /api/me 刷新 promise（去重） */
    _refreshPromise: /** @type {Promise<Record<string, unknown> | null> | null} */ (null)
  }),

  getters: {
    isAuthenticated: (state) => !!(state.token && state.user),
    userId: (state) => {
      const id = state.user && state.user.id
      return typeof id === 'number' && id > 0 ? id : null
    },
    role: (state) => (state.user && state.user.role != null ? state.user.role : null),
    isTeacher: (state) => {
      const r = state.user && state.user.role
      return r === 1 || r === 3
    }
  },

  actions: {
    /** 从 localStorage 恢复；坏 user JSON → null，无 token 时清空 user。 */
    hydrate() {
      const token = localStorage.getItem(TOKEN_KEY)
      const user = parseStoredUser(localStorage.getItem(USER_KEY))
      this.token = token || null
      this.user = this.token ? user : null
      if (!this.token) {
        localStorage.removeItem(USER_KEY)
      } else if (!user) {
        // 坏缓存不阻塞有 token 的 refreshMe，但展示层视为无 user
        localStorage.removeItem(USER_KEY)
      }
      this.hydrated = true
    },

    /**
     * 登录成功写入会话。
     * @param {{ token: string, user: object }} payload
     */
    setSession({ token, user }) {
      this.token = token || null
      this.user = user || null
      if (this.token) localStorage.setItem(TOKEN_KEY, this.token)
      else localStorage.removeItem(TOKEN_KEY)
      if (this.user) localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      else localStorage.removeItem(USER_KEY)
      this.hydrated = true
    },

    /**
     * 退出 / 401：清会话并清空课程上下文。
     * @param {{ clearCourse?: boolean }} [opts]
     */
    clearSession(opts = {}) {
      const { clearCourse = true } = opts
      this.token = null
      this.user = null
      this._refreshPromise = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      if (clearCourse) {
        try {
          useCourseStore().clearCourseContext()
        } catch {
          /* pinia 未安装时忽略 */
        }
      }
    },

    /**
     * 用 /api/me 刷新身份（启动守卫 / 登录页恢复）。
     * 无 token → 清空并返回 null；失败 → clearSession。
     * @returns {Promise<Record<string, unknown> | null>}
     */
    async refreshMe() {
      if (!this.hydrated) this.hydrate()
      if (!this.token) {
        this.clearSession()
        return null
      }
      // 动态 import 避免 store ↔ http 循环依赖
      const { me } = await import('../api/user.js')
      try {
        const user = await me()
        this.setSession({ token: this.token, user })
        return user
      } catch {
        this.clearSession()
        return null
      }
    },

    /** 进程内只跑一次的启动刷新（路由守卫用）。 */
    refreshMeOnce() {
      if (!this._refreshPromise) {
        this._refreshPromise = this.refreshMe().finally(() => {
          // 成功后保留 resolved promise，避免每次导航重复打 /api/me
        })
      }
      return this._refreshPromise
    },

    /** 强制下次导航重新 refresh（登出后再登录等）。 */
    resetRefreshGate() {
      this._refreshPromise = null
    }
  }
})
