<template>
  <Teleport to="body">
    <!-- data-copilot-root：采集页面上下文时必须能识别并排除浮窗自身，见 usePageContext.inspect -->
    <div v-if="enabled" class="cp-widget" data-copilot-root
         :style="boxStyle" :class="{ 'cp-open': open }">
      <!-- 悬浮按钮（折叠态）：pointerdown 起拖拽，click 展开（拖拽后不触发展开） -->
      <div v-if="!open" class="cp-fab" title="轻营助手 Copilot"
           @pointerdown="startDrag($event, 44, 44, true)" @click="onFabClick">
        <span class="cp-fab-icon">🤖</span>
        <span v-if="unread > 0" class="cp-badge">{{ unread > 99 ? '99+' : unread }}</span>
      </div>

      <!-- 对话面板（展开态） -->
      <div v-else class="cp-panel">
        <div class="cp-head" @pointerdown.stop="startDrag($event, 400, 620)">
          <div class="cp-head-main">
            <span class="cp-avatar">🤖</span>
            <div class="cp-title">
              <div class="cp-name">轻营助手 Copilot</div>
              <div class="cp-sub">可问答 · 可引导页面操作</div>
            </div>
          </div>
          <div class="cp-head-actions">
            <button class="cp-icon-btn" title="清空会话" @click.stop="confirmClear">
              <span>🗑</span>
            </button>
            <button class="cp-icon-btn" title="收起" @click.stop="open = false">
              <span>—</span>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div ref="listEl" class="cp-body">
          <div v-if="messages.length === 0" class="cp-empty">
            <p>你好，我是轻营助手 👋</p>
            <p class="cp-empty-sub">问我平台怎么用，或说「我要申请开店」「怎么看我的订单」让我带你操作～</p>
          </div>
          <template v-for="m in messages" :key="m.id">
            <div v-if="m.role === 'user'" class="cp-row cp-row-user">
              <div class="cp-bubble cp-user">
                <!-- 附件要留在气泡里：用户回头看不到"这条消息带了文件"的话，
                     就无从理解助手当时为什么会说出一串商品明细 -->
                <div v-if="m.fileName" class="cp-user-file">📎 {{ m.fileName }}</div>{{ m.content }}
              </div>
            </div>
            <div v-else class="cp-row cp-row-ai">
              <div class="cp-bubble cp-ai" :class="{ 'cp-ai-error': m.error }">
                <div class="cp-md">
                  <span v-html="render(m.content)"></span><span v-if="m.streaming" class="cp-cursor"></span>
                </div>
                <div v-if="m.tools && m.tools.length" class="cp-tools">
                  <div v-for="(t, ti) in m.tools" :key="ti" class="cp-tool" :class="`cp-tool-${t.status || 'pending'}`">
                    <div class="cp-tool-head">
                      <span class="cp-tool-act">{{ toolDesc(t) }}</span>
                      <span v-if="t.status === 'running'" class="cp-spin"></span>
                    </div>
                    <!-- 只读查询卡片：无确认按钮（免确认），展示进行中/结果 + 数据时间戳 -->
                    <template v-if="t.readonly">
                      <div class="cp-tool-purpose">{{ t.status === 'running' ? '正在查询…' : t.summary }}</div>
                      <div v-if="t.status !== 'running' && fmtDataTime(t.dataAsOf)" class="cp-tool-time">
                        数据截至 {{ fmtDataTime(t.dataAsOf) }}
                      </div>
                    </template>
                    <template v-else>
                      <div class="cp-tool-purpose">{{ t.purpose }}</div>
                      <!-- 预填卡片：把解析明细摊开给用户看。用户是看着"这几条商品的标题与价格"点确认的，
                           而不是看着一句笼统的"确认执行"——这也是它比通用填表安全的关键一环 -->
                      <div v-if="t.preview" class="cp-prefill">
                        <div v-if="t.preview.shopName" class="cp-prefill-shop">
                          店铺名：<b>{{ t.preview.shopName }}</b>
                        </div>
                        <div class="cp-prefill-count">
                          商品 {{ t.preview.total }} 条<span v-if="t.preview.hidden > 0">（下方仅列前 {{ t.preview.rows.length }} 行）</span>
                        </div>
                        <table class="cp-prefill-table">
                          <thead><tr><th>商品</th><th>规格</th><th>价格</th><th>数量</th></tr></thead>
                          <tbody>
                            <tr v-for="(p, pi) in t.preview.rows" :key="pi">
                              <td>{{ p.title }}</td>
                              <td>{{ p.skuTitle }}</td>
                              <td>{{ p.price }}</td>
                              <td>{{ p.qty }}</td>
                            </tr>
                          </tbody>
                        </table>
                        <div v-if="t.preview.incomplete > 0" class="cp-prefill-warn">
                          ⚠️ 其中 {{ t.preview.incomplete }} 条信息不全（缺标题、规格名或价格），需你补全后才能提交
                        </div>
                      </div>
                      <div v-if="t.status === 'pending'" class="cp-tool-btns">
                        <button class="cp-btn cp-btn-primary" :disabled="sending" @click="confirmTool(t)">
                          {{ t.action === 'prefill' ? '填入表单' : '确认执行' }}
                        </button>
                        <button class="cp-btn" :disabled="sending" @click="cancelTool(t)">取消</button>
                      </div>
                      <div v-else class="cp-tool-result">{{ t.resultMessage }}</div>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- 输入区 -->
        <div class="cp-inputbar">
          <!-- 已选文档：只显示一条附件，正文留在服务端（发送时凭 documentId 引用）。
               早先是把解析结果直接灌进输入框——既淹没了输入框，稍大的清单还会撞上服务端的
               消息长度上限（2000 字）而直接发不出去 -->
          <div v-if="attachment" class="cp-chip" :class="{ 'cp-chip-busy': attachment.uploading }">
            <span class="cp-chip-icon">📎</span>
            <span class="cp-chip-name" :title="attachment.name">{{ attachment.name }}</span>
            <span v-if="attachment.uploading" class="cp-spin"></span>
            <span v-else class="cp-chip-meta">
              {{ attachment.summary }}<template v-if="attachment.truncated">（内容过长已截断，建议分批）</template>
            </span>
            <button class="cp-chip-x" :disabled="sending" title="移除" @click="removeAttachment">✕</button>
          </div>
          <div class="cp-inputrow">
            <button class="cp-attach" :disabled="sending || uploading" title="上传开店材料（CSV / Excel / Word / 文本）"
                    @click="pickFile">📎</button>
            <input ref="fileInput" type="file" accept=".csv,.txt,.md,.xlsx,.docx" class="cp-file-input" @change="onFileChange" />
            <textarea
              v-model="inputText"
              class="cp-input"
              :disabled="sending"
              rows="1"
              placeholder="输入你的问题，Enter 发送"
              @keydown.enter.exact.prevent="send"
            />
            <!-- 发送中显示「停止」：只读查询免确认、由服务端连查数次，一轮可能耗时较长，用户必须能中途叫停 -->
            <button v-if="sending" class="cp-send cp-stop" @click="stopStream">停止</button>
            <button v-else class="cp-send" :disabled="!canSend" @click="send">发送</button>
          </div>
        </div>
        <div class="cp-hint">
          下单 / 支付 / 密码等敏感操作不代填；<b>开店申请</b>可由你上传的清单预填草稿，<b>提交仍需你自己点</b>。
          跳转与点击会先经你确认，查询类会自动执行
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import { useCopilotPrefillStore } from '../../store/copilotPrefill'
import request from '../../api/request'
import { agentDocApi } from '../../api'
import { postAgentChat } from './agentSse'
import { executeDomAction, probeElementText } from './domExecutor'
import { buildPageContext, uid } from './usePageContext'
import { renderMarkdown } from './markdownRender'
import { isTokenExpired, clearLocalAuth } from '../../utils/authToken'

