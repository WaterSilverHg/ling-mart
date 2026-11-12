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

    <!-- 履约方式：自营实体店以到店自提为主，快递是否可选由店铺决定 -->
    <div class="fulfill" v-if="items.length && fulfillment">
      <div class="row">
        <span class="label">履约方式</span>
        <el-radio-group v-model="deliveryMode">
          <el-radio :value="2">到店自提</el-radio>
          <el-radio :value="1" :disabled="!fulfillment.supportExpress">
            快递配送{{ fulfillment.supportExpress ? '' : '（该店铺不支持）' }}
          </el-radio>
        </el-radio-group>
      </div>

      <!-- 自提：展示去哪儿取货、找谁 -->
      <div v-if="deliveryMode === 2" class="pickup">
        <template v-if="fulfillment.address">
          <div class="pickup-shop">{{ fulfillment.shopName }}</div>
          <div class="pickup-addr">{{ fulfillment.address }}</div>
          <div v-if="fulfillment.contactPhone" class="pickup-phone">门店电话：{{ fulfillment.contactPhone }}</div>
        </template>
        <div v-else class="warn">该店铺尚未配置自提地址，暂时无法下单，请联系卖家补全门店信息</div>
      </div>

      <!-- 快递：才需要收货地址 -->
      <div v-if="deliveryMode === 1" class="addr">
        <span class="label">收货地址</span>
        <template v-if="addresses.length">
          <el-select v-model="selectedAddressId" placeholder="请选择收货地址" style="width: 460px">
            <el-option v-for="a in addresses" :key="a.id"
                       :label="`${a.receiverName} ${a.receiverPhone} ${a.fullAddress}`"
                       :value="a.id" />
          </el-select>
          <el-button link type="primary" @click="$router.push('/addresses')">管理地址</el-button>
        </template>
        <template v-else>
          <span class="warn">还没有收货地址，</span>
          <el-button link type="primary" @click="$router.push('/addresses')">去添加</el-button>
        </template>
      </div>
    </div>

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
import { addressApi, orderApi, shopApi } from '../api'

const route = useRoute()
const router = useRouter()
const items = ref([])
const remark = ref('')
const submitting = ref(false)
const addresses = ref([])
const selectedAddressId = ref(null)
/** 店铺履约能力（能不能快递、自提地址/电话）；加载失败时为 null */
const fulfillment = ref(null)
/** 履约方式：1 快递 / 2 到店自提。自营实体店默认自提 */
const deliveryMode = ref(2)
const placeholder = 'https://via.placeholder.com/60x60/eee?text='
// 幂等键：一次结算会话一个；提交成功后作废。超时/失败重试复用同一键 → 后端不会重复建单
let idemKey = crypto.randomUUID()

const total = computed(() => items.value.reduce((s, i) => s + i.price * i.num, 0))

async function loadAddresses() {
  addresses.value = await addressApi.list()
  // 默认地址优先（列表已按默认在前排序，这里仍显式找一次，避免依赖后端排序细节）
  const preferred = addresses.value.find(a => a.isDefault) || addresses.value[0]
  selectedAddressId.value = preferred ? preferred.id : null
}

async function loadFulfillment() {
  const shopId = items.value[0]?.shopId
  if (!shopId) return
  fulfilmentFallback()
  try {
    fulfillment.value = await shopApi.fulfillment(shopId)
  } catch (e) {
    fulfillment.value = null
  }
  // 后端已保证同一订单只能是同一店铺的商品；店铺不支持快递时固定自提
  if (fulfillment.value && !fulfillment.value.supportExpress) {
    deliveryMode.value = 2
  }
}

/** 结算页拿不到店铺履约信息时的兜底：按「仅自提」处理，避免把订单下成快递 */
function fulfilmentFallback() {
  fulfillment.value = { shopName: '', supportExpress: 0, address: null, contactPhone: null }
}

async function submit() {
  if (!items.value.length) return
  if (deliveryMode.value === 1 && !selectedAddressId.value) {
    ElMessage.warning('请先选择收货地址')
    return
  }
  if (deliveryMode.value === 2 && fulfillment.value && !fulfillment.value.address) {
    ElMessage.warning('该店铺尚未配置自提地址，暂时无法下单')
    return
  }
  submitting.value = true
  try {
    const dto = {
      items: items.value.map(i => ({ skuId: i.skuId, num: i.num })),
      buyerRemark: remark.value,
      deliveryMode: deliveryMode.value,
      // 自提不需要也不应带收货地址
      addressId: deliveryMode.value === 1 ? selectedAddressId.value : null
    }
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
  loadAddresses()
  const encoded = route.query.items
  if (!encoded) return
  const raw = JSON.parse(decodeURIComponent(encoded))
  // 商品行来自购物车（完整快照）或立即购买（含服务端快照），仅需规整字段
  items.value = raw.map((it) => ({
    skuId: it.skuId,
    num: it.num,
    // 结算页要按 shopId 查店铺履约能力；老链接不带该字段时走「仅自提」兜底
    shopId: it.shopId,
    spuTitle: it.spuTitle || `商品#${it.skuId}`,
    skuTitle: it.skuTitle || '',
    skuImage: it.skuImage || '',
    price: Number(it.price ?? 0)
  }))
  // 必须先解析出 items 才知道 shopId，故履约信息在设置 items 之后加载
  loadFulfillment()
})
</script>

<style scoped>
.fulfill { border: 1px solid #ebeef5; border-radius: 6px; padding: 14px 16px; margin-bottom: 16px; }
.fulfill .row { display: flex; align-items: center; gap: 12px; }
.fulfill .label { color: #606266; font-size: 14px; width: 70px; }
.pickup { margin-top: 12px; padding: 10px 12px; background: #f5f7fa; border-radius: 4px; }
.pickup-shop { font-weight: 600; margin-bottom: 4px; }
.pickup-addr { color: #303133; }
.pickup-phone { color: #909399; font-size: 12px; margin-top: 4px; }
.warn { color: #e6a23c; font-size: 14px; }
.addr { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.goods-cell { display: flex; gap: 10px; align-items: center; }
.thumb { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.spu { font-size: 14px; }
.sku { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 700; }
.summary { display: flex; justify-content: flex-end; align-items: center; gap: 20px; margin-top: 20px; }
.total { font-size: 14px; }
</style>
