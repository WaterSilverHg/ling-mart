<template>
  <div class="card">
    <h3 class="page-title">卖家中心</h3>
    <el-tabs v-model="tab">
      <!-- 我的店铺 -->
      <el-tab-pane label="我的店铺" name="shop">
        <el-form v-if="shop" :model="shop" label-width="90px" style="max-width: 520px">
          <el-form-item label="店铺名称"><el-input v-model="shop.shopName" /></el-form-item>
          <el-form-item label="店铺 logo"><ImageUploader v-model="shopLogoList" :limit="1" /></el-form-item>
          <el-form-item label="店铺描述"><el-input v-model="shop.description" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="营业状态">
            <el-tag :type="shop.status === 1 ? 'success' : 'warning'">{{ shop.status === 1 ? '营业中' : '未启用' }}</el-tag>
            <el-tag style="margin-left: 8px" :type="reviewType(shop.reviewStatus)">{{ reviewText(shop.reviewStatus) }}</el-tag>
          </el-form-item>
          <el-form-item v-if="shop.status === 1">
            <el-button type="primary" @click="updateShop">提交修改（重新审核）</el-button>
          </el-form-item>
          <el-alert v-if="shop.reviewStatus === 3" type="warning" :closable="false" show-icon
                    title="店铺信息修改待审核：审核通过前继续按旧信息营业" style="max-width: 420px" />
        </el-form>
        <el-result v-else icon="warning" title="您还没有店铺" sub-title="请先在首页「申请开店」">
          <template #extra><el-button type="primary" @click="$router.push('/shop/apply')">去申请</el-button></template>
        </el-result>
      </el-tab-pane>

      <!-- 发布商品 -->
      <el-tab-pane label="发布商品" name="publish" :disabled="!shop || shop.status !== 1">
        <div style="max-width: 680px">
          <ProductEditor v-model="spu" />
          <div style="margin-top: 16px">
            <el-button type="primary" :loading="publishing" @click="publish">发布商品（提交审核）</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- 我的商品 -->
      <el-tab-pane label="我的商品" name="products">
        <el-empty v-if="!productsLoading && myProducts.length === 0" description="还没有商品" />
        <el-table v-else :data="myProducts">
          <el-table-column label="商品" min-width="200">
            <template #default="{ row }">
              <div class="p-cell">
                <img v-img-fallback :src="row.mainImage || placeholder" class="thumb" />
                <span>{{ row.title }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="价格" width="100">
            <template #default="{ row }"><span class="price">¥{{ row.price }}</span></template>
          </el-table-column>
          <el-table-column label="数量" width="150">
            <template #default="{ row }">
              <span :class="{ 'stock-infinite': row.stocks && row.stocks.length > 0 && row.stocks.every(s => s.unlimited) }">
                {{ stockText(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="审核状态" width="110">
            <template #default="{ row }">
              <el-tag :type="reviewType(row.reviewStatus)">{{ reviewText(row.reviewStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="销售" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '在售' : '下架' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button v-if="row.reviewStatus === 1 && row.status === 0" size="small" type="primary" @click="onShelf(row)">上架</el-button>
              <el-button v-if="row.reviewStatus === 1 && row.status === 1" size="small" type="warning" @click="offShelf(row)">下架</el-button>
              <el-button v-if="row.reviewStatus === 1" size="small" @click="openEdit(row)">编辑内容</el-button>
              <el-button v-if="row.reviewStatus === 1" size="small" @click="openStock(row)">改库存</el-button>
              <el-tooltip v-if="row.reviewStatus === 3" content="内容修改待审核，通过前继续按旧内容销售" placement="top">
                <el-tag type="warning" size="small">待重审</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="productsTotal > productsSize" class="pager" background layout="prev, pager, next" :total="productsTotal"
                       :page-size="productsSize" :current-page="productsPage" @current-change="onProductsPage" />
      </el-tab-pane>

      <!-- 订单发货 -->
      <el-tab-pane label="订单发货" name="orders">
        <el-empty v-if="sellerOrders.length === 0" description="暂无待处理订单" />
        <div v-for="o in sellerOrders" :key="o.orderSn" class="order-row">
          <div class="o-info">
            <div class="o-sn">{{ o.orderSn }}</div>
            <div v-for="it in o.items" :key="it.skuId" class="o-item">{{ it.spuTitle }} × {{ it.num }}</div>
            <div v-if="o.refundStatus === 1" class="o-refund">
              <el-tag type="warning" size="small">退款申请</el-tag>
              <span class="o-reason">买家原因：{{ o.refundReason }}</span>
              <el-button size="small" type="success" @click="reviewRefund(o, true)">同意退款</el-button>
              <el-button size="small" type="danger" @click="reviewRefund(o, false)">拒绝</el-button>
            </div>
            <div v-else-if="o.refundStatus === 3" class="o-refund">退款已拒绝：{{ o.refundRejectReason }}</div>
          </div>
          <el-tag :type="o.refundStatus === 1 ? 'warning' : (o.orderStatus === 1 ? 'primary' : 'info')">{{ o.refundStatus === 1 ? '退款处理中' : (o.orderStatus === 1 ? '待发货' : statusText(o.orderStatus)) }}</el-tag>
          <el-button v-if="o.orderStatus === 1" size="small" type="success" @click="ship(o)">发货</el-button>
        </div>
        <el-pagination v-if="sellerOrdersTotal > sellerOrdersSize" class="pager" background layout="prev, pager, next" :total="sellerOrdersTotal"
                       :page-size="sellerOrdersSize" :current-page="sellerOrdersPage" @current-change="onSellerOrdersPage" />
      </el-tab-pane>

      <!-- 数据统计（批7：卖家经营看板 + 订单导出） -->
      <el-tab-pane label="数据统计" name="stats" :disabled="!shop || shop.status !== 1">
        <div v-if="stats">
          <div class="stats-head">
            <el-radio-group v-model="range" @change="loadStats">
              <el-radio-button value="7d">近7天</el-radio-button>
              <el-radio-button value="30d">近30天</el-radio-button>
              <el-radio-button value="all">全部</el-radio-button>
            </el-radio-group>
            <el-button type="primary" plain :icon="Download" @click="exportOrders">导出订单</el-button>
          </div>

          <el-row :gutter="16" class="stat-cards">
            <el-col :span="6"><el-card shadow="never"><div class="stat-label">本期销售额</div><div class="stat-num price">¥{{ fmt(stats.overview.salesAmount) }}</div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-label">本期订单数</div><div class="stat-num">{{ stats.overview.orderCount }}</div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-label">成交件数</div><div class="stat-num">{{ stats.overview.itemCount }}</div></el-card></el-col>
            <el-col :span="6"><el-card shadow="never"><div class="stat-label">客单价</div><div class="stat-num price">¥{{ fmt(stats.overview.avgAmount) }}</div></el-card></el-col>
          </el-row>

          <div class="charts">
            <div ref="trendChart" class="chart"></div>
            <div ref="statusChart" class="chart"></div>
          </div>

          <el-table :data="stats.topProducts" style="margin-top: 16px">
            <el-table-column label="商品（TOP5）" min-width="260">
              <template #default="{ row }">{{ row.spuTitle }}<span class="sku-sub"> / {{ row.skuTitle }}</span></template>
            </el-table-column>
            <el-table-column label="成交件数" width="120" prop="num" align="center" />
            <el-table-column label="成交金额" width="160">
              <template #default="{ row }"><span class="price">¥{{ row.amount }}</span></template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-else description="暂无统计数据" />
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑商品内容（宽松重审）：与发布共用同一套表单；数量不在内容里改（改库存不重审） -->
    <el-dialog v-model="editVisible" title="编辑商品内容（提交后重新审核，通过前旧内容照常销售）" width="720px">
      <ProductEditor v-model="editForm" :stock-editable="false" />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">提交审核</el-button>
      </template>
    </el-dialog>

    <!-- 改库存（不触发重审）：数量留空 = 不限量；0 = 售罄 -->
    <el-dialog v-model="stockVisible" title="修改库存（不触发重新审核）" width="440px">
      <div v-for="s in stockForm" :key="s.id" class="sku-row">
        <span style="width: 200px">{{ s.title }}</span>
        <el-input
          :model-value="stockQtyText(s)"
          @update:model-value="setStockQty(s, $event)"
          inputmode="numeric"
          placeholder="数量（留空=不限量）"
          style="width: 150px"
        />
      </div>
      <div class="stock-tip">数量留空表示不限量（不限量时买家不显示件数，也不会出现库存不足）；填 0 表示售罄。</div>
      <template #footer>
        <el-button @click="stockVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStock">保存库存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { shopApi, productApi, orderApi } from '../api'
import ImageUploader from '../components/ImageUploader.vue'
import ProductEditor from '../components/ProductEditor.vue'

const tab = ref('shop')
const shop = ref(null)
const shopLogoList = ref([])   // 店铺 logo（单图，改店铺时上传）
const myProducts = ref([])
const sellerOrders = ref([])
const publishing = ref(false)
const placeholder = 'https://via.placeholder.com/48x48/eee?text='

// 分页：我的商品
const productsPage = ref(1)
const productsSize = ref(10)
const productsTotal = ref(0)
const productsLoading = ref(false)
// 分页：卖家订单
const sellerOrdersPage = ref(1)
const sellerOrdersSize = ref(10)
const sellerOrdersTotal = ref(0)

const spu = ref({ title: '', subtitle: '', images: [], description: '', skus: [{ title: '', price: null, qty: null }] })

// 编辑 / 改库存
const editVisible = ref(false)
const editForm = ref({ id: null, title: '', subtitle: '', images: [], description: '', skus: [] })
const editing = ref(false)
const stockVisible = ref(false)
const stockForm = ref([])
const editingSpuId = ref(null)

const REVIEW = { 0: ['待审核', 'warning'], 1: ['已通过', 'success'], 2: ['已拒绝', 'danger'], 3: ['待重审', 'warning'] }
function reviewText(s) { return REVIEW[s]?.[0] || '未知' }
function reviewType(s) { return REVIEW[s]?.[1] || 'info' }
function statusText(s) { return { 0: '待支付', 1: '待发货', 2: '已发货', 3: '已完成', 4: '已取消', 5: '已退款' }[s] || '未知' }

async function loadAll() {
  try {
    shop.value = await shopApi.myShop()
    // 回显店铺 logo（单图）
    shopLogoList.value = shop.value.shopLogo ? [shop.value.shopLogo] : []
  } catch (e) { shop.value = null }
}

async function updateShop() {
  await shopApi.updateShop({
    shopName: shop.value.shopName,
    shopLogo: shopLogoList.value?.[0] || null,
    description: shop.value.description
  })
  ElMessage.success('已提交修改，等待重新审核；审核通过前继续按旧信息营业')
  loadAll()
}

/** 共享表单结构 → 后端 SpuDTO（第一张图为主图，其余图挂到各 SKU；数量 NULL=不限量） */
function toSpuPayload(src) {
  const images = src.images || []
  const skus = (src.skus || [])
    .filter(s => s.title && s.price != null && Number(s.price) > 0)
    .map(s => ({ title: s.title, price: s.price, stock: s.qty == null ? null : s.qty, images: images.slice(1) }))
  return {
    title: src.title,
    subtitle: src.subtitle || null,
    mainImage: images[0] || null,
    description: src.description || null,
    skus
  }
}

/** 「我的商品」数量列文案：全部不限量→不限量；限量→剩余件数/已售罄；混合→两者都提示 */
function stockText(row) {
  const stocks = row.stocks || []
  if (!stocks.length) return '—'
  const unlimited = stocks.filter(s => s.unlimited)
  const limited = stocks.filter(s => !s.unlimited)
  if (limited.length === 0) return '不限量'
  const sum = limited.reduce((a, s) => a + (Number.isFinite(s.available) ? s.available : 0), 0)
  const suffix = unlimited.length > 0 ? ' · 另有不限量规格' : ''
  return sum > 0 ? `剩 ${sum} 件${suffix}` : `已售罄${suffix}`
}

/** 改库存数量输入显示：null（不限量）显示为空 */
function stockQtyText(s) {
  return s.stock == null ? '' : String(s.stock)
}

/** 改库存数量输入：仅非负整数，清空=不限量 */
function setStockQty(s, value) {
  const digits = String(value ?? '').replace(/[^\d]/g, '')
  s.stock = digits === '' ? null : Math.max(0, parseInt(digits, 10))
}

async function publish() {
  const dto = toSpuPayload(spu.value)
  if (!dto.title || !dto.skus.length) {
    ElMessage.warning('请填写商品标题和至少一个有效规格（规格名 + 价格）')
    return
  }
  publishing.value = true
  try {
    await productApi.create(dto)
    ElMessage.success('已提交审核，审核通过后即可上架')
    tab.value = 'products'
    loadProducts()
    // 发布成功，清空表单便于发布下一个商品
    spu.value = { title: '', subtitle: '', images: [], description: '', skus: [{ title: '', price: null, qty: null }] }
  } finally {
    publishing.value = false
  }
}

async function loadProducts() {
  productsLoading.value = true
  try {
    const r = await productApi.myProducts({ page: productsPage.value, size: productsSize.value })
    myProducts.value = r.list
    productsTotal.value = r.total
  } finally {
    productsLoading.value = false
  }
}

function onProductsPage(p) {
  productsPage.value = p
  loadProducts()
}

async function onShelf(row) {
  await productApi.onShelf(row.spuId)
  ElMessage.success('已上架')
  loadProducts()
}

async function offShelf(row) {
  await productApi.offShelf(row.spuId)
  ElMessage.success('已下架')
  loadProducts()
}

async function openEdit(row) {
  const detail = await productApi.detail(row.spuId)
  // 加载全部图片：封面 + 各 SKU 补充图（去重保序），避免编辑时只看到封面、其余图丢失
  const allImages = []
  const pushUnique = (u) => { if (u && !allImages.includes(u)) allImages.push(u) }
  pushUnique(detail.mainImage)
  ;(detail.skus || []).forEach(s => (s.images || []).forEach(pushUnique))
  editForm.value = {
    id: detail.spuId,
    title: detail.title,
    subtitle: detail.subtitle,
    images: allImages,
    description: detail.description,
    // 内容重审会重建 SKU，这里把当前数量带进草稿（不限量=null），审核通过后不丢数量
    skus: detail.skus.map(s => ({ title: s.title, price: s.price, qty: s.unlimited ? null : s.stock }))
  }
  editVisible.value = true
}

async function submitEdit() {
  editing.value = true
  try {
    const dto = toSpuPayload(editForm.value)
    if (!dto.title || !dto.skus.length) {
      ElMessage.warning('请填写标题和至少一个规格（规格名 + 价格）')
      return
    }
    await productApi.update(editForm.value.id, dto)
    ElMessage.success('已提交内容审核，通过前按旧内容销售')
    editVisible.value = false
    loadProducts()
  } finally {
    editing.value = false
  }
}

async function openStock(row) {
  const detail = await productApi.detail(row.spuId)
  editingSpuId.value = row.spuId
  // 数量：null = 不限量（输入框留空）；0..N 为限量
  stockForm.value = detail.skus.map(s => ({ id: s.id, title: s.title, stock: s.unlimited ? null : s.stock }))
  stockVisible.value = true
}

async function submitStock() {
  await productApi.updateStock(editingSpuId.value, stockForm.value.map(s => ({ id: s.id, title: s.title, price: null, stock: s.stock, images: [] })))
  ElMessage.success('库存已更新')
  stockVisible.value = false
}

async function loadOrders() {
  const r = await orderApi.sellerList({ page: sellerOrdersPage.value, size: sellerOrdersSize.value })
  sellerOrders.value = r.list
  sellerOrdersTotal.value = r.total
}

function onSellerOrdersPage(p) {
  sellerOrdersPage.value = p
  loadOrders()
}

async function ship(o) {
  await orderApi.ship(o.orderSn)
  ElMessage.success('已发货')
  loadOrders()
}

async function reviewRefund(o, approve) {
  let rejectReason = ''
  if (!approve) {
    try {
      const r = await ElMessageBox.prompt('请输入拒绝退款的原因', '拒绝退款', {
        confirmButtonText: '确认拒绝', cancelButtonText: '取消',
        inputValidator: v => !!v || '请填写拒绝原因'
      })
      rejectReason = r.value
    } catch (e) { return }
  } else {
    try {
      await ElMessageBox.confirm('确认同意退款？退款后库存将回补、订单不再计入销售。', '同意退款', { type: 'warning' })
    } catch (e) { return }
  }
  await orderApi.reviewRefund(o.orderSn, { approve, rejectReason })
  ElMessage.success(approve ? '已同意退款' : '已拒绝退款')
  loadOrders()
}

// ============ 数据统计（批7） ============
const range = ref('7d')
const stats = ref(null)
const trendChart = ref(null)
const statusChart = ref(null)
let trendChartIns = null
let statusChartIns = null
const STATUS_NAME = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已完成', 4: '已取消', 5: '已退款' }

// 切换到「数据统计」tab 时懒加载（shop 未营业 tab 已禁用）
watch(tab, (v) => {
  if (v === 'stats' && !stats.value) loadStats()
})

async function loadStats() {
  try {
    stats.value = await orderApi.sellerStats({ range: range.value })
    await nextTick()
    renderCharts()
  } catch (e) { /* 拦截器已提示 */ }
}

function renderCharts() {
  renderTrend()
  renderStatus()
}

function renderTrend() {
  if (!trendChart.value) return
  trendChartIns = trendChartIns || echarts.init(trendChart.value)
  const trend = stats.value?.salesTrend || []
  trendChartIns.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['销售额', '订单数'] },
    grid: { left: 48, right: 48, bottom: 28, top: 36 },
    xAxis: { type: 'category', data: trend.map(t => t.date), axisLabel: { fontSize: 11 } },
    yAxis: [
      { type: 'value', name: '销售额(元)' },
      { type: 'value', name: '订单数', minInterval: 1 }
    ],
    series: [
      { name: '销售额', type: 'line', smooth: true, data: trend.map(t => Number(t.amount)), itemStyle: { color: '#f56c6c' } },
      { name: '订单数', type: 'bar', yAxisIndex: 1, barMaxWidth: 22, data: trend.map(t => t.orderCount), itemStyle: { color: '#409eff' } }
    ]
  })
}

function renderStatus() {
  if (!statusChart.value) return
  statusChartIns = statusChartIns || echarts.init(statusChart.value)
  const dist = stats.value?.statusDistribution || []
  statusChartIns.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '62%'],
      center: ['50%', '46%'],
      data: dist.map(d => ({ name: STATUS_NAME[d.status] || `状态${d.status}`, value: d.count }))
    }]
  })
}