const authStore = useAuthStore()
const prefillStore = useCopilotPrefillStore()
const route = useRoute()
const router = useRouter()

const SESSION_KEY = 'copilot-session-id'
const POS_KEY = 'copilot-widget-pos'

/** 预填卡片里最多列几行明细（多了卡片会过长，用户也没法一次核对） */
const MAX_PREFILL_PREVIEW = 8

// 纯净页（登录/注册）由 App.vue 保证不挂载，这里再加一层兜底
const enabled = computed(() => route.meta.layout !== 'blank')

const open = ref(false)
const unread = ref(0)
const inputText = ref('')
const sending = ref(false)
/** 当前在途请求的 AbortController（「停止」按钮用） */
const abortController = ref(null)
const messages = ref([])
const listEl = ref(null)
const greeted = ref(false)
const historyLoaded = ref(false)

// 会话 ID 必须在 localStorage 里持久化：不写回的话刷新页面就会生成新 ID，
// 于是 GET /history 与 /session/{id}/pending 都打到一个全新 Redis Key 上、永远返回空——
// 「刷新后恢复历史/挂起任务」这个功能会静默失效（此前只有 getItem、没有 setItem）。
// localStorage 里没有时不在这里立刻新建，而是先去问服务端「最近会话」，见 ensureSessionId。
const sessionId = ref(localStorage.getItem(SESSION_KEY) || '')
/**
 * 会话 ID 的解析 Promise（只解析一次）。
 *
 * 用「共享在途 Promise」而不是一个 boolean 标志位：标志位在第一次 await 之前就置位了，
 * 此时 sessionId 还是空的——并发的第二次调用（双击 FAB 就会）会以为已解析完，
 * 拿着空 ID 去打 `/history`。共享 Promise 让后来者等同一个结果。
 */
let sessionPromise = null

/** 确保 sessionId 已确定（多次调用共享同一次解析）。 */
function ensureSessionId() {
  if (!sessionPromise) {
    sessionPromise = resolveSessionId()
  }
  return sessionPromise
}

/**
 * 解析 sessionId：localStorage 里有时直接用，没有时才问服务端。
 *
 * 为什么要问服务端：换设备、清了浏览器数据、无痕窗口都会让 localStorage 为空，
 * 而服务端（Redis，24h TTL）里上次的对话可能还在。接回它比开一个空白会话有用得多。
 * 服务端也没有（第一次使用）才新建，新建后立刻写回 localStorage——与旧行为一致。
 */
async function resolveSessionId() {
  if (sessionId.value) {
    return
  }
  try {
    const latest = await request.get('/agent/session/latest')
    if (latest) {
      sessionId.value = latest
      localStorage.setItem(SESSION_KEY, latest)
      return
    }
  } catch {
    // 取不到就新建：这是可用性增强，不该阻塞对话
  }
  sessionId.value = uid()
  localStorage.setItem(SESSION_KEY, sessionId.value)
}

