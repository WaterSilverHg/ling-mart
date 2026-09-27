import { describe, it, expect, beforeEach, vi } from 'vitest'

// ---- 模块级 mock（在 import request 之前生效）----
// 1. axios：用假实例捕获 request 拦截器与 response 拦截器的回调
const mocks = vi.hoisted(() => {
  const instance = {
    defaults: {},
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() }
    }
  }
  return { instance, axiosCreate: vi.fn(() => instance) }
})
vi.mock('axios', () => ({ default: { create: mocks.axiosCreate } }))

// 2. element-plus：只保留 ElMessage（拦截器里用于弹错）
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

// 3. router：避免引入真实路由实例，单独捕获 push 调用
const { routerPush } = vi.hoisted(() => ({ routerPush: vi.fn() }))
vi.mock('../router', () => ({
  default: {
    currentRoute: { value: { path: '/', fullPath: '/' } },
    push: routerPush
  }
}))

import request from '../api/request'
import { ElMessage } from 'element-plus'

// 取到注册在 axios 实例上的拦截器回调
const reqHandler = mocks.instance.interceptors.request.use.mock.calls[0][0]
const resOk = mocks.instance.interceptors.response.use.mock.calls[0][0]
const resErr = mocks.instance.interceptors.response.use.mock.calls[0][1]

describe('request 实例（网关统一请求）', () => {
  beforeEach(() => {
    localStorage.clear()
    // 注意：不能 clearAllMocks —— axios.create 的调用发生在模块加载期，
    // 清空后「baseURL 断言」会失效；各用例内自行 reset 需要的 mock
  })

  it('axios.create 使用网关地址作为 baseURL', () => {
    expect(mocks.axiosCreate).toHaveBeenCalledWith(
      expect.objectContaining({ baseURL: 'http://localhost:88/api' })
    )
  })

  describe('请求拦截器', () => {
    it('本地存在 token 时注入 Authorization: Bearer', () => {
      localStorage.setItem('token', 'jwt-token')
      const config = reqHandler({ headers: {} })
      expect(config.headers.Authorization).toBe('Bearer jwt-token')
    })

    it('无 token 时不注入 Authorization', () => {
      const config = reqHandler({ headers: {} })
      expect(config.headers.Authorization).toBeUndefined()
    })
  })

  describe('响应拦截器（业务码 200 = 成功）', () => {
    it('code=200 时解包返回 data', () => {
      const res = resOk({ data: { code: 200, msg: 'ok', data: { id: 1 } }, config: {} })
      expect(res).toEqual({ id: 1 })
    })

    it('blob 下载响应直接原样返回（订单导出 xlsx）', () => {
      const res = resOk({ data: new Blob(['x']), config: { responseType: 'blob' } })
      expect(res.data instanceof Blob).toBe(true)
    })

    it('业务码非 200：弹错并 reject', async () => {
      const p = resOk({ data: { code: 500, msg: '库存不足', data: null }, config: {} })
      await expect(p).rejects.toThrow('库存不足')
      expect(ElMessage.error).toHaveBeenCalledWith('库存不足')
    })

    it('业务码 401：清除凭证 + 跳登录页 + 弹错', async () => {
      const p = resOk({ data: { code: 401, msg: '登录已过期，请重新登录', data: null }, config: {} })
      await expect(p).rejects.toThrow('登录已过期，请重新登录')
      expect(localStorage.getItem('token')).toBeNull()
      expect(routerPush).toHaveBeenCalledWith(expect.objectContaining({ path: '/login' }))
      expect(ElMessage.error).toHaveBeenCalled()
    })
  })

  describe('响应拦截器（HTTP 错误分支）', () => {
    it('HTTP 401：清除凭证 + 跳登录页（axios error 分支）', async () => {
      const err = {
        response: { status: 401, data: { msg: 'token 无效' } },
        config: {}
      }
      await expect(resErr(err)).rejects.toBe(err)
      expect(localStorage.getItem('token')).toBeNull()
      expect(routerPush).toHaveBeenCalled()
    })

    it('普通网络错误：弹错误消息并 reject', async () => {
      const err = { response: { status: 500, data: { msg: '服务内部错误' } }, config: {} }
      await expect(resErr(err)).rejects.toBe(err)
      expect(ElMessage.error).toHaveBeenCalledWith('服务内部错误')
    })
  })
})
