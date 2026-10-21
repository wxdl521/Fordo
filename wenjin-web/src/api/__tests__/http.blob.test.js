import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { http } from '../http.js'

/** 造一个返回/拒绝 Blob 响应体的 axios adapter，走真实的请求/响应拦截器链。 */
function blobResponse(config, status, blob, headers = {}) {
  return { data: blob, status, statusText: String(status), headers, config, request: {} }
}

function succeedWith(blob) {
  return async (config) => blobResponse(config, 200, blob)
}

function failWith(status, blob) {
  return async (config) => {
    throw new AxiosError(
      `Request failed with status code ${status}`,
      'ERR_BAD_REQUEST',
      config,
      {},
      blobResponse(config, status, blob, { 'x-request-id': 'rid-blob' })
    )
  }
}

describe('http with responseType: blob', () => {
  const originalLocation = window.location

  beforeEach(() => {
    localStorage.clear()
    delete window.location
    window.location = { pathname: '/admin/users', assign: vi.fn() }
  })

  afterEach(() => {
    localStorage.clear()
    window.location = originalLocation
  })

  it('passes a successful Blob download through without Result unwrapping', async () => {
    const xlsx = new Blob(['PK-fake-xlsx'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })

    const result = await http.get('/system-admin/users/import-template', {
      responseType: 'blob',
      adapter: succeedWith(xlsx)
    })

    expect(result).toBe(xlsx)
  })

  it('keeps the backend Result message when a blob request fails with a JSON body', async () => {
    const body = new Blob([JSON.stringify({ code: 403, message: '无访问权限', data: null })], {
      type: 'application/json'
    })

    await expect(
      http.get('/system-admin/users/import-template', { responseType: 'blob', adapter: failWith(403, body) })
    ).rejects.toMatchObject({
      code: 403,
      message: '无访问权限',
      httpStatus: 403,
      requestId: 'rid-blob'
    })
    expect(window.location.assign).not.toHaveBeenCalled()
  })

  it('still clears the session on a 401 returned as a Blob', async () => {
    localStorage.setItem('wj_token', 'tok')
    localStorage.setItem('wj_user', JSON.stringify({ id: 1, role: 3 }))
    const body = new Blob([JSON.stringify({ code: 401, message: '登录已失效', data: null })], {
      type: 'application/json'
    })

    await expect(
      http.get('/system-admin/users/import-template', { responseType: 'blob', adapter: failWith(401, body) })
    ).rejects.toMatchObject({ code: 401, message: '登录已失效', httpStatus: 401 })

    expect(localStorage.getItem('wj_token')).toBeNull()
    expect(localStorage.getItem('wj_user')).toBeNull()
    expect(window.location.assign).toHaveBeenCalledWith('/')
  })

  it('falls back to a generic error when the failed blob is not JSON', async () => {
    const body = new Blob(['<html>Bad Gateway</html>'], { type: 'text/html' })

    await expect(
      http.get('/system-admin/users/import-template', { responseType: 'blob', adapter: failWith(502, body) })
    ).rejects.toMatchObject({
      httpStatus: 502,
      network: false,
      message: 'Request failed with status code 502'
    })
  })
})