// 拖拽位置（固定定位时覆盖默认 right/bottom）
const pos = ref(readPos())
const boxStyle = computed(() =>
  pos.value ? { left: `${pos.value.x}px`, top: `${pos.value.y}px`, right: 'auto', bottom: 'auto' } : {}
)

watch(open, (v) => {
  if (v) {
    unread.value = 0
    nextTick(scrollBottom)
  }
})

// 内容或工具卡变化时保持滚到底
watch(
  () => `${messages.value.length}|${lastSig()}`,
  () => {
    if (open.value) nextTick(scrollBottom)
  }
)

function lastSig() {
  const last = messages.value[messages.value.length - 1]
  if (!last) {
    return ''
  }
  return `${last.content?.length || 0}:${last.tools?.length || 0}`
}

function scrollBottom() {
  if (listEl.value) {
    listEl.value.scrollTop = listEl.value.scrollHeight
  }
}

async function openPanel() {
  // 本地 token 过期也要拦（而不是等发消息 401 才跳登录）
  if (authStore.token && isTokenExpired(authStore.token)) {
    clearLocalAuth()
    authStore.logout()
  }
  if (!authStore.isLogin) {
    ElMessage.warning('请先登录后使用智能助手')
    router.push('/login')
    return
  }
  open.value = true
  unread.value = 0
  // 必须先确定 sessionId 再拉历史：否则会拿着空 ID 去打 /history，白跑一趟且永远为空
  await ensureSessionId()
  await loadHistoryOnce()
  if (messages.value.length === 0 && !greeted.value) {
    greeted.value = true
    messages.value.push({
      id: uid(),
      role: 'assistant',
      content: '你好，我是轻营助手 Copilot 🤖\n\n- 问我任何平台使用问题（如“什么是幂等键？”）\n- 或让我带你操作：**“我想申请开店”**、“查看我的订单”',
      tools: [],
      streaming: false,
      error: false
    })
    nextTick(scrollBottom)
  }
}

/** 页面刷新后恢复历史（每个页面生命周期只拉一次，避免重复）。 */
async function loadHistoryOnce() {
  if (historyLoaded.value) {
    return
  }
  historyLoaded.value = true
  try {
    const list = await request.get(`/agent/session/${sessionId.value}/history`)
    if (list && list.length) {
      messages.value = list.map((h) => ({
        id: uid(),
        role: h.role === 'assistant' ? 'assistant' : 'user',
        content: h.content || '',
        tools: [],
        streaming: false,
        error: false
      }))
    }
  } catch {
    // 历史加载失败不阻塞使用
  }
  await attachPending()
}

/** 刷新后恢复“待确认操作”卡片：上次发起但尚未确认/取消的页面操作（挂起-恢复）。 */
async function attachPending() {
  let pending = []
  try {
    pending = (await request.get(`/agent/session/${sessionId.value}/pending`)) || []
  } catch {
    return // 服务端不可用则静默跳过，不阻塞聊天
  }
  if (!pending.length) {
    return
  }
  const tools = pending.map((t) => ({ ...t, status: 'pending', resultMessage: '' }))
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant' && isToolPlaceholder(last.content)) {
    last.tools = tools
  } else {
    messages.value.push({
      id: uid(),
      role: 'assistant',
      content: '上一步有个页面操作还没确认执行，要继续吗？',
      tools,
      streaming: false,
      error: false
    })
  }
  // 注意：恢复出的 click 卡不再打“当前 DOM”指纹——服务器保存的 elementText（若模型给出）才是刷新前的
  // 期望目标；executor 会用它做防误点校验。若没有 elementText 则按旧行为执行（点前仍会高亮给用户看）。
  nextTick(scrollBottom)
}

/** 判断该条历史是否为“发起了待确认操作”的占位消息（后端落盘文本格式见 AgentTurnService.buildToolPlaceholder）。 */
function isToolPlaceholder(content) {
  return !!content && (content.indexOf('（等待你确认）') !== -1 || content.indexOf('我准备执行页面操作') === 0)
}

// ============ 上传开店材料（v0.29） ============

/**
 * 前端侧的文件大小上限，与服务端 `lingmart.agent.document.max-file-size` 一致。
 * 这里只是"快速失败"的体验优化——**权威判定在服务端**，改服务端配置时这里要跟着改。
 */
const MAX_DOC_BYTES = 2 * 1024 * 1024

const fileInput = ref(null)

/** 附件状态：null=无；`{uploading:true, name}`=上传中；否则是服务端返回的元信息（含 documentId） */
const attachment = ref(null)

/** 上传中：此时附件的 documentId 还不存在，发送按钮要禁用 */
const uploading = computed(() => !!attachment.value?.uploading)

/** 可否发送：有文字或有已就绪的附件即可（只传文件不打字也应能发出去） */
const canSend = computed(() => !sending.value && (!!inputText.value.trim() || !!attachment.value?.documentId))

function pickFile() {
  if (sending.value || uploading.value) {
    return
  }
  fileInput.value?.click()
}

function removeAttachment() {
  attachment.value = null
}

