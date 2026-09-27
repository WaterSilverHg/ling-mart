import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 隔离 authApi：登录/资料接口全部 mock，不触真实网络
vi.mock('../api', () => ({
  authApi: {
    login: vi.fn(),
    updateProfile: vi.fn()
  }
}))

import { useAuthStore } from '../store/auth'
import { authApi } from '../api'

/** 构造带用户角色的 localStorage 初始态 */
function seedLogin(role = 'ROLE_USER') {
  localStorage.setItem('token', 'jwt-token')
  localStorage.setItem('user', JSON.stringify({ id: 1, phone: '13800000000', role }))
}

describe('auth store（用户登录态）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('初始状态：无 token 视为未登录', () => {
    const store = useAuthStore()
    expect(store.isLogin).toBe(false)
    expect(store.isSeller).toBe(false)
    expect(store.isAdmin).toBe(false)
  })

  it('初始状态：从 localStorage 恢复已登录用户', () => {
    seedLogin('ROLE_SELLER')
    const store = useAuthStore()
    expect(store.isLogin).toBe(true)
    expect(store.isSeller).toBe(true)
    expect(store.isAdmin).toBe(false)
    expect(store.user.phone).toBe('13800000000')
  })

  it('角色 getter：管理员 / 普通用户 / 卖家互斥正确', () => {
    seedLogin('ROLE_ADMIN')
    const store = useAuthStore()
    expect(store.isAdmin).toBe(true)
    expect(store.isSeller).toBe(false)
  })

  it('login 成功：写入 state + localStorage', async () => {
    authApi.login.mockResolvedValue({
      token: 'jwt-abc',
      user: { id: 1, phone: '13800000000', role: 'ROLE_USER' }
    })
    const store = useAuthStore()
    const user = await store.login('13800000000', '123456')

    expect(authApi.login).toHaveBeenCalledWith({ phone: '13800000000', password: '123456' })
    expect(user.role).toBe('ROLE_USER')
    expect(store.token).toBe('jwt-abc')
    expect(localStorage.getItem('token')).toBe('jwt-abc')
    expect(JSON.parse(localStorage.getItem('user')).id).toBe(1)
  })

  it('login 失败：异常向上抛出，state 不被污染', async () => {
    authApi.login.mockRejectedValue(new Error('账号或密码错误'))
    const store = useAuthStore()
    await expect(store.login('13800000000', 'wrong')).rejects.toThrow('账号或密码错误')
    expect(store.isLogin).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('logout：清空 state + localStorage', () => {
    seedLogin('ROLE_SELLER')
    const store = useAuthStore()
    store.logout()
    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.isLogin).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  it('setUser：仅更新用户信息并同步 localStorage', () => {
    seedLogin('ROLE_USER')
    const store = useAuthStore()
    store.setUser({ id: 1, nickname: '小王', role: 'ROLE_USER' })
    expect(store.user.nickname).toBe('小王')
    expect(JSON.parse(localStorage.getItem('user')).nickname).toBe('小王')
    // token 不受影响
    expect(store.token).toBe('jwt-token')
  })
})
