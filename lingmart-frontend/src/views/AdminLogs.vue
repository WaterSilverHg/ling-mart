<template>
  <div class="card">
    <h3 class="page-title">审计日志</h3>
    <el-empty v-if="!loading && audits.length === 0" description="暂无审核记录" />
    <el-table v-if="audits.length" :data="audits">
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="动作" width="160">
        <template #default="{ row }">
          <el-tag size="small">{{ actionText(row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operatorId" label="操作人ID" width="120" />
      <el-table-column prop="targetId" label="被审对象ID" width="120" />
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">{{ row.result === 1 ? '通过' : '拒绝' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="160" />
    </el-table>
    <el-pagination v-if="total > size" class="pager" background layout="prev, pager, next" :total="total"
                   :page-size="size" :current-page="page" @current-change="onPage" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminApi } from '../api'

const audits = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

function actionText(a) {
  return { APPLY_REVIEW: '入驻审核', SHOP_REVIEW: '店铺信息审核', PRODUCT_REVIEW: '商品审核' }[a] || a
}

function formatTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }

async function load() {
  loading.value = true
  try {
    // 合并 shop 与 product 两个服务的审计日志
    const [shopA, prodA] = await Promise.all([
      adminApi.shopAuditList({ page: page.value, size: size.value }),
      adminApi.productAuditList({ page: page.value, size: size.value })
    ])
    audits.value = [...(shopA.list || []), ...(prodA.list || [])]
      .sort((a, b) => String(b.createTime).localeCompare(String(a.createTime)))
    total.value = (shopA.total || 0) + (prodA.total || 0)
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

onMounted(load)
</script>

<style scoped>
.pager { margin-top: 16px; display: flex; justify-content: center; }
</style>
