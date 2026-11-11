<template>
  <div class="admin-app">
    <header class="admin-topbar">
      <div class="inner">
        <router-link to="/admin/review" class="brand">轻营 · 管理后台</router-link>
        <nav class="nav">
          <router-link to="/admin/review">审核中心</router-link>
          <router-link to="/admin/logs">审计日志</router-link>
          <router-link to="/admin/ops">运维台</router-link>
        </nav>
        <div class="user">
          <span class="nick">{{ authStore.user?.nickname || authStore.user?.phone }}</span>
          <el-button size="small" text type="danger" @click="logout">退出登录</el-button>
        </div>
      </div>
    </header>
    <main class="admin-main">
      <!-- 管理端不包含商城功能（购物车/订单/申请开店/卖家中心），仅审核与日志 -->
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const authStore = useAuthStore()
const router = useRouter()

function logout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.admin-app { min-height: 100vh; background: #f5f7fa; }
.admin-topbar { background: #1f2d3d; color: #fff; }
.admin-topbar .inner { max-width: 1200px; margin: 0 auto; display: flex; align-items: center; height: 56px; padding: 0 16px; }
.brand { color: #fff; font-size: 18px; font-weight: 700; margin-right: 32px; text-decoration: none; }
.nav a { color: #c0c4cc; margin-right: 24px; text-decoration: none; font-size: 14px; }
.nav a.router-link-active { color: #409eff; font-weight: 600; }
.user { margin-left: auto; color: #c0c4cc; font-size: 13px; display: flex; align-items: center; gap: 10px; }
.admin-main { max-width: 1200px; margin: 16px auto; padding: 0 16px; }
</style>
