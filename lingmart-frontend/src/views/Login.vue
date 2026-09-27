<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <h2 class="title">登录轻营</h2>
      <el-form :model="form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号" maxlength="11" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password @keyup.enter="onSubmit" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">登 录</el-button>
        <div class="tips">
          <span>还没有账号？<router-link to="/register">去注册</router-link></span>
          <span class="admin-tip">管理员：13800000000 / admin123</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const loading = ref(false)
const form = reactive({ phone: '', password: '' })

async function onSubmit() {
  if (!form.phone || !form.password) {
    ElMessage.warning('请填写手机号和密码')
    return
  }
  loading.value = true
  try {
    const user = await authStore.login(form.phone, form.password)
    ElMessage.success(`欢迎回来，${user.nickname || user.phone}`)
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-wrap { display: flex; justify-content: center; padding-top: 60px; }
.auth-card { width: 380px; }
.title { text-align: center; margin-bottom: 24px; color: #409eff; }
.tips { margin-top: 12px; font-size: 12px; color: #909399; display: flex; justify-content: space-between; }
.admin-tip { color: #e6a23c; }
</style>
