<template>
  <div class="card chat-page">
    <div class="chat-layout">
      <!-- 会话列表（预览只显示对方头像/昵称，不显示手机号） -->
      <div class="conv-list">
        <div class="list-head">消息</div>
        <el-empty v-if="!listLoading && conversations.length === 0" description="暂无会话" :image-size="60" />
        <div v-for="c in conversations" :key="c.conversationId" class="conv-item"
             :class="{ active: currentId === c.conversationId }" @click="openFromList(c)">
          <el-avatar :size="40" :src="c.peer?.avatar || undefined" />
          <div class="conv-info">
            <div class="conv-name">{{ c.peer?.nickname || '用户' + c.peer?.userId }}</div>
            <div class="conv-sub">{{ c.shopName }} · {{ c.lastMessage || '暂无消息' }}</div>
          </div>
        </div>
      </div>

      <!-- 聊天窗口（会话内明文展示对方头像/昵称/手机号，手机号可一键复制） -->
      <div class="chat-window">
        <template v-if="currentConv">
          <div class="chat-head">
            <el-avatar :size="36" :src="currentConv.peer?.avatar || undefined" />
            <div class="head-info">
              <div class="head-name">{{ currentConv.peer?.nickname || '用户' + currentConv.peer?.userId }}</div>
              <div class="head-phone">
                <span>手机号：{{ currentConv.peer?.phone || '—' }}</span>
                <el-button v-if="currentConv.peer?.phone" link type="primary" size="small" @click="copyPhone">复制</el-button>
              </div>
            </div>
            <span class="head-shop">{{ currentConv.shopName }}</span>
          </div>

          <div v-if="msgHasMore" class="load-more">
            <el-button size="small" link type="primary" :loading="loadingMore" @click="loadMore">加载更早的消息</el-button>
          </div>
          <div ref="msgBox" class="msg-list">
            <div v-for="m in messages" :key="m.id || m.clientMsgId" class="msg-row" :class="{ mine: isMine(m) }">
              <el-avatar :size="28" :src="m.senderAvatar || undefined" />
              <div class="bubble-wrap">
                <div class="bubble" :class="{ pending: m.status === SENDING, failed: m.status === FAILED }">
                  {{ m.content }}
                </div>
                <div v-if="m.status === FAILED" class="send-fail">
                  <span>发送失败</span>
                  <el-button link type="danger" size="small" @click="resend(m)">重发</el-button>
                </div>
              </div>
            </div>
          </div>

          <div class="chat-input">
            <el-input v-model="input" type="textarea" :rows="2" placeholder="输入消息，Enter 发送，Shift+Enter 换行"
                      @keydown.enter.exact.prevent="send" />
            <el-button type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
          </div>
        </template>
        <el-empty v-else description="选择左侧会话，或从店铺主页「联系老板」发起聊天" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Client } from '@stomp/stompjs'
import { chatApi } from '../api'
import { useAuthStore } from '../store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/** 乐观气泡状态：发送中（等待服务端确认）/ 发送失败（可重发） */
const SENDING = 'sending'
const FAILED = 'failed'
/** 增量补拉单次条数：返回达到该值说明可能被截断，降级为全量重载以避免中间留空洞 */
const SINCE_LIMIT = 100

const conversations = ref([])
const listLoading = ref(false)
const messages = ref([])
const input = ref('')
const currentConv = ref(null)
const currentId = ref(null)
const msgBox = ref(null)
// 消息历史分页：每页 50 条，「加载更早」翻页
const msgPage = ref(1)
const msgHasMore = ref(false)
const msgTotal = ref(0)
const loadingMore = ref(false)

let client = null
let subscription = null
/** 发送失败回执订阅（/user/queue/errors） */
let errorSubscription = null
// 是否曾建立过连接：用于区分「CONNECT 被拒绝（认证失败）」与「已连接后的错误」
let everConnected = false

function wsUrl() {
  // 聊天 WebSocket 地址：优先取 VITE_WS_URL 环境变量，未配置时回退到当前主机名的网关 88 端口
  return import.meta.env.VITE_WS_URL || `ws://${location.hostname}:88/ws`
}

