<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <h2 class="title">注册轻营</h2>
      <el-form :model="form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号" maxlength="11" />
        </el-form-item>
        <el-form-item>
          <div class="code-row">
            <el-input v-model="form.code" placeholder="验证码" maxlength="6" style="flex: 1" />
            <el-button :disabled="countdown > 0" @click="sendCode">
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.nickname" placeholder="昵称（可选）" maxlength="30" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="设置密码（至少 6 位）" show-password @keyup.enter="onSubmit" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">注 册</el-button>
        <div class="tips">
          <span>已有账号？<router-link to="/login">去登录</router-link></span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'

const router = useRouter()
const loading = ref(false)
const countdown = ref(0)
let timer = null
const form = reactive({ phone: '', code: '', nickname: '', password: '' })

async function sendCode() {
  if (!/^1\d{10}$/.test(form.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  const data = await authApi.sendCode(form.phone)
  // 开发模式返回验证码明文，自动填入便于联调
  if (data?.devCode) {
    form.code = data.devCode
    ElMessage.success(`验证码已发送（开发模式）：${data.devCode}`)
  } else {
    ElMessage.success('验证码已发送')
  }
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function onSubmit() {
  if (!form.phone || !form.code || !form.password) {
    ElMessage.warning('请填写完整信息')
    return
  }
  loading.value = true
  try {
    await authApi.register({ phone: form.phone, code: form.code, password: form.password, nickname: form.nickname })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}

onUnmounted(() => timer && clearInterval(timer))
</script>

<style scoped>
.auth-wrap { display: flex; justify-content: center; padding-top: 60px; }
.auth-card { width: 380px; }
.title { text-align: center; margin-bottom: 24px; color: #409eff; }
.code-row { display: flex; gap: 8px; width: 100%; }
.tips { margin-top: 12px; font-size: 12px; color: #909399; }
</style>