/**
 * 选文件 → 上传到 Agent 服务 → 只记下元信息，**不把正文放进输入框**。
 *
 * <p>正文留在服务端，发送时凭 documentId 引用。这样长的清单不会挤占输入框，
 * 也不会因为超过 `max-message-length`（2000 字）而被服务端整轮拒绝。</p>
 *
 * <p>不自动发送：用户应当先看一眼传上去的是什么（文件名 + 解析摘要），确认没传错再发。</p>
 */
async function onFileChange(e) {
  const file = e.target.files && e.target.files[0]
  // 清掉 value，否则连续选同一个文件不会再触发 change
  e.target.value = ''
  if (!file) {
    return
  }
  if (!authStore.isLogin) {
    ElMessage.warning('请先登录后再上传')
    return
  }
  if (file.size > MAX_DOC_BYTES) {
    ElMessage.error(`文件不能超过 ${Math.round(MAX_DOC_BYTES / 1024 / 1024)}MB`)
    return
  }
  // 文档要挂在这个会话下，sessionId 为空服务端会拒绝；它可能需要一次网络往返解析出来
  await ensureSessionId()
  attachment.value = { uploading: true, name: file.name }
  try {
    const info = await agentDocApi.upload(file, sessionId.value)
    attachment.value = info
    ElMessage.success(`已上传「${info.name}」（${info.summary}），点「发送」交给助手`)
  } catch (err) {
    // 失败不保留半成品状态：留着一条"看起来传上去了"的附件比没有更糟
    attachment.value = null
    // 错误提示由 axios 拦截器统一弹出
  }
}

async function send() {
  const typed = inputText.value.trim()
  if (sending.value || uploading.value) {
    return
  }
  const doc = attachment.value?.documentId ? attachment.value : null
  if (!typed && !doc) {
    return
  }
  if (!authStore.isLogin) {
    ElMessage.warning('请先登录')
    return
  }
  // 必须先等 sessionId 确定：localStorage 为空时它要靠 /session/latest 解析（有一次网络往返），
  // 而输入框在 openPanel 里是**立刻可用**的。抢跑的话 sessionId 还是空串，
  // 服务端会自己 UUID.randomUUID() 落盘，而前端永远不知道那个 ID——
  // 这一轮的历史刷新后就丢了；若它下发了确认卡片，刷新后 /pending 也查不到，点它会命中 receipt_rejected。
  await ensureSessionId()
  // 只传了文件、没打字时补一句话：服务端会因为"消息为空"直接拒绝这一轮。
  // 这句话会显示在用户自己的气泡里（不是背着用户发的内容），所以他看得见自己发了什么。
  const text = typed || '请根据我上传的文档帮我预填开店申请'
  inputText.value = ''
  pushMessage('user', text, undefined, false, doc ? doc.name : '')
  // 发送时只带 documentId，正文由服务端从会话里取——消息体因此永远很短
  const ok = await stream(text, [], doc ? doc.documentId : '')
  // 只有这一轮真的跑完才清掉附件：发送失败时留着，用户可以原样重发，不必重新上传
  if (ok && attachment.value === doc) {
    attachment.value = null
  }
}

/**
 * 发起一轮 Agent 流式请求，结果渲染为一条新的 assistant 消息。
 *
 * @returns {Promise<boolean>} 本轮是否正常收到 message_end（发送失败/被中止为 false）
 */
async function stream(text, toolResults, documentId = '') {
  sending.value = true
  const id = uid()
  pushMessage('assistant', '', id, true)
  let ended = false
  let aborted = false
  // 持有 AbortController：只读工具免确认、由服务端自主执行，一轮可能连查几次，
  // 耗时明显变长——用户必须能中途叫停，否则只能干等超时或刷新页面。
  const controller = new AbortController()
  abortController.value = controller
  try {
    const res = await postAgentChat(
      {
        message: text,
        sessionId: sessionId.value,
        context: buildPageContext(route.path),
        toolResults: toolResults || [],
        documentId: documentId || ''
      },
      {
        onDelta: (delta) => appendAssistant(id, delta),
        onToolCall: (tool) => pushTool(id, tool),
        onToolRun: (t) => pushToolRun(id, t),
        onToolResult: (t) => finishToolRun(id, t),
        onEnd: () => {
          ended = true
        },
        onError: (msg) => markAssistantError(id, msg)
      },
      controller.signal
    )
    aborted = !!res?.aborted
  } finally {
    abortController.value = null
    finishAssistant(id, ended, aborted)
    sending.value = false
    if (!open.value) {
      unread.value += 1
    }
    nextTick(scrollBottom)
  }
  // 交给调用方判断"这一轮到底成没成"：send() 据此决定要不要清掉附件
  return ended
}

/** 用户点击「停止」：中断当前 SSE 请求。只读查询无副作用，中断不会留下脏状态。 */
function stopStream() {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
}

/**
 * 追加一条消息。
 *
 * @param {string} fileName 该条用户消息附带的文档名（仅用于在气泡里标出"这条带了文件"）——
 *                         只在本次会话内有效：上传的文档不进会话历史，刷新后拿不到
 */
function pushMessage(role, content, id = uid(), streaming = false, fileName = '') {
  messages.value.push({ id, role, content, tools: [], streaming, error: false, fileName })
  nextTick(scrollBottom)
}

function findAssistant(id) {
  return messages.value.find((m) => m.id === id)
}

function appendAssistant(id, delta) {
  const m = findAssistant(id)
  if (m) {
    m.content += delta
  }
}

