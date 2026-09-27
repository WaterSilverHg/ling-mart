<template>
  <div>
    <!-- 搜索区：搜商品关键词 → 返回命中的店铺（点击进店查看商品，商品不直接展示） -->
    <div class="card search-bar">
      <el-input v-model="query.keyword" placeholder="搜索商品，如：手机 / 小米" clearable style="width: 260px"
                @keyup.enter="doSearch" />
      <el-input-number v-model="minPrice" :min="0" placeholder="最低价" style="width: 120px" />
      <span class="sep">—</span>
      <el-input-number v-model="maxPrice" :min="0" placeholder="最高价" style="width: 120px" />
      <el-button type="primary" @click="doSearch">搜索店铺</el-button>
      <el-button v-if="searched" link @click="resetBrowse">← 返回全部店铺</el-button>
    </div>

    <!-- 店铺结果：搜索时 = 命中的店铺；浏览时 = 全部入驻店铺 -->
    <div class="section-title">{{ searched ? `店铺搜索结果（${shops.length}）` : `入驻店铺（${shops.length}）` }}</div>
    <el-empty v-if="!loading && shops.length === 0"
              :description="searched ? '没有找到匹配的店铺' : '暂无入驻店铺，敬请期待'" />
    <div v-else class="shop-grid">
      <div v-for="s in shops" :key="s.id ?? s.shopId" class="shop-card" @click="$router.push(`/shop/${s.id ?? s.shopId}`)">
        <img v-img-fallback :src="s.shopLogo || s.image || placeholder" class="shop-logo" />
        <div class="shop-name">{{ s.shopName }}</div>
        <div class="shop-desc">
          {{ s.description || (s.productCount ? `${s.productCount} 件在售 · ¥${s.priceFrom} 起` : '这家店还没有填写简介') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { shopApi, searchApi } from '../api'

const placeholder = 'https://via.placeholder.com/200x160/eee?text=轻营'
const shops = ref([])
const loading = ref(false)
const searched = ref(false)
const minPrice = ref(null)
const maxPrice = ref(null)
const query = reactive({ keyword: '' })

/** 浏览模式：全部入驻店铺 */
async function loadShops() {
  loading.value = true
  try {
    const data = await shopApi.shops()
    shops.value = data || []
  } finally {
    loading.value = false
  }
}

/** 搜索模式：按命中商品聚合出店铺（商品不直接展示） */
async function doSearch() {
  searched.value = true
  loading.value = true
  try {
    const params = { page: 1, size: 12 }
    if (query.keyword) params.keyword = query.keyword
    if (minPrice.value !== null && minPrice.value !== undefined) params.minPrice = minPrice.value
    if (maxPrice.value !== null && maxPrice.value !== undefined) params.maxPrice = maxPrice.value
    const data = await searchApi.searchShops(params)
    shops.value = data?.list || []
  } catch (e) {
    shops.value = []
  } finally {
    loading.value = false
  }
}

function resetBrowse() {
  searched.value = false
  loadShops()
}

onMounted(() => {
  loadShops()
})
</script>

<style scoped>
.search-bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sep { color: #909399; }
.section-title { font-size: 16px; font-weight: 600; margin: 18px 4px 12px; color: #303133; }
/* 店铺卡片（首页浏览 / 搜索均以店铺为入口） */
.shop-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.shop-card { background: #fff; border-radius: 8px; padding: 16px; cursor: pointer; transition: all .2s; box-shadow: 0 1px 4px rgba(0, 0, 0, .06); text-align: center; }
.shop-card:hover { transform: translateY(-3px); box-shadow: 0 4px 12px rgba(0, 0, 0, .12); }
.shop-logo { width: 96px; height: 96px; object-fit: cover; border-radius: 50%; background: #f0f2f5; }
.shop-name { margin-top: 10px; font-size: 15px; font-weight: 600; color: #303133; }
.shop-desc { margin-top: 4px; font-size: 12px; color: #909399; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
