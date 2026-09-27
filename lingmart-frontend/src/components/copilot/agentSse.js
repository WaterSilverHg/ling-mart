// Agent SSE 客户端：POST /agent/chat + ReadableStream 逐行解析 SSE 事件。
// 用原生 fetch 流（EventSource 只支持 GET，无法携带请求体/自定义头），无需额外依赖。
//
// 事件协议（与后端 AgentChatController 对齐）：
//   message_start / content_delta / tool_call / tool_run / tool_result / message_end / error
//
// tool_call   = 待用户确认的写操作（渲染成确认卡片）
// tool_run    = 只读工具开始由服务端执行（"正在查询…"）
// tool_result = 只读工具执行完毕（结果卡片，带 dataAsOf 数据时间戳）
import { clearLocalAuth } from '../../utils/authToken'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:88/api'

/**
 * 发起一次 Agent 流式对话。
 *
 * @param {object} payload 请求体 { message, sessionId, context, toolResults }
 * @param {object} handlers { onStart(data), onDelta(delta), onToolCall(tool),
 *                            onToolRun(data), onToolResult(data), onEnd(data), onError(msg) }
 * @param {AbortSignal} [signal] 传入后可用 AbortController 中断（浮窗的「停止」按钮）
 * @returns {Promise<{ok:boolean, aborted?:boolean, message?:string}>} 是否正常结束 / 是否被用户中断
 */
export async function postAgentChat(payload, handlers, signal) {
  const token = localStorage.getItem('token')
  let resp
  try {
    resp = await fetch(`${BASE_URL}/agent/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(payload),
      signal
    })
  } catch (e) {
    if (e.name === 'AbortError') {
      return { ok: false, aborted: true }
    }
    handlers.onError?.('网络异常，无法连接助手，请稍后再试')
    return { ok: false }
  }

  if (!resp.ok) {
    // 登录失效：清凭证并回登录（SSE 走原生 fetch，须自己处理，无法复用 axios 拦截器）
    if (resp.status === 401 || resp.status === 403) {
      clearLocalAuth()
      handlers.onError?.('登录已过期，请重新登录')
      if (window.location.pathname !== '/login') {
        const back = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.assign(`/login?redirect=${back}`)
      }
      return { ok: false }
    }
    // 网关 5xx：尽力读取网关错误信息
    try {
      const err = await resp.json()
      handlers.onError?.(err?.msg || `请求失败（HTTP ${resp.status}）`)
    } catch {
      handlers.onError?.(`请求失败（HTTP ${resp.status}）`)
    }
    return { ok: false }
  }

  if (!resp.body) {
    handlers.onError?.('浏览器不支持流式响应')
    return { ok: false }
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let currentEvent = ''
  let finishedByMessageEnd = false

  const handleLine = (line) => {
    const l = line.trimEnd()
    if (l === '') {
      return
    }
    if (l.startsWith('event:')) {
      currentEvent = l.slice(6).trim()
      return
    }
    if (l.startsWith('data:')) {
      const data = l.slice(5).trim()
      if (!data) {
        return
      }
      let json
      try {
        json = JSON.parse(data)
      } catch {
        // 非法 JSON 忽略该行
        return
      }
      dispatch(currentEvent || 'message', json)
      return
    }
    // 其余为注释/心跳，忽略
  }

  const dispatch = (event, data) => {
    switch (event) {
      case 'message_start':
        handlers.onStart?.(data)
        break
      case 'content_delta':
        handlers.onDelta?.(data?.delta ?? '')
        break
      case 'tool_call':
        handlers.onToolCall?.(data)
        break
      case 'tool_run':
        handlers.onToolRun?.(data)
        break
      case 'tool_result':
        handlers.onToolResult?.(data)
        break
      case 'message_end':
        finishedByMessageEnd = true
        handlers.onEnd?.(data)
        break
      case 'error':
        handlers.onError?.(data?.message || '助手出了点问题，请重试')
        break
      default:
        // 未知事件：忽略
        break
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      // 按行切分，保留最后一个可能不完整的行
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        handleLine(line.replace(/\r$/, ''))
      }
    }
    // 处理末尾残余（一般无完整事件）
    if (buffer.trim()) {
      handleLine(buffer.replace(/\r$/, ''))
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      // 用户点了「停止」。必须带上 aborted：stream() 据此把仍在"进行中"的查询卡片收尾，
      // 否则它们会永远显示"正在查询…"转圈下去。
      //
      // 这个 catch 覆盖的是**流中途**中断（响应头已到、fetch 已 resolve），
      // 也就是绝大多数"停止"——服务端一发 message_start，用户再点停止就走这里。
      // 上面那个 fetch 阶段的 catch 只在还没连上时才会命中。
      return { ok: false, aborted: true }
    }
    handlers.onError?.('接收响应中断，请重试')
    return { ok: false }
  } finally {
    reader.releaseLock?.()
  }

  return { ok: finishedByMessageEnd }
}
