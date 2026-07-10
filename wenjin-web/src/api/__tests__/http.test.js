import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  unwrapSuccess,
  unwrapFailure,
  unwrapError,
  clearSessionAndRedirect
} from '../http.js'

describe('http Result unwrap', () => {
  const originalLocation = window.location

  beforeEach(() => {
    localStorage.clear()
    // happy-dom: allow reassign pathname checks via stub
    delete window.location
    window.location = { pathname: '/map', assign: vi.fn() }
  })

  afterEach(() => {
    localStorage.clear()
    window.location = originalLocation
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
        { status: 401, headers: { 'x-request-id': 'req-abc' } },
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
})
