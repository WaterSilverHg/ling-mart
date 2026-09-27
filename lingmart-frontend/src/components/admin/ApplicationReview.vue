<template>
  <div>
    <el-empty v-if="applications.length === 0" description="暂无待审核的入驻申请" />
    <div v-for="a in applications" :key="a.id" class="app-card">
      <div class="app-head">
        <div>
          <b>{{ a.shopName }}</b>
          <span class="app-desc">{{ a.description }}</span>
        </div>
        <div class="app-ops">
          <el-button size="small" type="success" @click="reviewApp(a, true)">通过</el-button>
          <el-button size="small" type="danger" @click="reviewApp(a, false)">拒绝</el-button>
        </div>
      </div>
      <div class="app-products">
        <div v-for="p in productsOf(a.id)" :key="p.spuId" class="p-item">
          <img v-img-fallback :src="p.mainImage || placeholder" class="thumb" />
          <div class="p-info">
            <div class="p-title">{{ p.title }}</div>
            <div class="p-meta">¥{{ p.price }} · {{ reviewText(p.reviewStatus) }}</div>
          </div>
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

const applications = ref([])
// 入驻申请携带的商品（按 applicationId 分组，完整展示不受分页影响）
const appProducts = ref({})
const placeholder = 'https://via.placeholder.com/48x48/eee?text='

const page = ref(1)
const total = ref(0)
const PAGE_SIZE = 10

function reviewText(s) { return { 0: '待审核', 1: '已通过', 2: '已拒绝', 3: '待重审' }[s] || '未知' }
function productsOf(applicationId) { return appProducts.value[applicationId] || [] }

async function load() {
  const r = await adminApi.applicationList({ status: 0, page: page.value, size: PAGE_SIZE })
  applications.value = r.list
  total.value = r.total
  const map = {}
  await Promise.all(r.list.map(async (a) => { map[a.id] = await adminApi.reviewByApplication(a.id) }))
  appProducts.value = map
}

function onPage(p) {
  page.value = p
  load()
}

async function reviewApp(a, approve) {
  let reason = ''
  if (!approve) {
    try {
      const r = await ElMessageBox.prompt('请输入拒绝原因', '拒绝入驻申请', { confirmButtonText: '确认拒绝', inputValidator: (v) => !!v || '请输入原因' })
      reason = r.value
    } catch (e) { return }
  }
  await adminApi.reviewApplication(a.id, { approve, rejectReason: reason })
  ElMessage.success(approve ? '已通过，店铺与商品已生效' : '已拒绝')
  load()
}

onMounted(load)
</script>

<style scoped>
.app-card { border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; margin-bottom: 12px; background: #fafafa; }
.app-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.app-desc { display: block; color: #909399; font-size: 13px; margin-top: 4px; }
.app-products { margin-top: 10px; border-top: 1px dashed #e4e7ed; padding-top: 10px; }
.p-item { display: flex; align-items: center; gap: 10px; padding: 6px 0; }
.thumb { width: 48px; height: 48px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.p-title { font-size: 14px; }
.p-meta { color: #909399; font-size: 12px; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
</style>