// 订单导出（xlsx，从 Content-Disposition 读取文件名）
async function exportOrders() {
  const daysMap = { '7d': 7, '30d': 30, 'all': 0 }
  const res = await orderApi.sellerExport({ days: daysMap[range.value] })
  downloadBlob(res.data, res.headers?.['content-disposition'])
}

function downloadBlob(blob, contentDisposition) {
  const matched = contentDisposition?.match(/filename\*=utf-8''([^;]+)/)
  const fileName = matched ? decodeURIComponent(matched[1]) : `订单导出_${Date.now()}.xlsx`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

function fmt(v) {
  return Number(v ?? 0).toFixed(2)
}

function disposeCharts() {
  trendChartIns?.dispose()
  statusChartIns?.dispose()
  trendChartIns = statusChartIns = null
}

onMounted(() => {
  loadAll()
  loadProducts()
  loadOrders()
})

onBeforeUnmount(disposeCharts)
</script>

<style scoped>
.sku-row { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.p-cell { display: flex; align-items: center; gap: 8px; }
.thumb { width: 48px; height: 48px; object-fit: cover; border-radius: 4px; background: #f0f2f5; }
.price { color: #f56c6c; font-weight: 600; }
.stock-infinite { color: #909399; }
.stock-tip { color: #909399; font-size: 12px; margin-bottom: 8px; }
.order-row { display: flex; align-items: center; gap: 16px; background: #fafafa; padding: 12px 16px; border-radius: 6px; margin-bottom: 10px; }
.o-info { flex: 1; }
.o-sn { font-size: 12px; color: #909399; }
.o-item { font-size: 14px; }
.o-refund { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.o-reason { color: #e6a23c; font-size: 12px; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
/* 数据统计 */
.stats-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 10px; }
.stat-cards { margin-bottom: 16px; }
.stat-label { color: #909399; font-size: 13px; }
.stat-num { font-size: 22px; font-weight: 700; margin-top: 6px; }
.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart { height: 300px; }
.sku-sub { color: #909399; font-size: 12px; }
</style>
