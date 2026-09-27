<template>
  <div class="card">
    <h3 class="page-title">确认订单</h3>
    <el-table :data="items" v-if="items.length">
      <el-table-column label="商品" min-width="240">
        <template #default="{ row }">
          <div class="goods-cell">
            <img v-img-fallback :src="row.skuImage || placeholder" class="thumb" />
            <div>
              <div class="spu">{{ row.spuTitle }}</div>
              <div class="sku">{{ row.skuTitle }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="单价" width="120">
        <template #default="{ row }">¥{{ row.price }}</template>
      </el-table-column>
      <el-table-column label="数量" width="100">
        <template #default="{ row }">{{ row.num }}</template>
      </el-table-column>
      <el-table-column label="小计" width="120">
        <template #default="{ row }"><span class="price">¥{{ (row.price * row.num).toFixed(2) }}</span></template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="没有待结算的商品" />

    <div class="summary" v-if="items.length">
      <el-input v-model="remark" placeholder="买家备注（可选）" style="width: 320px" />
      <span class="total">应付：<b class="price">¥{{ total.toFixed(2) }}</b></span>
      <el-button type="danger" size="large" :loading="submitting" @click="submit">提交订单</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi, productApi } from '../api'

const route = useRoute()
const router = useRouter()
const items = ref([])
const remark = ref('')
const submitting = ref(false)
const placeholder = 'https://via.placeholder.com/60x60/eee?text='
// 幂等键：一次结算会话一个；提交成功后作废。超时/失败重试复用同一键 → 后端不会重复建单
let idemKey = crypto.randomUUID()

const total = computed(() => items.value.reduce((s, i) => s + i.price * i.num, 0))

async function submit() {
  if (!items.value.length) return
  submitting.value = true
  try {
    const dto = { items: items.value.map(i => ({ skuId: i.skuId, num: i.num })), buyerRemark: remark.value }
    const orderSn = await orderApi.create(dto, idemKey)
    idemKey = crypto.randomUUID() // 成功后作废旧键
    ElMessage.success('订单创建成功，进行模拟支付')
    await payConfirm(orderSn)
  } finally {
    submitting.value = false
  }
}

async function payConfirm(orderSn) {
  try {
    await ElMessageBox.confirm('模拟支付：确认支付该订单吗？', '支付', {
      confirmButtonText: '确认支付',
      cancelButtonText: '暂不支付',
      type: 'warning'
    })
    await orderApi.pay(orderSn)
    ElMessage.success('支付成功')
    router.push('/orders')
  } catch (e) {
    // 用户选择暂不支付 → 订单保留待支付状态（等待超时自动取消）
    ElMessage.info('已跳过支付，订单保持待支付')
    router.push('/orders')
  }
}

onMounted(() => {
  const encoded = route.query.items
  if (!encoded) return
  const raw = JSON.parse(decodeURIComponent(encoded))
  // 商品行来自购物车（完整快照）或立即购买（含服务端快照），仅需规整字段
  items.value = raw.map((it) => ({
    skuId: it.skuId,
    num: it.num,
    spuTitle: it.spuTitle || `商品#${it.skuId}`,
    skuTitle: it.skuTitle || '',
    skuImage: it.skuImage || '',
    price: Number(it.price ?? 0)
  }))
})
</script>

<style scoped>
.goods-cell { display: flex; gap: 10px; align-items: center; }
.thumb { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.spu { font-size: 14px; }
.sku { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 700; }
.summary { display: flex; justify-content: flex-end; align-items: center; gap: 20px; margin-top: 20px; }
.total { font-size: 14px; }
</style>
