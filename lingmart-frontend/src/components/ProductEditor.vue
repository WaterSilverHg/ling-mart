<template>
  <div class="product-editor">
    <el-input v-model="model.title" maxlength="100" placeholder="商品标题（必填，如：九成新 iPhone 13）" />
    <el-input v-model="model.subtitle" maxlength="255" placeholder="副标题 / 卖点（选填，如：自用一年，无磕碰）" class="mt8" />
    <div class="mt8">
      <div class="field-tip">商品图片 <span class="gray">（第一张为封面，其余为详情补充图）</span></div>
      <ImageUploader v-model="model.images" :limit="9" />
    </div>
    <el-input v-model="model.description" type="textarea" :rows="2" maxlength="2000"
              placeholder="商品详情（选填）" class="mt8" />

    <el-divider content-position="left">规格</el-divider>
    <div class="sku-tip">每个规格可独立设置价格与数量；数量留空即不限量。</div>
    <div v-for="(sku, i) in model.skus" :key="i" class="sku-row">
      <el-input v-model="sku.title" placeholder="规格名，如：黑色/128G" style="width: 180px" />
      <el-input-number v-model="sku.price" :min="0.01" :precision="2" placeholder="价格（必填）" style="width: 130px" />
      <el-input
        :model-value="qtyText(sku)"
        @update:model-value="setQty(sku, $event)"
        :disabled="!stockEditable"
        inputmode="numeric"
        placeholder="数量（留空=不限量）"
        style="width: 170px"
      />
      <el-button v-if="!stockEditable" link type="info" class="stock-hint"
                 title="编辑商品内容不涉及库存，请在「我的商品」里点「改库存」单独调整">
        改库存维护
      </el-button>
      <el-button link type="danger" @click="removeSku(i)">删除</el-button>
    </div>
    <el-button size="small" @click="addSku">+ 添加规格</el-button>
  </div>
</template>

<script setup>
import ImageUploader from './ImageUploader.vue'

/**
 * 共享商品编辑表单：申请开店 / 卖家中心「发布商品」「编辑内容」复用同一套字段，
 * 避免出现两套不一致的商品表单。
 *
 * 绑定对象结构：
 *   { title, subtitle, images: [], description, skus: [{ title, price, qty }] }
 * - images：有序图片数组，第一张为封面（后端以 images[0] 为主图，其余挂到各 SKU）
 * - skus[].qty：null = 不限量；0..N = 限量可售（0 = 售罄）
 *
 * @prop stockEditable 是否允许编辑数量。编辑「内容」（重审）时库存不走该表单，
 *       应置 false 并把数量只读，提示用「改库存」单独维护。
 */
const model = defineModel({ type: Object, required: true })

// 模板直接可用 prop 名（见 template 中 :disabled="!stockEditable"）
defineProps({
  stockEditable: { type: Boolean, default: true }
})

/** 数量显示：null（不限量）显示为空，让「留空即不限量」所见即所得 */
function qtyText(sku) {
  return sku.qty == null ? '' : String(sku.qty)
}

/** 数量输入：仅允许非负整数，清空即视为不限量（null） */
function setQty(sku, value) {
  const digits = String(value ?? '').replace(/[^\d]/g, '')
  if (digits === '') {
    sku.qty = null
  } else {
    sku.qty = Math.max(0, parseInt(digits, 10))
  }
}

function addSku() {
  model.value.skus.push({ title: '', price: null, qty: null })
}

function removeSku(i) {
  model.value.skus.splice(i, 1)
}
</script>

<style scoped>
.product-editor { width: 100%; }
.mt8 { margin-top: 8px; }
.field-tip { font-size: 13px; color: #606266; margin-bottom: 6px; }
.gray { color: #909399; font-size: 12px; }
.sku-row { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.sku-tip { font-size: 12px; color: #909399; margin-bottom: 8px; }
.stock-hint { font-size: 12px; }
.product-editor :deep(.el-input), .product-editor :deep(.el-textarea) { max-width: 100%; }
</style>
