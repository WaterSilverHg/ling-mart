// DOM 操作执行器（安全沙箱）：仅执行 Agent 发起、且用户已在浮窗中点击「确认」后的操作。
// 支持 navigate / click / scroll（fill 预留 v1.1，返回“不支持”避免代填敏感信息）。
// 执行前对目标元素做红色高亮闪烁，让用户明确看到“AI 即将操作什么”。

import { ID_ATTR, ID_PATTERN, INTERACTIVE_SELECTOR } from './usePageContext'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const clickableText = (el) =>
  (el.innerText || el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.textContent || '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 40)

/**
 * 执行一次页面操作。
 * @param {string} action navigate | click | scroll | fill
 * @param {object} args 工具参数
 * @param {import('vue-router').Router} router Vue Router 实例
 * @returns {Promise<{success:boolean, message:string}>}
 */
export async function executeDomAction(action, args = {}, router) {
  try {
    switch (action) {
      case 'navigate':
        return await doNavigate(args, router)
      case 'click':
        return await doClick(args)
      case 'scroll':
        return await doScroll(args)
      case 'fill':
        // 敏感字段由用户自己填写，Agent 不代填（v1.0 不开放）
        return { success: false, message: '自动填表暂未开放，请手动填写后继续' }
      default:
        return { success: false, message: `暂不支持的操作：${action}` }
    }
  } catch (e) {
    return { success: false, message: e?.message || '执行页面操作时发生未知错误' }
  }
}

async function doNavigate(args, router) {
  const route = typeof args.route === 'string' ? args.route.trim() : ''
  if (!route || !route.startsWith('/') || route.startsWith('//')) {
    return { success: false, message: '无效的跳转地址' }
  }
  // 用 router.resolve(route) 而不是 router.resolve({ path: route })：
  // 后者把 route 整个当作**路径**，于是 "/chat?shopId=3" 里的 "?" 成了路径的一部分、匹配不到任何路由，
  // 合法的带参跳转会被误判成「页面不存在」。交给 resolve 解析完整地址即可正确处理 path + query + hash。
  //
  // 注意：本项目有 catch-all 重定向（/:pathMatch(.*)* → /），所以 matched 非空并不等于"这个路由真实存在"——
  // 未知路径会匹配到 catch-all、静默落到首页。此处无法可靠区分，故失败提示仅覆盖格式非法的情况。
  let resolved
  try {
    resolved = router.resolve(route)
  } catch {
    return { success: false, message: `无效的跳转地址：${route}` }
  }
  if (!resolved.matched || resolved.matched.length === 0) {
    return { success: false, message: `页面不存在：${route}` }
  }
  await router.push(route)
  return { success: true, message: `已跳转到页面 ${route}` }
}

/**
 * 点击页面元素。目标解析分三级，越靠后越"尽力而为"，但任何一级都不会退化成"猜一个点"：
 *
 * ① **稳定 ID**（主路径）：采集时打在 DOM 上的 `data-copilot-id`。ID 跟着元素走，
 *    所以列表重排、v-for 重建都不会指错行——这正是 CSS 路径方案做不到的。
 * ② **唯一文本匹配**（降级）：页面刷新后 ID 会重新编号，旧卡片上的 ID 自然失效；
 *    此时按卡上记录的可见文本找**唯一**命中。多命中即刻放弃，不赌。
 * ③ **CSS 选择器**（兼容）：升级前的挂起卡片参数里是 selector，保持原行为。
 */
async function doClick(args) {
  const expected = typeof args.elementText === 'string' ? args.elementText.trim() : ''

  // ③ 兼容分支放最前：升级前落库的挂起卡片只带 selector，走它们熟悉的原路径
  const legacySelector = typeof args.selector === 'string' ? args.selector.trim() : ''
  if (legacySelector) {
    return await clickResolved(findElement(legacySelector), expected, legacySelector)
  }

  // ① 稳定 ID
  const elementId = typeof args.elementId === 'string' ? args.elementId.trim() : ''
  if (ID_PATTERN.test(elementId)) {
    const el = findById(elementId)
    if (el) {
      return await clickResolved(el, expected, elementId)
    }
  }

  // ② 唯一文本匹配（仅当模型给了文本指纹时可用，而它是必填之外的常规字段）
  if (expected) {
    const el = findByUniqueText(expected)
    if (el) {
      return await clickResolved(el, expected, '文本匹配')
    }
  }

  if (elementId || expected) {
    return {
      success: false,
      message: '点击目标已失效（页面可能已刷新或内容已变化），为避免误点已取消，请重新发起操作。'
    }
  }
  return { success: false, message: '缺少点击目标（elementId）' }
}

/**
 * 对已解析出的元素做指纹校验后执行点击。
 *
 * 指纹校验不能因为"有 ID 定位"就省掉：ID 保证点的是**同一个节点**，但不保证它**内容没变**。
 * 例如某行数据被替换后节点复用，用户确认的是"标题为 A 的那件商品"，此刻它已显示 B——
 * 那正是最典型的误点。
 */
async function clickResolved(el, expected, source) {
  if (!el) {
    return { success: false, message: '找不到要点击的元素，页面可能已变化，请重试' }
  }
  const actual = clickableText(el)
  if (expected && !textMatches(actual, expected)) {
    return {
      success: false,
      message: '点击目标已变化（页面可能已刷新或列表已更新），为避免误点已取消，请重新发起操作。'
    }
  }
  await highlight(el, 480)
  el.click()
  await sleep(220)
  return { success: true, message: `已点击「${actual || source}」` }
}

/** 归一化文本：压缩空白并小写，便于比对。 */
function normText(s) {
  return (s || '').replace(/\s+/g, ' ').trim().toLowerCase()
}

/** 指纹匹配：等值即可；对可能带角标/前后缀的文本，允许较长的一侧包含较短一侧。 */
function textMatches(actual, expected) {
  const a = normText(actual)
  const e = normText(expected)
  if (!a || !e) {
    return false
  }
  if (a === e) {
    return true
  }
  if (a.length >= 4 && a.includes(e)) {
    return true
  }
  if (e.length >= 4 && e.includes(a)) {
    return true
  }
  return false
}

/** 供前端在“确认卡片渲染瞬间”读取目标文本，作为该操作的 DOM 指纹；确认执行时再比对防变化。 */
export function probeElementText(elementId) {
  const el = ID_PATTERN.test(elementId || '') ? findById(elementId) : null
  return el ? clickableText(el) : ''
}

async function doScroll(args) {
  const target = typeof args.target === 'string' ? args.target : ''
  if (target === 'top') {
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return { success: true, message: '已滚动到页面顶部' }
  }
  if (target === 'bottom') {
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
    return { success: true, message: '已滚动到页面底部' }
  }
  const selector = typeof args.selector === 'string' ? args.selector : target
  const el = selector ? findElement(selector) : null
  if (!el) {
    return { success: false, message: '找不到要滚动到的元素' }
  }
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  return { success: true, message: '已滚动到目标位置' }
}

/** 按稳定 ID 找元素。ID 由采集侧写入 DOM，格式受 ID_PATTERN 约束，不含可注入字符。 */
function findById(elementId) {
  try {
    return document.querySelector(`[${ID_ATTR}="${elementId}"]`)
  } catch {
    return null
  }
}

/**
 * 按可见文本在页面里找**唯一**匹配的可交互元素。
 *
 * 只认唯一命中：两个及以上候选时无从判断用户当初指的是哪一个，
 * 此时宁可失败让用户重发，也不能赌一个——赌错就是替用户点了别的按钮。
 *
 * @returns {Element|null} 唯一命中返回该元素；零命中或命中多个都返回 null
 */
function findByUniqueText(expected) {
  let nodes = []
  try {
    nodes = document.querySelectorAll(INTERACTIVE_SELECTOR)
  } catch {
    return null
  }
  let hit = null
  for (const el of nodes) {
    if (el.disabled) {
      continue
    }
    if (!textMatches(clickableText(el), expected)) {
      continue
    }
    if (hit) {
      return null // 出现第二个候选：放弃
    }
    hit = el
  }
  return hit
}

function findElement(selector) {
  try {
    return document.querySelector(selector)
  } catch {
    return null // 选择器非法
  }
}

/** 目标高亮闪烁（红色脉冲描边），随后移除。 */
async function highlight(el, durationMs) {
  el.classList.add('copilot-target')
  try {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  } catch {
    // 忽略滚动异常
  }
  await sleep(durationMs)
  el.classList.remove('copilot-target')
}

export { sleep }
