// 页面上下文采集：随用户消息上报当前路由 / 标题 / 可见文本 / 可交互元素，
// 让 Agent 理解用户“在哪一页、在做什么”，并为 click_element 提供**稳定的元素 ID**。

const MAX_TEXT = 1500 // 可见文本压缩上限
const MAX_ELEMENTS = 40 // 上报给服务端的可交互元素上限
// 扫描上限：先按文档顺序粗扫到这里，再按优先级取前 MAX_ELEMENTS。
// 必须大于最终配额——否则链接密集的页面会在排序之前就把配额塞满，优先级无从发挥。
const MAX_SCAN = 200

/** 元素 ID 属性名与格式（domExecutor 解析时也用它，故 export 共用一处定义） */
export const ID_ATTR = 'data-copilot-id'
export const ID_PATTERN = /^c\d+$/

/**
 * 可交互元素的采集选择器：**一趟查完**，不按标签分组。
 *
 * 早期实现是 `for (tag of ['a','button','input',...])` 逐个 querySelectorAll，
 * 于是页面上的 `<a>` 会先把采集配额占满——在链接多的页面（首页、店铺列表、商品网格）上
 * 按钮一个都进不来，模型于是"看不见"用户想点的提交/加购按钮。合并成一趟查询就没有优先级偏斜。
 */
export const INTERACTIVE_SELECTOR = 'a, button, input, select, textarea, [role="button"]'

/**
 * 浮窗自身的根标记。采集时必须把自己整棵子树排除掉。
 *
 * 浮窗 `Teleport` 到 `body`，所以它就混在页面里，`document.querySelectorAll` 一定会命中它：
 * 「发送 / 停止 / 清空会话 / 收起」以及卡片上的「确认执行 / 取消」全都是
 * 「表单控件 + 视口内 + 有文本」——按优先级排序后它们会排在**最前面**，
 * 把页面真实按钮挤出送往模型的前 8 个名额（后端只取 8）。更糟的是模型可能反过来下发
 * `click_element` 去点助手自己的按钮（比如「清空会话」），而用户确认后前端会**真的执行**。
 */
const ROOT_ATTR = 'data-copilot-root'

/**
 * 元素 ID 计数器：**单调递增、不复位**。
 *
 * 复位会撞号：路由切换时旧元素虽已随 DOM 卸载，但计数器一旦归零，新页面的第一个元素
 * 又会拿到 c0，而服务端可能还挂着一张指向旧 c0 的卡片。单调递增让 ID 在整次页面加载内唯一，
 * 代价只是一个不可能溢出的整数。整页刷新时 DOM 与计数器一起重置，同样不会撞号。
 */
let idSeq = 0

/**
 * 汇总页面可见文本（去空白压缩）。
 *
 * 只取应用根节点 `#app` 的文本，**不读 `document.body`**：浮窗 Teleport 到 body、位于 `#app` 之外，
 * 读 body 会把助手自己的对话与按钮文案当成"页面数据"喂给模型——1500 字符的预算里
 * 很可能大半是它自己上一轮的回复，等于把自己的输出回灌给自己。
 */
function collectVisibleText() {
  const root = document.getElementById('app') || document.body
  const raw = root ? root.innerText : ''
  return raw.replace(/\s+/g, ' ').trim().slice(0, MAX_TEXT)
}

/**
 * 判断元素是否值得上报；值得则返回采集信息（含内部字段 `el` 与 `priority`，输出前会剥掉）。
 *
 * <p>判定顺序刻意从便宜到昂贵：属性读取 → 尺寸读取 → 计算样式。尺寸为 0 的元素
 * （折叠菜单、隐藏弹层）在这一步就被排除，不必再去触发一次样式解析。</p>
 */
function inspect(el) {
  if (!el || el.disabled) {
    return null
  }
  if (el.getAttribute('aria-hidden') === 'true') {
    return null
  }
  // 排除浮窗自身整棵子树（原因见 ROOT_ATTR 的说明）
  if (el.closest(`[${ROOT_ATTR}]`)) {
    return null
  }
  const rect = el.getBoundingClientRect()
  if (rect.width === 0 && rect.height === 0) {
    return null
  }
  const cs = window.getComputedStyle(el)
  if (cs.visibility === 'hidden' || cs.display === 'none') {
    return null
  }

  const tag = (el.tagName || '').toLowerCase()
  let text = (el.innerText || el.getAttribute('placeholder') || el.getAttribute('aria-label') || '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 60)
  if (tag === 'input' && !text) {
    const type = el.getAttribute('type')
    text = type ? `<${type} 输入框>` : '<输入框>'
  }

  const viewportH = window.innerHeight || 0
  const viewportW = window.innerWidth || 0
  const inViewport =
    rect.bottom > 0 && rect.top < viewportH && rect.right > 0 && rect.left < viewportW

  // 优先级：表单控件 > 视口内 > 有文本。
  // 表单控件（按钮/输入项/或 role=button）才是用户真正想"操作"的对象；
  // 而导航链接几乎每个页面都有一堆，价值低得多——把它们排在后面，提交按钮才进得了前几名。
  const isFormControl =
    tag === 'button' ||
    tag === 'input' ||
    tag === 'select' ||
    tag === 'textarea' ||
    el.getAttribute('role') === 'button'
  let priority = 0
  if (isFormControl) {
    priority += 4
  }
  if (inViewport) {
    priority += 2
  }
  if (text) {
    priority += 1
  }

  return { el, tag, text, priority }
}

/**
 * 为元素分配稳定 ID：元素已带则**复用**，没有才新建。
 *
 * 复用的意义：ID 是模型唯一的定位手段。若每次采集都重新编号，模型两轮前看到的 c7
 * 与新编号对不上，它复述的挂起操作就会指错元素。
 */
function ensureId(el) {
  let id = el.getAttribute(ID_ATTR)
  if (!id) {
    id = `c${++idSeq}`
    el.setAttribute(ID_ATTR, id)
  }
  return id
}

/**
 * 采集当前页可交互元素，按优先级取前 MAX_ELEMENTS 个并分配稳定 ID。
 *
 * <p>注意读取与写入是分开的：所有尺寸/样式读取完成后，才在最后一步写 ID 属性。
 * 交错读写会触发浏览器反复重排（layout thrashing）。</p>
 */
function collectElements() {
  let nodes = []
  try {
    nodes = document.querySelectorAll(INTERACTIVE_SELECTOR)
  } catch {
    return []
  }
  const candidates = []
  const limit = Math.min(nodes.length, MAX_SCAN)
  for (let i = 0; i < limit; i += 1) {
    const info = inspect(nodes[i])
    if (info) {
      candidates.push(info)
    }
  }
  // 现代引擎的 sort 是稳定的：同分保持文档顺序，不会让顺序随实现漂移
  candidates.sort((a, b) => b.priority - a.priority)
  return candidates.slice(0, MAX_ELEMENTS).map((info) => ({
    tag: info.tag,
    text: info.text,
    elementId: ensureId(info.el)
  }))
}

/**
 * 构建页面上报上下文。
 * @param {string} routePath 当前路由 path
 * @returns {{route:string, title:string, visibleText:string, elements:Array}}
 */
export function buildPageContext(routePath) {
  return {
    route: routePath || window.location.pathname + window.location.search,
    title: document.title || '',
    visibleText: collectVisibleText(),
    elements: collectElements()
  }
}

/** 生成会话/消息 ID。 */
export function uid() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return `id-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
