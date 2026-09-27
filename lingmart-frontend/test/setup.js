import { afterEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

// 每个用例结束后自动卸载所有已挂载组件，避免 DOM 与状态泄漏
enableAutoUnmount(afterEach)

afterEach(() => {
  localStorage.clear()
})
