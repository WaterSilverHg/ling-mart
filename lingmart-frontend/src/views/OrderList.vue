<template>
  <div class="card">
    <h3 class="page-title">我的订单</h3>
    <el-empty v-if="!loading && orders.length === 0" description="暂无订单" />
    <div v-for="o in orders" :key="o.orderSn" class="order">
      <div class="head">
        <span class="sn">订单号：{{ o.orderSn }}</span>
        <el-tag :type="tagType(o)">{{ tagText(o) }}</el-tag>
      </div>
      <div v-for="item in o.items" :key="item.skuId" class="item">
        <img v-img-fallback :src="item.skuImage || placeholder" class="thumb" />
        <div class="titles">
          <div>{{ item.spuTitle }}</div>
          <div class="sku">{{ item.skuTitle }} × {{ item.num }}</div>
        </div>
        <span class="price">¥{{ item.price }}</span>
      </div>
      <div class="foot">
        <!-- 自提订单：展示去哪儿取货（下单快照） -->
        <div v-if="o.pickupAddress" class="receiver">
          自提：{{ o.pickupShopName }} · {{ o.pickupAddress }}
          <span v-if="o.pickupPhone" class="contact">（门店电话 {{ o.pickupPhone }}）</span>
        </div>
        <!-- 快递订单：收货信息为下单快照。v0.39 之前的存量订单没有该字段，这里不渲染 -->
        <div v-else-if="o.receiverName" class="receiver">
          收货：{{ o.receiverName }} {{ o.receiverPhone }} · {{ o.receiverFullAddress }}
        </div>
        <span class="amount">合计：<b class="price">¥{{ o.totalAmount }}</b></span>
        <span class="time">下单时间：{{ formatTime(o.createTime) }}</span>
        <div v-if="o.shipTime" class="ship-info">
          <template v-if="o.deliveryMode === 1">
            物流：{{ o.shippingCompany }} <span class="tracking">{{ o.trackingNo }}</span>
          </template>
          <template v-else-if="o.deliveryMode === 2">已备货，可到店取货</template>
          <!-- 兼容本次升级前已发货的存量订单：那时还没有发货方式字段 -->
          <template v-else>已发货</template>
        </div>
        <div v-if="o.refundStatus" class="refund-info">
          <template v-if="o.refundStatus === 1">退款处理中（卖家 24 小时未处理将自动退款）</template>
          <template v-else-if="o.refundStatus === 2">已退款</template>
          <template v-else-if="o.refundStatus === 3">退款被拒<span v-if="o.refundRejectReason">：{{ o.refundRejectReason }}</span></template>
          <span v-if="o.refundReason">（申请原因：{{ o.refundReason }}）</span>
        </div>
        <div class="ops">
          <el-button v-if="o.orderStatus === 0" size="small" type="danger" @click="pay(o)">立即支付</el-button>
          <el-button v-if="o.orderStatus === 0" size="small" @click="cancel(o)">取消订单</el-button>
          <!-- 自提订单的「确认收货」实际是「确认已取货」，文案按履约方式区分 -->
          <el-button v-if="o.orderStatus === 2" size="small" type="success" @click="confirm(o)">
            {{ o.deliveryMode === 2 ? '确认已取货' : '确认收货' }}
          </el-button>
          <el-button v-if="canRefund(o)" size="small" @click="requestRefund(o)">申请退款</el-button>
        </div>
      </div>
    </div>
    <el-pagination v-if="total > size" class="pager" background layout="prev, pager, next" :total="total"
                   :page-size="size" :current-page="page" @current-change="onPage" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '../api'

const orders = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const placeholder = 'https://via.placeholder.com/48x48/eee?text='

const STATUS = { 0: ['待支付', 'warning'], 1: ['已支付', 'primary'], 2: ['已发货', 'success'], 3: ['已完成', 'success'], 4: ['已取消', 'info'], 5: ['已退款', 'info'] }

function statusText(s) { return STATUS[s]?.[0] || '未知' }
function statusType(s) { return STATUS[s]?.[1] || 'info' }

// 退款场景优先展示退款状态（退款中/已退款），否则展示订单状态
function tagText(o) {
  if (o.refundStatus === 1) return '退款处理中'
  if (o.refundStatus === 2 || o.orderStatus === 5) return '已退款'
  // 履约方式不同，状态 2 对买家的含义也不同：快递是「已发货」，自提是「待自提」
  if (o.orderStatus === 2 && o.deliveryMode === 2) return '待自提'
  return statusText(o.orderStatus)
}
function tagType(o) {
  if (o.refundStatus === 1) return 'warning'
  if (o.refundStatus === 2 || o.orderStatus === 5) return 'info'
  return statusType(o.orderStatus)
}

// 可申请退款：已支付、订单在 已支付/已发货/已完成、且当前无退款中/已退款记录
function canRefund(o) {
  return o.payStatus === 1 && [1, 2, 3].includes(o.orderStatus) && (o.refundStatus === 0 || o.refundStatus === 3)
}

function formatTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }

async function load() {
  loading.value = true
  try {
    const r = await orderApi.list({ page: page.value, size: size.value })
    orders.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

async function pay(o) {
  try {
    await ElMessageBox.confirm(`确认支付订单 ${o.orderSn} 吗？`, '模拟支付', { type: 'warning' })
    await orderApi.pay(o.orderSn)
    ElMessage.success('支付成功')
    load()
  } catch (e) { /* 取消弹窗 */ }
}

async function cancel(o) {
  await ElMessageBox.confirm('确定取消该订单吗？', '提示', { type: 'warning' })
  await orderApi.cancel(o.orderSn)
  ElMessage.success('已取消')
  load()
}

async function confirm(o) {
  await ElMessageBox.confirm('确认已收到货物吗？确认后订单完成。', '确认收货', { type: 'success' })
  await orderApi.confirm(o.orderSn)
  ElMessage.success('订单已完成')
  load()
}

async function requestRefund(o) {
  try {
    const r = await ElMessageBox.prompt('请填写退款原因', '申请退款', {
      confirmButtonText: '提交申请', cancelButtonText: '取消',
      inputValidator: v => !!v || '请填写退款原因'
    })
    await orderApi.refund(o.orderSn, { reason: r.value })
    ElMessage.success('退款申请已提交，等待卖家处理')
    load()
  } catch (e) { /* 取消弹窗 */ }
}

onMounted(load)
</script>

<style scoped>
.order { background: #fafafa; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.head { display: flex; justify-content: space-between; margin-bottom: 12px; }
.sn { color: #606266; font-size: 13px; }
.item { display: flex; align-items: center; gap: 12px; padding: 8px 0; border-top: 1px solid #f0f0f0; }
.thumb { width: 48px; height: 48px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.titles { flex: 1; font-size: 14px; }
.sku { color: #909399; font-size: 12px; }
.price { color: #f56c6c; font-weight: 600; }
.foot { display: flex; align-items: center; gap: 20px; margin-top: 12px; justify-content: flex-end; }
.amount { font-size: 14px; }
.time { color: #909399; font-size: 12px; }
.refund-info { color: #e6a23c; font-size: 12px; flex: 1; }
.receiver { color: #909399; font-size: 12px; flex: 1; }
.receiver .contact { color: #c0c4cc; }
.ship-info { color: #909399; font-size: 12px; flex: 1; }
.ship-info .tracking { color: #409eff; font-family: Consolas, Menlo, monospace; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
</style>