/**
 * 把预填草稿摊成卡片上可读的明细（v0.28）。
 *
 * 卡片**必须展示具体内容**：用户是看着"这几条商品的标题与价格"点确认的。
 * 只写一句「确认执行」的话，用户就失去了核对的机会——而那正是这条能力比通用填表安全的地方。
 */
function buildPrefillPreview(args) {
  const draft = (args && args.draft) || {}
  const products = Array.isArray(draft.products) ? draft.products : []
  const rows = []
  let incomplete = 0
  products.forEach((p) => {
    const skus = Array.isArray(p.skus) && p.skus.length ? p.skus : [{}]
    const hasPrice = skus.some((s) => s && s.title && Number(s.price) > 0)
    if (!p.title || !hasPrice) {
      incomplete++
    }
    skus.forEach((s) => {
      rows.push({
        title: p.title || '（缺标题）',
        skuTitle: (s && s.title) || '（缺规格名）',
        price: s && s.price != null && s.price !== '' ? `¥${s.price}` : '（缺价格）',
        qty: s && s.qty != null && s.qty !== '' ? s.qty : '不限量'
      })
    })
  })
  return {
    shopName: draft.shopName || '',
    total: products.length,
    incomplete,
    rows: rows.slice(0, MAX_PREFILL_PREVIEW),
    hidden: Math.max(0, rows.length - MAX_PREFILL_PREVIEW)
  }
}

function pushTool(id, tool) {
  const m = findAssistant(id)
  if (!m) {
    return
  }
  // 兜底防线：服务端已强制每轮最多下发 1 个**待确认写操作**（lingmart.agent.max-tools-per-turn），此处再拦一道。
  // 注意只拦写操作（readonly 的查询卡片可以有多张，那是服务端自主循环的进度展示）。
  if (m.tools.filter((t) => !t.readonly).length >= 1) {
    console.warn('[copilot] 单轮收到多个待确认操作，已忽略多余的：', tool)
    return
  }
  const t = { ...tool, status: 'pending', resultMessage: '' }
  if (t.action === 'prefill') {
    t.preview = buildPrefillPreview(t.arguments)
  }
  m.tools.push(t)
  // 卡片一渲染就为 click 目标打“当前 DOM 指纹”：用户点确认那一瞬的页面与此刻不同即拒点
  // （ID 保证点的是同一个节点，但不保证内容没变，故指纹校验不能省）
  const card = m.tools[m.tools.length - 1]
  if (card.action === 'click' && card.arguments && card.arguments.elementId) {
    nextTick(() => {
      card.fp = probeElementText(card.arguments.elementId)
    })
  }
}

/**
 * 只读工具开始执行（服务端自主查询）：渲染成一张"进行中"卡片。
 * 这类卡片**没有确认/取消按钮**——查询免确认，服务端直接执行，用户只需看到它在做什么。
 */
function pushToolRun(id, data) {
  const m = findAssistant(id)
  if (!m) {
    return
  }
  m.tools.push({
    toolCallId: data.toolCallId,
    name: data.name,
    readonly: true,
    label: data.label || '查询数据',
    status: 'running',
    summary: '',
    dataAsOf: ''
  })
  nextTick(scrollBottom)
}

/** 只读工具执行完毕：把对应卡片从"进行中"更新为结果卡片（含数据时间戳）。 */
function finishToolRun(id, data) {
  const m = findAssistant(id)
  if (!m) {
    return
  }
  const card = m.tools.find((t) => t.readonly && t.toolCallId === data.toolCallId)
  if (!card) {
    console.warn('[copilot] 收到未匹配的结果事件：', data)
    return
  }
  card.status = 'done'
  card.summary = data.summary || '查询完成'
  card.dataAsOf = data.dataAsOf || ''
  nextTick(scrollBottom)
}

/** 把后端返回的 ISO 时间显示成 HH:mm（数据时间戳只需精确到分钟）。 */
function fmtDataTime(iso) {
  if (!iso) {
    return ''
  }
  const m = String(iso).match(/T(\d{2}:\d{2})/)
  return m ? m[1] : ''
}

function finishAssistant(id, ok, aborted = false) {
  const m = findAssistant(id)
  if (!m) {
    return
  }
  m.streaming = false
  if (aborted) {
    // 用户主动停止：把还在"进行中"的查询卡片收尾，否则会永远转圈
    m.tools.forEach((t) => {
      if (t.readonly && t.status === 'running') {
        t.status = 'done'
        t.summary = '已停止'
      }
    })
    if (!m.content.trim() && (!m.tools || m.tools.length === 0)) {
      m.content = '（已停止）'
    }
    return
  }
  if (ok && !m.content.trim() && (!m.tools || m.tools.length === 0)) {
    m.content = '（没有收到回复，请换个问法）'
  }
}

function markAssistantError(id, msg) {
  const m = findAssistant(id)
  if (!m) {
    return
  }
  m.streaming = false
  m.error = true
  m.content = (m.content ? m.content + '\n\n' : '') + `⚠️ ${msg}`
}

