import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 开店申请「预填草稿」的中转站（v0.28）。
 *
 * <p>为什么用 store 而不是 window 事件：用户点【填入表单】时**可能并不在** `/shop/apply`，
 * 需要先跳转再消费——事件在目标页挂载之前派发会直接丢失。store 让目标页在 `onMounted` 时主动取。</p>
 *
 * <p>采用「消费式」语义（`take()` 取走即清空）：否则用户下次进入申请页时会被又填一遍。</p>
 */
export const useCopilotPrefillStore = defineStore('copilotPrefill', () => {
  /** 待填入的草稿 `{ shopName, description, products }`；null 表示没有待预填内容 */
  const draft = ref(null)

  /** 存入待填入的草稿（覆盖旧值：同一时刻只应有一份） */
  function put(payload) {
    draft.value = payload
  }

  /** 取走并清空 */
  function take() {
    const d = draft.value
    draft.value = null
    return d
  }

  return { draft, put, take }
})
