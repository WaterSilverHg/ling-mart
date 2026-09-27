<template>
  <div v-if="store" class="card">
    <div class="shop-head">
      <img v-img-fallback :src="store.shopLogo || placeholder" class="logo" />
      <div class="info">
        <h2 class="name">{{ store.shopName }}</h2>
        <p class="desc">{{ store.description || '这家店还没有填写简介' }}</p>
        <div class="owner">
          <el-avatar :size="24" :src="store.owner?.avatar || undefined" />
          <span>{{ store.owner?.nickname || '' }}</span>
        </div>
      </div>
      <!-- 店铺主页只展示店主头像/昵称，不展示手机号；联系老板进入会话后才可见 -->
      <el-button v-if="authStore.isLogin && authStore.user?.id !== store.owner?.userId" type="primary" @click="contact">
        联系老板
      </el-button>
    </div>

    <el-divider content-position="left">在售商品（{{ store.products?.length || 0 }}）</el-divider>
    <el-empty v-if="!store.products || store.products.length === 0" description="该店铺暂无在售商品" />
    <div v-else class="grid">
      <div v-for="p in store.products" :key="p.spuId" class="goods" @click="$router.push(`/product/${p.spuId}`)">
        <img v-img-fallback :src="p.mainImage || placeholder" class="img" />
        <div class="title" :title="p.title">{{ p.title }}</div>
        <div class="meta">
          <span class="price">¥{{ p.price }}</span>
          <span class="sales">销量 {{ p.saleCount || 0 }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { shopApi, chatApi } from '../api'
import { useAuthStore } from '../store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const store = ref(null)
const placeholder = 'https://via.placeholder.com/80x80/eee?text=店'

async function load() {
  try {
    store.value = await shopApi.storefront(route.params.id)
  } catch (e) {
    // 拦截器已提示；店铺不存在或未营业
    store.value = null
  }
}

async function contact() {
  if (!authStore.isLogin) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  try {
    // 发起/进入会话，跳转聊天页（由聊天页加载历史并建立 WebSocket）
    const detail = await chatApi.openConversation(store.value.id)
    router.push({ path: '/chat', query: { shopId: store.value.id } })
  } catch (e) {
    ElMessage.error('暂时无法发起会话，请稍后再试')
  }
}

onMounted(load)
</script>

<style scoped>
.shop-head { display: flex; align-items: flex-start; gap: 16px; }
.logo { width: 80px; height: 80px; object-fit: cover; border-radius: 8px; background: #f0f2f5; }
.info { flex: 1; }
.name { margin: 0 0 6px; font-size: 20px; }
.desc { color: #909399; font-size: 13px; margin: 0 0 8px; }
.owner { display: flex; align-items: center; gap: 6px; color: #606266; font-size: 13px; }
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.goods { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; cursor: pointer; transition: all .2s; }
.goods:hover { transform: translateY(-3px); box-shadow: 0 4px 12px rgba(0, 0, 0, .1); }
.img { width: 100%; height: 160px; object-fit: cover; background: #f0f2f5; }
.title { padding: 10px 12px 4px; font-size: 14px; color: #303133; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.meta { padding: 0 12px 12px; display: flex; justify-content: space-between; align-items: center; }
.price { color: #f56c6c; font-weight: 700; font-size: 16px; }
.sales { color: #909399; font-size: 12px; }
</style>