/** 用户点击【确认执行】：前端真实执行 DOM 操作，再把回执送回 Agent 续跑。 */
async function confirmTool(tool) {
  if (sending.value) {
    return
  }
  tool.status = 'running'
  let result
  if (tool.action === 'prefill') {
    // 预填**不走 DOM 执行器**：它不写任何页面元素，而是把结构化草稿交给开店申请页自己去填。
    // 页面填完后用户仍可编辑，且**提交必须由用户自己点**——这条边界是这套设计的安全地基。
    result = await applyPrefill(tool)
  } else {
    // 用“渲染卡片瞬间”打的 DOM 指纹做执行校验（优先），无则退回模型给出的 elementText；都不带则按旧行为执行
    const execArgs = { ...(tool.arguments || {}) }
    if (tool.fp) {
      execArgs.elementText = tool.fp
    }
    result = await executeDomAction(tool.action, execArgs, router)
  }
  tool.status = result.success ? 'done' : 'error'
  tool.resultMessage = result.message
  // 导航与预填都可能刚切换路由，等页面就绪再采集上下文续跑
  const wait = result.success && (tool.action === 'navigate' || tool.action === 'prefill') ? 400 : 80
  await new Promise((r) => setTimeout(r, wait))
  // nonce 必须原样回传：服务端据此确认该回执确实对应当前挂起的意图（未命中一律不采信）
  // outcome 显式声明是"确认"：审计要能区分"用户主动取消"与"用户确认但执行失败"——两者都是 success=false
  await stream('', [
    {
      action: tool.action,
      purpose: tool.purpose,
      success: result.success,
      message: result.message,
      nonce: tool.nonce || '',
      outcome: 'confirm'
    }
  ])
}

/**
 * 把预填草稿交给开店申请页（v0.28）。
 *
 * <p>用 store 中转而不是派发 window 事件：用户点【填入表单】时可能并不在 `/shop/apply`，
 * 需要先跳转——事件会在目标页挂载之前丢掉。目标页用 `watch(..., { immediate: true })` 消费，
 * 因此"已经在申请页"与"跳转过去"两种情况都能接住。</p>
 */
async function applyPrefill(tool) {
  const draft = tool.arguments && tool.arguments.draft
  if (!draft || !Array.isArray(draft.products) || draft.products.length === 0) {
    return { success: false, message: '草稿里没有商品条目，无法填入' }
  }
  const alreadyThere = route.path === '/shop/apply'
  prefillStore.put(draft)
  if (!alreadyThere) {
    await router.push('/shop/apply')
  }
  return {
    success: true,
    message: alreadyThere
      ? '已填入申请表单，请核对内容后自己点提交'
      : '已跳转到申请开店页并填入草稿，请核对内容后自己点提交'
  }
}

/** 用户点击【取消】：把拒绝原因回传 Agent。 */
async function cancelTool(tool) {
  if (sending.value) {
    return
  }
  tool.status = 'cancelled'
  tool.resultMessage = '已取消'
  await stream('', [
    {
      action: tool.action,
      purpose: tool.purpose,
      success: false,
      message: '用户拒绝了该操作，请换一种方式或解释原因',
      nonce: tool.nonce || '',
      outcome: 'cancel'
    }
  ])
}

function toolDesc(t) {
  if (t.readonly) {
    return t.label || '查询数据'
  }
  const args = t.arguments || {}
  if (t.action === 'navigate') {
    return `跳转页面 ${args.route || ''}`
  }
  if (t.action === 'click') {
    return '点击页面元素'
  }
  if (t.action === 'prefill') {
    return '预填开店申请'
  }
  return `操作（${t.name || t.action}）`
}

function render(content) {
  return renderMarkdown(content)
}

async function confirmClear() {
  try {
    await ElMessageBox.confirm('确定清空本次会话上下文吗？', '清空会话', { type: 'warning' })
  } catch {
    return
  }
  try {
    await request.delete(`/agent/session/${sessionId.value}`)
  } catch {
    // 服务端清理失败不阻断本地清空
  }
  messages.value = []
  greeted.value = false
  // 附件一并清掉：服务端在清空会话时已删除那份文档，留着附件条会指向一个不存在的 documentId
  // （发送后表现为"助手看不到材料"，且没有任何提示）
  attachment.value = null
  nextTick(scrollBottom)
}

// ============ 拖拽 ============
function readPos() {
  try {
    const raw = localStorage.getItem(POS_KEY)
    if (raw) {
      const p = JSON.parse(raw)
      if (typeof p.x === 'number' && typeof p.y === 'number') {
        return p
      }
    }
  } catch {
    // 忽略损坏数据
  }
  return null
}

/** 拖拽是否发生了实际位移（>5px），用于区分“点击”与“拖动” */
let dragMoved = false

/**
 * 开始拖拽。
 *
 * @param {PointerEvent} e
 * @param {number} w 面板宽度（用于边界收敛）
 * @param {number} h 面板高度
 * @param {boolean} trackClick 是否记录“拖过”标记供随后的 click 判定（**只有悬浮球需要**）
 */