/** 认证失败（token 过期/无效）：清除凭证并统一跳登录 */
function forceRedirectLogin() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

/** 建立（复用）STOMP 连接；token 通过 STOMP CONNECT 头携带，由聊天服务校验 */
function connectWs() {
  if (client) return
  everConnected = false
  client = new Client({
    brokerURL: wsUrl(),
    connectHeaders: { token: localStorage.getItem('token') || '' },
    reconnectDelay: 5000,
    onConnect: () => {
      everConnected = true
      // 先订阅再补拉：补拉期间到达的实时消息不会丢，重叠部分由 onIncoming 去重
      if (currentId.value) subscribe(currentId.value)
      subscribeSendErrors()
      catchUp(currentId.value)
    },
    onStompError: (frame) => {
      // CONNECT 被拒绝（token 失效）：停止重连 + 统一跳登录，避免用旧 token 无限重试
      if (!everConnected) {
        try { client?.deactivate() } catch (e) { /* ignore */ }
        forceRedirectLogin()
        return
      }
      ElMessage.error(frame.headers?.message || '消息发送失败')
    }
  })
  client.activate()
}

/** 雪花 ID 经后端全局序列化为字符串：一律按字符串比较，禁止 Number()（会丢精度） */
function sameId(a, b) {
  return a != null && b != null && String(a) === String(b)
}

/** 当前登录用户 ID（字符串形式） */
function myUserId() {
  return authStore.user?.id == null ? null : String(authStore.user.id)
}

/** 是否为我发出的消息 */
function isMine(m) {
  return sameId(m.senderId, myUserId())
}

/** 本地最后一条「已确认」消息的 id（乐观气泡无 id，须跳过）；无则返回 null */
function lastConfirmedId() {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (messages.value[i].id != null) return String(messages.value[i].id)
  }
  return null
}

/**
 * 合并一条服务端消息（实时推送或补拉）：
 * 1) 本地有同 clientMsgId 的乐观气泡 → 用服务端消息替换（发送确认 / 失败重发成功）；
 * 2) 已按 id 存在 → 丢弃（实时推送与补拉/历史重叠）；
 * 3) 否则追加并滚动到底部。
 */
function onIncoming(msg) {
  const byClient = msg.clientMsgId == null
    ? -1
    : messages.value.findIndex(m => m.clientMsgId === msg.clientMsgId)
  if (byClient >= 0) {
    messages.value.splice(byClient, 1, { ...msg })
    scrollBottom()
    return
  }
  if (messages.value.some(m => m.id != null && sameId(m.id, msg.id))) return
  messages.value.push(msg)
  scrollBottom()
}

/** 订阅当前会话 topic，收到消息按 onIncoming 合并 */
function subscribe(cid) {
  if (subscription) { try { subscription.unsubscribe() } catch (e) { /* ignore */ } subscription = null }
  if (!client || !client.connected) return
  subscription = client.subscribe(`/topic/conversation/${cid}`, (frame) => {
    try {
      onIncoming(JSON.parse(frame.body))
    } catch (e) { /* 非法帧忽略 */ }
  })
}

/** 订阅发送失败回执：把对应气泡标记为「发送失败」，用户可一键重发（文本不丢） */
function subscribeSendErrors() {
  if (!client || !client.connected) return
  if (errorSubscription) { try { errorSubscription.unsubscribe() } catch (e) { /* ignore */ } errorSubscription = null }
  errorSubscription = client.subscribe('/user/queue/errors', (frame) => {
    let err = {}
    try { err = JSON.parse(frame.body) } catch (e) { /* ignore */ }
    const idx = err.clientMsgId != null
      ? messages.value.findIndex(m => m.clientMsgId === err.clientMsgId)
      : lastPendingIndex()
    if (idx >= 0) messages.value[idx] = { ...messages.value[idx], status: FAILED }
    ElMessage.error(err.msg || '消息发送失败')
  })
}

/** 兜底定位失败气泡：最后一条仍在「发送中」的消息（服务端未回传 clientMsgId 时） */
function lastPendingIndex() {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (messages.value[i].status === SENDING) return i
  }
  return -1
}

