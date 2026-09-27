<template>
  <div class="card" style="max-width: 680px">
    <h3 class="page-title">我的店铺</h3>
    <el-empty v-if="!loading && !application && !shop" description="还没有申请开店，去申请一个吧">
      <el-button type="primary" @click="$router.push('/shop/apply')">申请开店</el-button>
    </el-empty>

    <template v-else>
      <!-- 审核中 -->
      <el-result v-if="application && application.status === 0" icon="info" title="入驻申请审核中" :sub-title="`申请店铺：${application.shopName}`">
        <template #extra>
          <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
        </template>
      </el-result>

      <!-- 被拒绝 -->
      <el-result v-else-if="application && application.status === 2" icon="error" title="申请被拒绝" :sub-title="`店铺：${application.shopName}`">
        <template #extra>
          <el-button type="primary" @click="$router.push('/shop/apply')">重新申请</el-button>
          <el-button @click="$router.push('/')">返回首页</el-button>
        </template>
        <div v-if="application.rejectReason" class="reject-reason">拒绝原因：{{ application.rejectReason }}</div>
      </el-result>

      <!-- 已开店 -->
      <div v-else-if="shop || (application && application.status === 1)">
        <div class="shop-head" v-if="shop">
          <img v-img-fallback :src="shop.shopLogo || placeholder" class="logo" />
          <div class="info">
            <h2 class="name">{{ shop.shopName }}</h2>
            <p class="desc">{{ shop.description || '这家店还没有填写简介' }}</p>
            <div class="status-line">
              <el-tag :type="shopTagType" size="small">{{ shopTagText }}</el-tag>
              <span v-if="shop.reviewStatus === 3" class="warn">店铺信息变更审核中（旧信息照常营业）</span>
              <span v-if="shop.reviewStatus === 2" class="warn">店铺信息变更被拒（旧信息照常营业）</span>
            </div>
          </div>
        </div>
        <div v-else class="approved-note">入驻申请已通过，店铺营业中</div>

        <div class="actions">
          <el-button v-if="shop" type="primary" @click="$router.push(`/shop/${shop.id}`)">查看店铺主页</el-button>
          <el-button type="primary" plain @click="$router.push('/seller')">去卖家中心</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { shopApi } from '../api'

const application = ref(null)
const shop = ref(null)
const loading = ref(false)
const placeholder = 'https://via.placeholder.com/80x80/eee?text=店'

const shopTagText = computed(() => {
  const map = { 0: '待审核', 1: '营业中', 2: '已拒绝/禁用', 3: '信息变更待审核' }
  return map[shop.value?.status] || '未知'
})
const shopTagType = computed(() => {
  return { 0: 'warning', 1: 'success', 2: 'danger', 3: 'warning' }[shop.value?.status] || 'info'
})

async function load() {
  loading.value = true
  try {
    application.value = await shopApi.myApplication()
  } catch (e) { application.value = null }
  try {
    shop.value = await shopApi.myShop()
  } catch (e) { shop.value = null }
  loading.value = false
}

onMounted(load)
</script>

<style scoped>
.shop-head { display: flex; align-items: flex-start; gap: 16px; }
.logo { width: 80px; height: 80px; object-fit: cover; border-radius: 8px; background: #f0f2f5; }
.info { flex: 1; }
.name { margin: 0 0 6px; font-size: 20px; }
.desc { color: #909399; font-size: 13px; margin: 0 0 8px; }
.status-line { display: flex; align-items: center; gap: 10px; }
.warn { color: #e6a23c; font-size: 13px; }
.reject-reason { color: #f56c6c; font-size: 13px; margin-top: 8px; }
.actions { margin-top: 20px; display: flex; gap: 10px; }
.approved-note { color: #67c23a; font-size: 14px; }
</style>