function startDrag(e, w, h, trackClick = false) {
  if (e.pointerType === 'touch') {
    return // 触屏不拖拽（移动端为全屏面板）
  }
  // 面板内的按钮不参与拖拽：只要在这里 setPointerCapture，浏览器就会把随后的 click
  // 改派到「pointerdown 目标」与「捕获元素」的最近公共祖先（对头部按钮而言即 .cp-head），
  // 按钮自己的 click 监听器再也收不到——表现就是「点收起/清空没反应」，加 @click.stop 也救不了
  // （事件根本没派发到按钮）。在这里统一拦，而不是给每个按钮加 @pointerdown.stop，
  // 以后往头部新增按钮会自动被覆盖。
  if (e.target?.closest?.('button')) {
    return
  }
  // 只有悬浮球需要在拖拽后抑制随后的 click（“拖动不展开”）。
  // 面板头部没有 click 语义，绝不能写这个标记：否则拖过面板头部、再收起浮窗之后，
  // 第一次点悬浮球会被当成“拖拽后的点击”而吞掉，要点两次才展开。
  if (trackClick) {
    dragMoved = false
  }
  const startX = e.clientX
  const startY = e.clientY
  // 捕获指针：把后续 pointer 事件（含在窗口外松开的 pointerup）都定向到这个元素。
  // 不捕获的话，按住拖到浏览器窗口之外再松手，浏览器不派发 pointerup，
  // 监听器就永远留在 window 上——此后不按任何键移动鼠标，浮窗都会继续跟着光标跑。
  try {
    e.currentTarget.setPointerCapture?.(e.pointerId)
  } catch {
    // 捕获失败不影响拖拽本身，退化为原来的行为
  }
  const base = pos.value || rectOf(e.currentTarget)
  const moved = (ev) => {
    const dx = ev.clientX - startX
    const dy = ev.clientY - startY
    if (trackClick && !dragMoved && (Math.abs(dx) > 5 || Math.abs(dy) > 5)) {
      dragMoved = true
    }
    pos.value = {
      x: clamp(base.x + dx, 4, window.innerWidth - w - 4),
      y: clamp(base.y + dy, 4, window.innerHeight - h - 4)
    }
  }
  const up = () => {
    window.removeEventListener('pointermove', moved)
    window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', up)
    try {
      e.currentTarget.releasePointerCapture?.(e.pointerId)
    } catch {
      // 忽略
    }
    try {
      localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
    } catch {
      // 忽略写入失败
    }
  }
  window.addEventListener('pointermove', moved)
  window.addEventListener('pointerup', up)
  // pointercancel：触控笔/手势被系统接管等情况下不会有 pointerup，必须一并收尾
  window.addEventListener('pointercancel', up)
}

/** FAB 点击展开：真正拖动过则不触发展开。 */
function onFabClick() {
  if (dragMoved) {
    dragMoved = false
    return
  }
  openPanel()
}

function rectOf(el) {
  const r = el.getBoundingClientRect()
  return { x: r.left, y: r.top }
}

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, v))
}
</script>

<style>
/* ===== Copilot 浮窗（全局样式，Teleport 到 body 需非 scoped） ===== */
.cp-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 3000;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

.cp-fab {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #337ecc);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.35);
  user-select: none;
  position: relative;
  transition: transform 0.15s ease;
}
.cp-fab:hover {
  transform: scale(1.05);
}
.cp-fab-icon {
  font-size: 26px;
}
.cp-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: #f56c6c;
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

.cp-panel {
  width: 400px;
  max-width: calc(100vw - 24px);
  height: 620px;
  max-height: calc(100vh - 80px);
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e4e7ed;
}

.cp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: linear-gradient(135deg, #ecf5ff, #d9ecff);
  border-bottom: 1px solid #e4e7ed;
  cursor: grab;
  user-select: none;
}
.cp-head-main {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cp-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #409eff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #fff;
}
.cp-name {
  font-weight: 700;
  font-size: 14px;
  color: #303133;
}
.cp-sub {
  font-size: 11px;
  color: #909399;
}
.cp-head-actions {
  display: flex;
  gap: 4px;
}
.cp-icon-btn {
  border: none;
  background: transparent;
  color: #606266;
  font-size: 14px;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: pointer;
  line-height: 1;
}
.cp-icon-btn:hover {
  background: rgba(0, 0, 0, 0.06);
}

.cp-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 12px;
  background: #f7f9fc;
}
.cp-empty {
  text-align: center;
  color: #909399;
  padding: 60px 20px;
}
.cp-empty p {
  margin: 6px 0;
  font-size: 14px;
}
.cp-empty-sub {
  font-size: 12px;
  line-height: 1.7;
}

.cp-row {
  display: flex;
  margin-bottom: 12px;
}
.cp-row-user {
  justify-content: flex-end;
}
.cp-row-ai {
  justify-content: flex-start;
}
.cp-bubble {
  max-width: 86%;
  padding: 9px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
}
.cp-user {
  background: #409eff;
  color: #fff;
  border-top-right-radius: 3px;
  white-space: pre-wrap;
}
/* 气泡里的附件行：让用户回头能看出"这条消息带了文件" */
.cp-user-file {
  font-size: 12px;
  opacity: 0.9;
  margin-bottom: 2px;
  word-break: break-all;
}
.cp-ai {
  background: #fff;
  border: 1px solid #e8ecf1;
  border-top-left-radius: 3px;
  color: #303133;
}
.cp-ai-error {
  border-color: #fde2e2;
  color: #f56c6c;
}
.cp-cursor {
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 2px;
  background: #409eff;
  vertical-align: -2px;
  animation: cp-blink 1s step-end infinite;
}
@keyframes cp-blink {
  50% {
    opacity: 0;
  }
}

/* Markdown 排版 */
.cp-md p {
  margin: 4px 0;
}
.cp-md ul {
  margin: 4px 0;
  padding-left: 20px;
}
.cp-md li {
  margin: 2px 0;
}
.cp-md code {
  background: #f0f2f5;
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 12px;
  font-family: Consolas, Menlo, monospace;
}
.cp-md pre {
  background: #282c34;
  color: #e6e6e6;
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
  font-size: 12px;
  margin: 8px 0;
}
.cp-md pre code {
  background: transparent;
  color: inherit;
  padding: 0;
}
.cp-md a {
  color: #409eff;
}