/**
 * 重连/首次连接后补拉断线期间漏掉的消息（服务端已持久化，不会丢）。
 * 返回条数达到上限时降级为全量重载，保证中间不留空洞。
 */
async function catchUp(cid) {
  if (!cid) return
  const since = lastConfirmedId()
  try {
    const list = await chatApi.messagesSince(cid, { sinceId: since || undefined, limit: SINCE_LIMIT })
    if (!list || !list.length) return
    if (list.length >= SINCE_LIMIT) {
      await loadMessages(true)
      scrollBottom()
      return
    }
    const box = msgBox.value
    const wasAtBottom = !box || (box.scrollHeight - box.scrollTop - box.clientHeight < 40)
    let added = 0
    for (const m of list) {
      if (messages.value.some(x => x.id != null && sameId(x.id, m.id))) continue
      messages.value.push(m)
      added += 1
    }
    if (added > 0) {
      msgTotal.value += added
      // 用户正在翻历史时不强制拉到底部
      if (wasAtBottom) scrollBottom()
    }
  } catch (e) {
    // 补拉失败不影响实时收发：下次重连或刷新会再试
    console.warn('聊天消息补拉失败', e)
  }
}

/** 发布一条消息到 STOMP（首次发送与重发共用） */
function publishMessage(bubble) {
  bubble.status = SENDING
  client.publish({
    destination: '/app/chat/send',
    body: JSON.stringify({
      conversationId: bubble.conversationId,
      content: bubble.content,
      clientMsgId: bubble.clientMsgId
    })
  })
}

async function openConversation(cid, peer, shopName) {
  currentId.value = cid
  currentConv.value = { conversationId: cid, peer, shopName }
  await loadMessages(true)
  await nextTick()
  scrollBottom()
  subscribe(cid)
}

/** 拉取消息历史：reset=true 从头加载（最新一页），false 加载更早并前置 */
async function loadMessages(reset) {
  const cid = currentId.value
  if (!cid) return
  if (reset) {
    msgPage.value = 1
    messages.value = []
  }
  const r = await chatApi.messages(cid, { page: msgPage.value, size: 50 })
  msgTotal.value = r.total || 0
  // 用「已确认条数」（排除乐观气泡）与总数比较，避免增量合并后页码算术失真
  msgHasMore.value = messages.value.filter(m => m.id != null).length < msgTotal.value
  if (reset) {
    messages.value = r.list || []
  } else {
    // 加载更早：把旧消息放在当前列表前面
    messages.value = [...(r.list || []), ...messages.value]
  }
}

/** 加载更早的消息，并保持当前滚动位置 */
async function loadMore() {
  if (loadingMore.value || !msgHasMore.value || !currentId.value) return
  loadingMore.value = true
  const box = msgBox.value
  const prevHeight = box ? box.scrollHeight : 0
  try {
    msgPage.value += 1
    await loadMessages(false)
    await nextTick()
    // 顶部追加旧消息后，把滚动条下移「新增高度」，视觉上停留在原位置
    if (box) box.scrollTop = box.scrollHeight - prevHeight
  } finally {
    loadingMore.value = false
  }
}

function openFromList(c) {
  openConversation(c.conversationId, c.peer, c.shopName)
}

/** 客户端消息 ID（用于乐观气泡与服务端消息的关联） */
function newClientMsgId() {
  return (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function send() {
  const content = input.value.trim()
  if (!content || !currentId.value) return
  if (!client || !client.connected) {
    ElMessage.warning('连接已断开，正在重连，请稍候')
    return
  }
  // 乐观渲染：先上屏「发送中」气泡，服务端确认后按 clientMsgId 替换为正式消息；
  // 失败则原地标记「发送失败」并支持重发 —— 用户输入不会丢
  const bubble = {
    id: null,
    clientMsgId: newClientMsgId(),
    conversationId: currentId.value,
    senderId: myUserId(),
    content,
    createTime: null,
    status: SENDING
  }
  messages.value.push(bubble)
  scrollBottom()
  input.value = ''
  publishMessage(bubble)
}

/** 失败气泡重发（复用同一条气泡与 clientMsgId） */
function resend(m) {
  if (!client || !client.connected) {
    ElMessage.warning('连接已断开，正在重连，请稍候')
    return
  }
  publishMessage(m)
}

function copyPhone() {
  const phone = currentConv.value?.peer?.phone
  if (!phone) return
  navigator.clipboard?.writeText(phone)
    .then(() => ElMessage.success('手机号已复制'))
    .catch(() => ElMessage.warning('复制失败，请手动长按复制'))
}

function scrollBottom() {
  nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight })
}

