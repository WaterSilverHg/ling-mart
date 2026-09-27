<template>
  <div>
    <el-empty v-if="shops.length === 0" description="暂无待审核的店铺信息变更" />
    <div v-for="s in shops" :key="s.id" class="app-card">
      <div class="app-head">
        <div>
          <b>{{ s.shopName }}</b>
          <span class="app-desc">当前描述：{{ s.description }}</span>
          <div v-if="s.draftContent" class="draft">待审新信息：{{ parseDraft(s.draftContent) }}</div>
        </div>
        <div class="app-ops">
          <el-button size="small" type="success" @click="reviewShop(s, true)">通过</el-button>
          <el-button size="small" type="danger" @click="reviewShop(s, false)">拒绝</el-button>
        </div>
      </div>
    </div>
    <el-pagination v-if="total > PAGE_SIZE" class="pager" background layout="prev, pager, next" :total="total"
                   :page-size="PAGE_SIZE" :current-page="page" @current-change="onPage" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'

const shops = ref([])

const page = ref(1)
const total = ref(0)
const PAGE_SIZE = 10

function parseDraft(json) {
  try {
    const d = JSON.parse(json)
    return `${d.shopName || ''}｜${d.description || ''}`
  } catch (e) {
    return json
  }
}

async function load() {
  const r = await adminApi.shopList({ reviewStatus: 3, page: page.value, size: PAGE_SIZE })
  shops.value = r.list
  total.value = r.total
}

function onPage(p) {
  page.value = p
  load()
}

async function reviewShop(s, approve) {
  let reason = ''
  if (!approve) {
    try {
      const r = await ElMessageBox.prompt('请输入拒绝原因', '拒绝店铺信息变更', { confirmButtonText: '确认拒绝', inputValidator: (v) => !!v || '请输入原因' })
      reason = r.value
    } catch (e) { return }
  }
  await adminApi.reviewShop(s.id, { approve, rejectReason: reason })
  ElMessage.success(approve ? '已通过，新信息已生效' : '已拒绝，旧信息继续营业')
  load()
}

onMounted(load)
</script>

<style scoped>
.app-card { border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; margin-bottom: 12px; background: #fafafa; }
.app-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.app-desc { display: block; color: #909399; font-size: 13px; margin-top: 4px; }
.draft { margin-top: 6px; color: #e6a23c; font-size: 13px; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
</style>