/* 操作卡片 */
.cp-tools {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cp-tool {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 8px 10px;
  background: #fafbfc;
}
.cp-tool-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cp-tool-act {
  font-weight: 600;
  font-size: 12px;
  color: #303133;
}
.cp-tool-purpose {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
}
.cp-tool-btns {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
/* 预填卡片：把解析明细摊开——用户是看着具体内容点确认的，不是看着一句笼统的「确认执行」 */
.cp-prefill {
  margin-top: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 8px;
  background: #fafbfc;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
}
.cp-prefill-shop { margin-bottom: 4px; }
.cp-prefill-count { color: #909399; margin-bottom: 6px; }
.cp-prefill-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.cp-prefill-table th {
  text-align: left;
  color: #909399;
  font-weight: 500;
  border-bottom: 1px solid #ebeef5;
  padding: 2px 4px;
}
.cp-prefill-table td {
  padding: 2px 4px;
  border-bottom: 1px dashed #f2f6fc;
  word-break: break-all;
}
.cp-prefill-warn { margin-top: 6px; color: #e6a23c; }
.cp-tool-result {
  margin-top: 6px;
  font-size: 12px;
  color: #67c23a;
}
.cp-tool-error .cp-tool-result,
.cp-tool-cancelled .cp-tool-result {
  color: #f56c6c;
}
/* 只读查询卡片的数据时间戳：必须显眼但克制——用户要能判断"这是什么时候的数据" */
.cp-tool-time {
  margin-top: 4px;
  font-size: 11px;
  color: #909399;
}
.cp-btn {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  border-radius: 6px;
  font-size: 12px;
  padding: 4px 12px;
  cursor: pointer;
}
.cp-btn-primary {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
.cp-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.cp-spin {
  width: 12px;
  height: 12px;
  border: 2px solid #409eff;
  border-top-color: transparent;
  border-radius: 50%;
  animation: cp-rotate 0.8s linear infinite;
}
@keyframes cp-rotate {
  to {
    transform: rotate(360deg);
  }
}

/* 输入区：纵向两段——附件条在上、操作行在下 */
.cp-inputbar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
  border-top: 1px solid #e4e7ed;
}
.cp-inputrow {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
/* 已选附件：一条窄条，不该抢输入区的视觉权重（它是"材料已就位"的状态，不是主操作） */
.cp-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #606266;
  background: #f4f7fb;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 4px 8px;
}
.cp-chip-busy {
  opacity: 0.7;
}
.cp-chip-name {
  font-weight: 600;
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cp-chip-meta {
  color: #909399;
  font-size: 11px;
}
.cp-chip-x {
  border: none;
  background: transparent;
  color: #909399;
  font-size: 12px;
  line-height: 1;
  padding: 2px 4px;
  border-radius: 4px;
  cursor: pointer;
}
.cp-chip-x:hover {
  background: rgba(0, 0, 0, 0.06);
  color: #f56c6c;
}
.cp-chip-x:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* 上传清单按钮：与发送按钮同高但弱化——它是辅助入口，不该和主操作抢视觉权重 */
.cp-attach {
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 14px;
  line-height: 1.2;
  cursor: pointer;
}
.cp-attach:hover {
  border-color: #409eff;
}
.cp-attach:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
/* 真正的 file input 藏起来（由上面的按钮代为触发），但必须留在 DOM 里才能 click() */
.cp-file-input {
  display: none;
}
.cp-input {
  flex: 1;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 13px;
  resize: none;
  line-height: 1.5;
  font-family: inherit;
  min-height: 34px;
  max-height: 96px;
}
.cp-input:focus {
  outline: none;
  border-color: #409eff;
}
.cp-send {
  border: none;
  background: #409eff;
  color: #fff;
  border-radius: 8px;
  padding: 7px 16px;
  font-size: 13px;
  cursor: pointer;
}
.cp-send:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
/* 停止按钮：用中性色而非主色，避免和「发送」抢视觉权重——它是撤离路径，不是主操作 */
.cp-stop {
  background: #909399;
}
.cp-hint {
  padding: 0 12px 8px;
  font-size: 11px;
  color: #c0c4cc;
}

/* 目标元素高亮（红框脉冲，前端 DOM 执行器使用） */
.copilot-target {
  outline: 3px solid #f56c6c !important;
  box-shadow: 0 0 0 8px rgba(245, 108, 108, 0.3) !important;
  animation: cp-pulse 0.5s ease-in-out infinite alternate;
}
@keyframes cp-pulse {
  from {
    box-shadow: 0 0 0 4px rgba(245, 108, 108, 0.25);
  }
  to {
    box-shadow: 0 0 0 12px rgba(245, 108, 108, 0.45);
  }
}

/* 移动端全屏 */
@media (max-width: 640px) {
  .cp-widget {
    right: 10px;
    bottom: 10px;
  }
  .cp-panel {
    position: fixed !important;
    left: 0 !important;
    top: 0 !important;
    width: 100vw;
    height: 100dvh;
    max-height: none;
    border-radius: 0;
    border: none;
  }
}
</style>