async function loadConversations() {
  listLoading.value = true
  try {
    conversations.value = await chatApi.conversations()
  } finally {
    listLoading.value = false
  }
}

/** 初始化：加载会话列表，按 query 打开指定会话（cid=已有会话 / shopId=从店铺主页发起） */
async function init() {
  await loadConversations()
  // 雪花 ID 经后端序列化为字符串：cid 必须按字符串比较（原先 Number() 转换使深链恒不命中）
  const cid = route.query.cid == null ? null : String(route.query.cid)
  const shopId = route.query.shopId
  if (cid) {
    const c = conversations.value.find(x => sameId(x.conversationId, cid))
    if (c) openFromList(c)
  } else if (shopId) {
    try {
      const detail = await chatApi.openConversation(shopId)
      openConversation(detail.conversationId, detail.peer, detail.shopName)
    } catch (e) { /* 拦截器已提示 */ }
  }
  connectWs()
}

// 进入 /chat 或切换 query（如从店铺主页再次发起）时初始化
watch(() => [route.query.cid, route.query.shopId], init, { immediate: true })

onBeforeUnmount(() => {
  if (subscription) { try { subscription.unsubscribe() } catch (e) { /* ignore */ } }
  if (errorSubscription) { try { errorSubscription.unsubscribe() } catch (e) { /* ignore */ } }
  if (client) { try { client.deactivate() } catch (e) { /* ignore */ } }
})
</script>

<style scoped>
.chat-layout { display: flex; gap: 16px; height: 560px; }
.conv-list { width: 280px; border-right: 1px solid #ebeef5; overflow-y: auto; flex-shrink: 0; }
.list-head { font-weight: 600; padding: 8px 4px; color: #303133; }
.conv-item { display: flex; gap: 10px; padding: 10px 8px; border-radius: 6px; cursor: pointer; }
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #ecf5ff; }
.conv-info { flex: 1; min-width: 0; }
.conv-name { font-size: 14px; color: #303133; }
.conv-sub { font-size: 12px; color: #909399; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chat-window { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-head { display: flex; align-items: center; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid #ebeef5; }
.head-info { flex: 1; }
.head-name { font-size: 14px; font-weight: 600; }
.head-phone { font-size: 12px; color: #909399; display: flex; align-items: center; gap: 4px; }
.head-shop { color: #909399; font-size: 12px; }
.load-more { text-align: center; padding: 4px 0 0; }
.msg-list { flex: 1; overflow-y: auto; padding: 12px 4px; }
.msg-row { display: flex; gap: 8px; margin-bottom: 12px; align-items: flex-start; }
.msg-row.mine { flex-direction: row-reverse; }
.bubble-wrap { display: flex; flex-direction: column; align-items: flex-start; max-width: 60%; }
.msg-row.mine .bubble-wrap { align-items: flex-end; }
.bubble { padding: 8px 12px; border-radius: 8px; background: #f0f2f5; font-size: 14px; word-break: break-word; }
.msg-row.mine .bubble { background: #409eff; color: #fff; }
/* 乐观气泡：发送中半透明；发送失败红色并给出重发入口 */
.bubble.pending { opacity: 0.55; }
.msg-row.mine .bubble.pending { opacity: 0.75; }
.bubble.failed, .msg-row.mine .bubble.failed { background: #fef0f0; color: #f56c6c; border: 1px solid #fbc4c4; }
.send-fail { display: flex; align-items: center; gap: 4px; margin-top: 2px; font-size: 12px; color: #f56c6c; }
.chat-input { display: flex; gap: 10px; align-items: flex-end; border-top: 1px solid #ebeef5; padding-top: 10px; }
.chat-input .el-textarea { flex: 1; }
</style>
