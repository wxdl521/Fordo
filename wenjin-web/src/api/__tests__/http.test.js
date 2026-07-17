import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  http,
  unwrapSuccess,
  unwrapFailure,
  unwrapError,
  clearSessionAndRedirect
} from '../http.js'
import { useSessionStore } from '../../store/session.js'

describe('http Result unwrap', () => {
  const originalLocation = window.location

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(undefined)
    // happy-dom: allow reassign pathname checks via stub
    delete window.location
    window.location = { pathname: '/map', assign: vi.fn() }
  })

  afterEach(() => {
    localStorage.clear()
    setActivePinia(undefined)
    window.location = originalLocation
  })

  it('request interceptor snapshots the token on the request config and Authorization header', async () => {
    localStorage.setItem('wj_token', 'token-a')
    let requestConfig

    await http.get('/token-snapshot', {
      adapter: async (config) => {
        requestConfig = config
        return {
          data: { code: 0, message: '成功', data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          request: {}
        }
      }
    })

    expect(requestConfig.__wjAuthToken).toBe('token-a')
    expect(requestConfig.headers.get('Authorization')).toBe('Bearer token-a')
  })

  it('unwrapSuccess returns data when code===0', async () => {
    const data = await unwrapSuccess({ data: { code: 0, message: '成功', data: { id: 1 } } })
    expect(data).toEqual({ id: 1 })
  })

  it('unwrapSuccess rejects business failure on 2xx with non-zero code', async () => {
    await expect(
      unwrapSuccess({
        status: 200,
        headers: {},
        data: { code: 1004, message: '用户名或密码错误', data: null }
      })
    ).rejects.toMatchObject({ code: 1004, message: '用户名或密码错误' })
  })

  it('unwrapFailure on 401 clears session and redirects', async () => {
    localStorage.setItem('wj_token', 't')
    localStorage.setItem('wj_user', '{}')
    await expect(
      unwrapFailure(
        {
          status: 401,
          headers: { 'x-request-id': 'req-abc' },
          config: { __wjAuthToken: 't' }
        },
        { code: 401, message: '未登录', data: null }
      )
    ).rejects.toMatchObject({
      code: 401,
      requestId: 'req-abc',
      httpStatus: 401
    })
    expect(localStorage.getItem('wj_token')).toBeNull()
    expect(localStorage.getItem('wj_user')).toBeNull()
    expect(window.location.assign).toHaveBeenCalledWith('/')
  })

  it('unwrapFailure rejects a stale 401 without clearing the replacement session', async () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    await expect(
      unwrapFailure(
        {
          status: 401,
          headers: { 'x-request-id': 'stale-request' },
          config: { __wjAuthToken: 'token-a' }
        },
        { code: 401, message: '旧请求未登录', data: null }
      )
    ).rejects.toMatchObject({
      code: 401,
      requestId: 'stale-request',
      httpStatus: 401,
      message: '旧请求未登录'
    })

    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(JSON.parse(localStorage.getItem('wj_user'))).toEqual({ id: 2 })
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unwrapSuccess scopes a body-level 401 to the response request token', async () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    await expect(
      unwrapSuccess({
        status: 200,
        headers: {},
        config: { __wjAuthToken: 'token-a' },
        data: { code: 401, message: '旧请求未登录', data: null }
      })
    ).rejects.toMatchObject({ code: 401, httpStatus: 200 })

    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(localStorage.getItem('wj_user')).not.toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('does not clear a session created after an anonymous request was sent', async () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    await expect(
      unwrapFailure(
        { status: 401, headers: {}, config: { __wjAuthToken: null } },
        { code: 401, message: '匿名请求未登录', data: null }
      )
    ).rejects.toMatchObject({ code: 401, httpStatus: 401 })

    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(localStorage.getItem('wj_user')).not.toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unwrapFailure on non-2xx Result body exposes code/detail/requestId', async () => {
    await expect(
      unwrapFailure(
        { status: 403, headers: { 'x-request-id': 'rid-1' } },
        { code: 403, message: '无访问权限', data: { reason: 'role' } }
      )
    ).rejects.toMatchObject({
      code: 403,
      detail: { reason: 'role' },
      requestId: 'rid-1',
      httpStatus: 403,
      message: '无访问权限'
    })
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unwrapError does not disguise network failures as business errors', async () => {
    await expect(unwrapError({ message: 'Network Error' })).rejects.toMatchObject({
      network: true,
      isNetworkError: true,
      message: 'Network Error'
    })
  })

  it('unwrapError maps HTTP error with Result body via unwrapFailure', async () => {
    localStorage.setItem('wj_token', 't')
    await expect(
      unwrapError({
        config: { __wjAuthToken: 't' },
        response: {
          status: 401,
          headers: { 'x-request-id': 'from-header' },
          data: { code: 401, message: '登录已失效', data: null }
        }
      })
    ).rejects.toMatchObject({
      code: 401,
      requestId: 'from-header',
      httpStatus: 401
    })
    expect(localStorage.getItem('wj_token')).toBeNull()
  })

  it('unwrapError uses error.config to preserve a replacement session on stale Result 401', async () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    await expect(
      unwrapError({
        config: { __wjAuthToken: 'token-a' },
        response: {
          status: 401,
          headers: { 'x-request-id': 'result-401' },
          data: { code: 401, message: '登录已失效', data: null }
        }
      })
    ).rejects.toMatchObject({
      code: 401,
      requestId: 'result-401',
      httpStatus: 401
    })

    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(localStorage.getItem('wj_user')).not.toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unwrapError uses response.config to preserve a replacement session on plain 401', async () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    await expect(
      unwrapError({
        message: 'Request failed',
        response: {
          status: 401,
          headers: {},
          data: 'unauthorized',
          config: { __wjAuthToken: 'token-a' }
        }
      })
    ).rejects.toMatchObject({ httpStatus: 401, network: false })

    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(localStorage.getItem('wj_user')).not.toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('unwrapError without Result body keeps httpStatus and is not network', async () => {
    await expect(
      unwrapError({
        message: 'Request failed',
        response: { status: 502, headers: {}, data: 'bad gateway' }
      })
    ).rejects.toMatchObject({
      httpStatus: 502,
      network: false
    })
  })

  it('clearSessionAndRedirect skips redirect on login page', () => {
    window.location.pathname = '/'
    localStorage.setItem('wj_token', 't')
    clearSessionAndRedirect()
    expect(localStorage.getItem('wj_token')).toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('clearSessionAndRedirect reports a stale token as a no-op', () => {
    localStorage.setItem('wj_token', 'token-b')
    localStorage.setItem('wj_user', JSON.stringify({ id: 2 }))

    expect(clearSessionAndRedirect('token-a')).toBe(false)
    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(localStorage.getItem('wj_user')).not.toBeNull()
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('delayed store cleanup does not erase a session created after synchronous cleanup', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const session = useSessionStore(pinia)
    session.setSession({ token: 'token-a', user: { id: 1 } })

    expect(clearSessionAndRedirect('token-a')).toBe(true)
    session.setSession({ token: 'token-b', user: { id: 2 } })

    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(session.token).toBe('token-b')
    expect(session.user).toEqual({ id: 2 })
    expect(localStorage.getItem('wj_token')).toBe('token-b')
    expect(JSON.parse(localStorage.getItem('wj_user'))).toEqual({ id: 2 })
  })
})
