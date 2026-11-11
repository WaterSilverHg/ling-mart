<template>
  <div class="ops">
    <!-- ==================== 1. Agent 知识库 ==================== -->
    <section class="card">
      <h3 class="page-title">Agent 知识库</h3>
      <div class="row">
        <el-tag :type="kb.available ? 'success' : 'info'" size="small">
          可用：{{ kb.available ? '是' : '否' }}
        </el-tag>
        <el-tag :type="kb.ready ? 'success' : 'warning'" size="small">
          就绪：{{ kb.ready ? '是' : '否' }}
        </el-tag>
        <el-tag size="small">分块数：{{ kb.chunkCount >= 0 ? kb.chunkCount : '—' }}</el-tag>
        <div class="spacer" />
        <el-button size="small" :loading="kbLoading" @click="loadKbStatus">刷新状态</el-button>
        <el-button size="small" type="primary" :loading="rebuilding" :disabled="!kb.available"
                   @click="rebuildKb">重建知识库</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon class="hint"
                title="首次部署、或改了 kb/ 下的知识文档之后，必须重建一次——否则助手仍按旧知识回答，甚至只靠模型先验。重建会重新向量化全部分块，需要 ChromaDB 可达。" />
    </section>

    <!-- ==================== 2. 召回评估 ==================== -->
    <section class="card">
      <h3 class="page-title">知识库召回评估 <span class="sub">recall@K</span></h3>
      <div class="row">
        <span class="label">Top-K</span>
        <el-input-number v-model="evalTopK" :min="1" :max="20" size="small" controls-position="right" />
        <el-button size="small" type="primary" :loading="evaluating" :disabled="!kb.ready"
                   @click="runEval">运行评估</el-button>
        <span class="muted">不调用大模型；只测“该看到的资料有没有被捞上来”。</span>
      </div>

      <template v-if="evalReport">
        <el-descriptions :column="4" border size="small" class="eval-summary">
          <el-descriptions-item label="recall@K">{{ percent(evalReport.recall) }}</el-descriptions-item>
          <el-descriptions-item label="命中 / 总数">{{ evalReport.hits }} / {{ evalReport.total }}</el-descriptions-item>
          <el-descriptions-item label="一条也没召回">{{ evalReport.emptyCount }}</el-descriptions-item>
          <el-descriptions-item label="本次 Top-K">{{ evalReport.topK }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="evalReport.misses.length">
          <div class="sub-title">未命中（{{ evalReport.misses.length }}）</div>
          <el-table :data="evalReport.misses" size="small" border>
            <el-table-column prop="question" label="问题" min-width="200" />
            <el-table-column label="期望来源" min-width="180">
              <template #default="{ row }">{{ row.expect.join(' / ') }}</template>
            </el-table-column>
            <el-table-column label="实际命中" min-width="180">
              <template #default="{ row }">
                {{ row.retrieved.length ? row.retrieved.join(' / ') : '（空）' }}
              </template>
            </el-table-column>
            <el-table-column label="最高分" width="90">
              <template #default="{ row }">{{ row.topScore == null ? '—' : row.topScore.toFixed(3) }}</template>
            </el-table-column>
          </el-table>
        </template>
        <el-alert v-else type="success" :closable="false" show-icon class="hint"
                  title="全部命中。若仍觉得答得不好，问题更可能在提示词或模型，而不是召回。" />

        <el-collapse class="all-items">
          <el-collapse-item :title="`全部明细（${evalReport.items.length} 题）`" name="all">
            <el-table :data="evalReport.items" size="small" border>
              <el-table-column prop="question" label="问题" min-width="200" />
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.hit ? 'success' : 'danger'" size="small">
                    {{ row.hit ? `命中 #${row.rank}` : '未命中' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="命中来源" min-width="200">
                <template #default="{ row }">{{ row.retrieved.join(' / ') || '（空）' }}</template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </template>
    </section>

    <!-- ==================== 3. Agent 操作审计 ==================== -->
    <section class="card">
      <h3 class="page-title">Agent 操作审计</h3>
      <div class="row wrap">
        <el-input v-model="auditQuery.userId" placeholder="用户 ID" size="small" clearable class="w120" />
        <el-input v-model="auditQuery.sessionId" placeholder="会话 ID" size="small" clearable class="w220" />
        <el-select v-model="auditQuery.eventType" placeholder="事件类型" size="small" clearable class="w180">
          <el-option v-for="e in eventTypes" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
        <el-date-picker v-model="auditRange" type="datetimerange" size="small"
                        value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始时间"
                        end-placeholder="结束时间" class="w360" />
        <el-button size="small" type="primary" :loading="auditLoading" @click="searchAudit">查询</el-button>
        <el-button size="small" @click="resetAudit">重置</el-button>
      </div>

      <el-empty v-if="!auditLoading && audits.length === 0" description="暂无审计记录" />
      <el-table v-else :data="audits" size="small" border>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.ts) }}</template>
        </el-table-column>
        <el-table-column prop="userId" label="用户" width="90" />
        <el-table-column prop="sessionId" label="会话" width="130" show-overflow-tooltip />
        <el-table-column label="事件" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="eventTagType(row.eventType)">{{ eventLabel(row.eventType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工具 / 动作" width="150">
          <template #default="{ row }">{{ row.toolName || row.action || '—' }}</template>
        </el-table-column>
        <el-table-column prop="result" label="结果" width="110" show-overflow-tooltip />
        <el-table-column label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.purpose || row.errorMsg || row.messageExcerpt || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="auditTotal > auditSize" class="pager" background layout="prev, pager, next"
                     :total="auditTotal" :page-size="auditSize" :current-page="auditPage"
                     @current-change="onAuditPage" />
    </section>

    <!-- ==================== 4. 用户角色修正 ==================== -->
    <section class="card">
      <h3 class="page-title">用户角色修正</h3>
      <div class="row">
        <el-input v-model="phone" placeholder="手机号（精确匹配）" size="small" clearable class="w220"
                  @keyup.enter="findUser" />
        <el-button size="small" type="primary" :loading="userLoading" @click="findUser">查询</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon class="hint"
                title="用途：开店审核已通过、但角色提升失败（旁路更新未纳入全局事务）时手工补发 ROLE_SELLER。角色变化在用户下次登录签发的 JWT 中生效。" />
      <template v-if="foundUser">
        <el-descriptions :column="4" border size="small" class="eval-summary">
          <el-descriptions-item label="用户 ID">{{ foundUser.id }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ foundUser.phone }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ foundUser.nickname || '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前角色">{{ foundUser.role }}</el-descriptions-item>
        </el-descriptions>
        <div class="row">
          <span class="label">目标角色</span>
          <el-select v-model="targetRole" size="small" class="w180">
            <el-option label="ROLE_USER（买家）" value="ROLE_USER" />
            <el-option label="ROLE_SELLER（卖家）" value="ROLE_SELLER" />
            <el-option label="ROLE_ADMIN（管理员）" value="ROLE_ADMIN" />
          </el-select>
          <el-button size="small" type="primary" :loading="roleSaving" :disabled="targetRole === foundUser.role"
                     @click="saveRole">保存角色</el-button>
        </div>
      </template>
    </section>

    <!-- ==================== 5. 搜索引擎维护 ==================== -->
    <section class="card">
      <h3 class="page-title">搜索引擎维护</h3>
      <div class="row">
        <span class="label">店铺 ID</span>
        <el-input v-model="reindexShopId" placeholder="shopId" size="small" clearable class="w160" />
        <el-button size="small" type="primary" :loading="reindexing" :disabled="!reindexShopId"
                   @click="reindexShop">重建该店铺 ES 索引</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon class="hint"
                title="人工兜底：店铺改名后 ES 刷新失败、或历史脏数据导致店名/销量与数据库漂移时使用。只重建该店铺“在售”商品的文档。" />
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi, agentAdminApi } from '../api'

// ---------------- 通用 ----------------
function percent(v) {
  return `${((v || 0) * 100).toFixed(1)}%`
}
function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}

// ---------------- 1. 知识库 ----------------
const kb = reactive({ available: false, ready: false, chunkCount: -1 })
const kbLoading = ref(false)
const rebuilding = ref(false)

async function loadKbStatus() {
  kbLoading.value = true
  try {
    const data = await agentAdminApi.kbStatus()
    Object.assign(kb, data)
  } catch (e) {
    // 拦截器已提示
  } finally {
    kbLoading.value = false
  }
}

async function rebuildKb() {
  try {
    await ElMessageBox.confirm(
      '将重新向量化 kb/ 下全部知识文档，耗时取决于文档数量与嵌入服务。期间不影响正在进行的对话。确认执行？',
      '重建知识库',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  rebuilding.value = true
  try {
    const count = await agentAdminApi.kbRebuild()
    ElMessage.success(`知识库重建完成，共 ${count} 个分块`)
    await loadKbStatus()
  } catch (e) {
    // 拦截器已提示
  } finally {
    rebuilding.value = false
  }
}

// ---------------- 2. 召回评估 ----------------
const evalTopK = ref(3)
const evaluating = ref(false)
const evalReport = ref(null)

async function runEval() {
  evaluating.value = true
  try {
    evalReport.value = await agentAdminApi.kbEval(evalTopK.value)
    ElMessage.success(`评估完成：recall@${evalReport.value.topK} = ${percent(evalReport.value.recall)}`)
  } catch (e) {
    evalReport.value = null
  } finally {
    evaluating.value = false
  }
}

// ---------------- 3. 审计 ----------------
const eventTypes = [
  { value: 'tool_proposed', label: '下发操作意图' },
  { value: 'user_confirmed', label: '用户确认' },
  { value: 'user_cancelled', label: '用户取消' },
  { value: 'receipt_rejected', label: '回执被拒' },
  { value: 'turn_completed', label: '一轮结束' },
  { value: 'tool_run', label: '只读工具执行' },
  { value: 'preroute_blocked', label: '前置拦截' },
  { value: 'context_reset', label: '上下文重置' }
]

const auditQuery = reactive({ userId: '', sessionId: '', eventType: '' })
const auditRange = ref(null)
const audits = ref([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditSize = 20
const auditLoading = ref(false)

function eventLabel(v) {
  return eventTypes.find((e) => e.value === v)?.label || v
}
function eventTagType(v) {
  if (v === 'receipt_rejected' || v === 'preroute_blocked') return 'danger'
  if (v === 'context_reset') return 'warning'
  if (v === 'turn_completed' || v === 'tool_run') return 'info'
  return 'success'
}

function auditParams() {
  const params = { page: auditPage.value, size: auditSize }
  if (auditQuery.userId) params.userId = auditQuery.userId
  if (auditQuery.sessionId) params.sessionId = auditQuery.sessionId
  if (auditQuery.eventType) params.eventType = auditQuery.eventType
  if (auditRange.value && auditRange.value.length === 2) {
    params.from = auditRange.value[0]
    params.to = auditRange.value[1]
  }
  return params
}

async function loadAudit() {
  auditLoading.value = true
  try {
    const data = await agentAdminApi.audit(auditParams())
    audits.value = data.list || []
    auditTotal.value = data.total || 0
  } catch (e) {
    audits.value = []
    auditTotal.value = 0
  } finally {
    auditLoading.value = false
  }
}

function searchAudit() {
  // userId 走服务端 Long 转换，非数字会得到一个框架级 400；先在界面拦掉
  if (auditQuery.userId && !/^\d+$/.test(auditQuery.userId.trim())) {
    ElMessage.warning('用户 ID 必须是数字')
    return
  }
  auditPage.value = 1
  loadAudit()
}
function onAuditPage(p) {
  auditPage.value = p
  loadAudit()
}
function resetAudit() {
  auditQuery.userId = ''
  auditQuery.sessionId = ''
  auditQuery.eventType = ''
  auditRange.value = null
  searchAudit()
}

// ---------------- 4. 用户角色 ----------------
const phone = ref('')
const userLoading = ref(false)
const foundUser = ref(null)
const targetRole = ref('')
const roleSaving = ref(false)

async function findUser() {
  if (!phone.value) {
    ElMessage.warning('请先输入手机号')
    return
  }
  userLoading.value = true
  try {
    const user = await adminApi.findUserByPhone(phone.value)
    foundUser.value = user
    targetRole.value = user?.role || ''
    if (!user) ElMessage.warning('未找到该手机号对应的用户')
  } catch (e) {
    foundUser.value = null
  } finally {
    userLoading.value = false
  }
}

async function saveRole() {
  try {
    await ElMessageBox.confirm(
      `确认把用户 ${foundUser.value.phone} 的角色从 ${foundUser.value.role} 改为 ${targetRole.value}？` +
      '该变更在这些用户下次登录签发的 JWT 中生效。',
      '修正角色',
      { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  roleSaving.value = true
  try {
    await adminApi.updateUserRole(foundUser.value.id, targetRole.value)
    ElMessage.success('角色已更新')
    foundUser.value.role = targetRole.value
  } catch (e) {
    // 拦截器已提示
  } finally {
    roleSaving.value = false
  }
}

// ---------------- 5. ES 重建 ----------------
const reindexShopId = ref('')
const reindexing = ref(false)

async function reindexShop() {
  if (!/^\d+$/.test(String(reindexShopId.value).trim())) {
    ElMessage.warning('店铺 ID 必须是数字')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将重建店铺 ${reindexShopId.value} 在售商品的搜索索引。用于店名/销量漂移的人工兜底，确认执行？`,
      '重建店铺索引',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  reindexing.value = true
  try {
    const count = await adminApi.reindexShopEs(reindexShopId.value)
    ElMessage.success(`已重建 ${count} 个商品文档`)
  } catch (e) {
    // 拦截器已提示
  } finally {
    reindexing.value = false
  }
}

onMounted(loadKbStatus)
</script>

<style scoped>
.card { margin-bottom: 16px; }
.row { display: flex; align-items: center; gap: 10px; margin: 8px 0; }
.row.wrap { flex-wrap: wrap; }
.spacer { flex: 1; }
.label { color: #606266; font-size: 13px; }
.muted { color: #909399; font-size: 12px; }
.sub { color: #909399; font-size: 12px; font-weight: 400; margin-left: 6px; }
.sub-title { font-size: 13px; color: #606266; margin: 14px 0 6px; }
.hint { margin-top: 10px; }
.eval-summary { margin: 12px 0; }
.all-items { margin-top: 14px; }
.w120 { width: 120px; }
.w160 { width: 160px; }
.w180 { width: 180px; }
.w220 { width: 220px; }
.w360 { width: 360px; }
.pager { margin-top: 12px; display: flex; justify-content: center; }
</style>
