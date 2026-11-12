<template>
  <div v-if="product" class="card detail">
    <div class="main-row">
      <img v-img-fallback :src="currentImage" class="big-img" />
      <div class="info">
        <h2 class="title">{{ product.title }}</h2>
        <p class="subtitle" v-if="product.subtitle">{{ product.subtitle }}</p>
        <div class="price-box">¥ <span class="price">{{ currentSku?.price ?? product.price }}</span></div>
        <div class="meta-row">
          <span v-if="product.brandName">品牌：{{ product.brandName }}</span>
          <span>店铺：<router-link :to="`/shop/${product.shopId}`" class="shop-link">{{ product.shopName }}</router-link></span>
        </div>
        <div class="meta-row">销量：{{ product.saleCount }}</div>

        <div class="sku-box">
          <div class="sku-label">选择规格：</div>
          <el-radio-group v-model="selectedSkuId">
            <el-radio-button v-for="sku in product.skus" :key="sku.id" :value="sku.id">
              {{ sku.title }}
            </el-radio-button>
          </el-radio-group>
          <!-- 数量展示：限量显示剩余/售罄；不限量不显示件数（符合“默认不显示数量=无限供应”） -->
          <div v-if="isLimited" class="stock-line" :class="{ 'sold-out': skuSoldOut }">
            <span v-if="!skuSoldOut">仅剩 <b class="stock-num">{{ currentSku.stock }}</b> 件</span>
            <el-tag v-else type="danger" size="small" effect="dark">已售罄</el-tag>
          </div>
        </div>

        <div v-if="sellable" class="buy-row">
          <el-input-number v-model="num" :min="1" :max="maxBuy" :disabled="skuSoldOut" />
          <el-button type="warning" :disabled="skuSoldOut || !currentSku" @click="addCart">加入购物车</el-button>
          <el-button type="danger" :disabled="skuSoldOut || !currentSku" @click="buyNow">立即购买</el-button>
        </div>
        <el-alert v-else-if="product.reviewStatus !== 1" type="warning" :closable="false" show-icon
                  :title="`商品${product.reviewStatus === 3 ? '内容修改待审核（旧内容暂不可售）' : '未通过审核，暂不可购买'}`" />
        <el-alert v-else type="info" :closable="false" show-icon title="商品已下架，暂不可购买" />
      </div>
    </div>

    <el-divider />
    <h3>商品详情</h3>
    <p class="desc">{{ product.description || '暂无详情' }}</p>
  </div>
  <el-empty v-else description="商品不存在或已下架" />
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { productApi, cartApi, orderApi } from '../api'

const route = useRoute()
const router = useRouter()
const product = ref(null)
const selectedSkuId = ref(null)
const num = ref(1)

const currentSku = computed(() => product.value?.skus?.find(s => s.id === selectedSkuId.value) || null)
const currentImage = computed(() => currentSku.value?.images?.[0] || product.value?.mainImage || '')
// 仅审核通过（reviewStatus=1）且在售（status=1）的商品可购买
const sellable = computed(() => product.value?.reviewStatus === 1 && product.value?.status === 1)
// 当前 SKU 是否为限量：unlimited=true 或无数值均视为不限量（不显示件数）
const isLimited = computed(() => !!currentSku.value && currentSku.value.unlimited !== true && currentSku.value.stock != null)
// 限量且剩余 0 → 售罄，禁止购买
const skuSoldOut = computed(() => isLimited.value && (currentSku.value.stock ?? 0) <= 0)
// 单次可购上限：限量时不超过剩余件数；不限量时放开到 999
const maxBuy = computed(() => (isLimited.value ? Math.max(1, Math.min(999, currentSku.value.stock)) : 999))

// 切换规格时把已填购买数量钳制到新规格可用范围内
watch(selectedSkuId, () => {
  if (num.value > maxBuy.value) num.value = Math.max(1, maxBuy.value)
})

function checkoutWith(items) {
  const encoded = encodeURIComponent(JSON.stringify(items))
  router.push({ path: '/checkout', query: { items: encoded } })
}

async function addCart() {
  if (!currentSku.value) {
    ElMessage.warning('请选择规格')
    return
  }
  await cartApi.add({ skuId: selectedSkuId.value, num: num.value })
  ElMessage.success('已加入购物车')
}

function buyNow() {
  if (!currentSku.value) {
    ElMessage.warning('请选择规格')
    return
  }
  // 携带服务端快照，结算页直接展示（下单金额仍以服务端为准）
  checkoutWith([{
    skuId: currentSku.value.id,
    num: num.value,
    // 结算页据此查询店铺履约能力（自提地址 / 是否支持快递）
    shopId: product.value.shopId,
    spuTitle: product.value.title,
    skuTitle: currentSku.value.title,
    skuImage: currentSku.value.images?.[0],
    price: currentSku.value.price
  }])
}

onMounted(async () => {
  const data = await productApi.detail(route.params.id)
  product.value = data
  if (data?.skus?.length) {
    selectedSkuId.value = data.skus[0].id
  }
})
</script>

<style scoped>
.main-row { display: flex; gap: 24px; }
.big-img { width: 360px; height: 360px; object-fit: cover; border-radius: 8px; background: #f0f2f5; }
.info { flex: 1; }
.title { font-size: 22px; }
.subtitle { color: #909399; margin: 8px 0; }
.price-box { background: #fef0f0; padding: 12px; border-radius: 6px; color: #f56c6c; font-size: 14px; margin: 12px 0; }
.price { font-size: 28px; font-weight: 700; }
.meta-row { color: #606266; font-size: 13px; margin: 6px 0; display: flex; gap: 20px; }
.shop-link { color: #409eff; text-decoration: none; }
.shop-link:hover { text-decoration: underline; }
.sku-box { margin: 20px 0; }
.sku-label { margin-bottom: 8px; font-size: 14px; }
.stock-line { margin-top: 12px; font-size: 14px; color: #e6a23c; }
.stock-line .stock-num { color: #f56c6c; font-size: 16px; }
.stock-line.sold-out { color: #909399; }
.stock-line .el-tag { vertical-align: middle; }
.buy-row { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.desc { color: #606266; line-height: 1.8; white-space: pre-wrap; }
</style>
