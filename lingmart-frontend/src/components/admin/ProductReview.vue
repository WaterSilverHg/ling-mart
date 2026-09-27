<template>
  <div>
    <el-empty v-if="products.length === 0" description="暂无待审核商品" />
    <div v-if="products.length" class="batch-bar">
      <span>已选 {{ selectedProducts.length }} 项</span>
      <el-button size="small" type="success" :disabled="!selectedProducts.length" @click="batchReview(true)">批量通过</el-button>
      <el-button size="small" type="danger" :disabled="!selectedProducts.length" @click="batchReview(false)">批量拒绝</el-button>
    </div>
    <el-table v-if="products.length" :data="products" @selection-change="onSelectionChange">
      <el-table-column type="selection" width="45" />
      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">
          <div class="p-cell">
            <img v-img-fallback :src="row.mainImage || placeholder" class="thumb" />
            <div>
              <div class="p-title">{{ row.title }}</div>
              <div class="p-meta">店铺：{{ row.shopName }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="价格" width="100">
        <template #default="{ row }"><span class="price">¥{{ row.price }}</span></template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag :type="row.reviewStatus === 3 ? 'warning' : 'info'">
            {{ row.reviewStatus === 3 ? '内容重审' : '新商品' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="reviewProduct(row, true)">通过</el-button>
          <el-button size="small" type="danger" @click="reviewProduct(row, false)">拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > PAGE_SIZE" class="pager" background layout="prev, pager, next" :total="total"
                   :page-size="PAGE_SIZE" :current-page="page" @current-change="onPage" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../../api'

const products = ref([])
// 批量审核选中项
const selectedProducts = ref([])
const placeholder = 'https://via.placeholder.com/48x48/eee?text='

const page = ref(1)
const total = ref(0)
const PAGE_SIZE = 10

async function load() {
  // 新商品（0 待审核）与内容重审（3 待重审）各取一页合并展示
  const [pending, reviewing] = await Promise.all([
    adminApi.reviewList({ status: 0, page: page.value, size: PAGE_SIZE }),
    adminApi.reviewList({ status: 3, page: page.value, size: PAGE_SIZE })
  ])
  products.value = [...pending.list, ...reviewing.list]
  total.value = pending.total + reviewing.total
}

function onPage(p) {
  page.value = p
  load()
}

function onSelectionChange(rows) {
  selectedProducts.value = rows
}

async function batchReview(approve) {
  let reason = ''
  if (!approve) {
    try {
      const r = await ElMessageBox.prompt('请输入拒绝原因', '批量拒绝商品', { confirmButtonText: '确认拒绝', inputValidator: (v) => !!v || '请输入原因' })
      reason = r.value
    } catch (e) { return }
  }
  await adminApi.reviewBatch({ spuIds: selectedProducts.value.map(p => p.spuId), approve, rejectReason: reason })
  ElMessage.success(approve ? `已通过 ${selectedProducts.value.length} 个商品` : '已拒绝')
  selectedProducts.value = []
  load()
}

async function reviewProduct(p, approve) {
  let reason = ''
  if (!approve) {
    try {
      const r = await ElMessageBox.prompt('请输入拒绝原因', '拒绝商品', { confirmButtonText: '确认拒绝', inputValidator: (v) => !!v || '请输入原因' })
      reason = r.value
    } catch (e) { return }
  }
  await adminApi.reviewProduct(p.spuId, { approve, rejectReason: reason })
  ElMessage.success(approve ? '已通过，商品已上架' : '已拒绝')
  load()
}

onMounted(load)
</script>

<style scoped>
.p-cell { display: flex; align-items: center; gap: 8px; }
.thumb { width: 48px; height: 48px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.p-title { font-size: 14px; }
.p-meta { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 600; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
.batch-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: #606266; font-size: 13px; }
</style>
