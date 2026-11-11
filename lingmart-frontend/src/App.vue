<template>
  <div class="app">
    <!-- 纯净页（登录/注册）：只渲染页面自身，不含商城顶部栏/导航/用户区 -->
    <template v-if="isBlank">
      <router-view />
    </template>
    <template v-else>
    <header class="topbar">
      <div class="inner">
        <router-link to="/" class="brand">轻营商城</router-link>
        <nav class="nav">
          <router-link to="/">首页</router-link>
          <!-- 管理员仅管理后台，不展示任何商城功能入口；申请开店/我的订单等入口在用户下拉菜单。
               注意：管理端路由带 layout:'blank'，本顶栏在管理端根本不渲染，
               故这里不再需要 isAdmin 的分支（原先的「管理后台」链接已不可达，已删除）。 -->
          <template v-if="authStore.isLogin && !authStore.isAdmin">
            <router-link to="/cart">购物车</router-link>
            <router-link to="/chat">消息</router-link>
            <router-link v-if="authStore.isSeller" to="/seller">卖家中心</router-link>
          </template>
        </nav>
        <div class="user">
          <template v-if="authStore.isLogin">
            <el-dropdown @command="onCommand">
              <span class="nick">
                <el-avatar :size="28" :src="authStore.user?.avatar || undefined" style="vertical-align: middle; margin-right: 6px" />
                {{ authStore.user?.nickname || authStore.user?.phone }}
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="!authStore.isAdmin" command="profile">个人资料</el-dropdown-item>
                  <el-dropdown-item v-if="!authStore.isAdmin" command="orders">我的订单</el-dropdown-item>
                  <el-dropdown-item v-if="!authStore.isAdmin" command="addresses">收货地址</el-dropdown-item>
                  <!-- 我的店铺：所有非管理员可查看（含审核中/被拒/已开店） -->
                  <el-dropdown-item v-if="!authStore.isAdmin" command="myShop">我的店铺</el-dropdown-item>
                  <el-dropdown-item v-if="!authStore.isSeller && !authStore.isAdmin" command="apply">申请开店</el-dropdown-item>
                  <el-dropdown-item v-if="authStore.isSeller" command="seller">卖家中心</el-dropdown-item>
                  <el-dropdown-item command="help" divided>帮助</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <el-button size="small" @click="$router.push('/login')">登录</el-button>
            <el-button size="small" type="primary" @click="$router.push('/register')">注册</el-button>
          </template>
        </div>
      </div>
    </header>
    <main class="main">
      <router-view />
    </main>

    <!-- 网页浮窗 Agent(Copilot)：可问答、可引导页面操作（登录/注册纯净页不显示） -->
    <CopilotWidget />
    </template>

    <!-- 个人资料（头像上传 / 昵称修改） -->
    <el-dialog v-model="profileVisible" title="个人资料" width="420px">
      <el-form label-width="80px">
        <el-form-item label="头像">
          <ImageUploader v-model="avatarList" :limit="1" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="profileNickname" maxlength="50" placeholder="设置昵称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileVisible = false">取消</el-button>
        <el-button type="primary" :loading="profileSaving" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>

    <!-- 帮助中心 -->
    <el-dialog v-model="helpVisible" title="帮助中心" width="520px">
      <div class="help-body">
        <h4>🛒 买家</h4>
        <p>1. 在首页浏览商品，加入购物车后去结算下单。</p>
        <p>2. 下单后待支付订单可在「我的订单」中支付或取消，超时自动取消。</p>
        <p>3. 通过「消息」与卖家实时沟通（页面右下角消息入口）。</p>
        <h4>🏪 卖家</h4>
        <p>1. 点击用户菜单「申请开店」，填写店铺信息与首批商品，等待管理员审核。</p>
        <p>2. 审核进度与结果在「我的店铺」查看（审核中 / 已开店 / 被拒绝均可查看）。</p>
        <p>3. 审核通过后，「卖家中心」可管理商品、发货与经营数据统计。</p>
        <h4>❓ 遇到问题</h4>
        <p>订单问题可联系管理员；经营数据统计与订单导出见「卖家中心」。</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './store/auth'
import { authApi } from './api'
import ImageUploader from './components/ImageUploader.vue'
import CopilotWidget from './components/copilot/CopilotWidget.vue'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

// 纯净页（登录/注册，meta.layout=blank）不渲染商城顶部栏/导航/用户区
const isBlank = computed(() => route.meta.layout === 'blank')

// 登录失效（401）时：request 拦截器清 localStorage 后派发 auth:expired，
// 这里同步清空 Pinia 登录态，避免失效会话残留（顶部栏仍显示旧用户头像/导航）
function onAuthExpired() {
  authStore.logout()
}
onMounted(() => window.addEventListener('auth:expired', onAuthExpired))
onUnmounted(() => window.removeEventListener('auth:expired', onAuthExpired))

function onCommand(cmd) {
  if (cmd === 'logout') {
    authStore.logout()
    router.push('/login')
  } else if (cmd === 'orders') {
    router.push('/orders')
  } else if (cmd === 'addresses') {
    router.push('/addresses')
  } else if (cmd === 'apply') {
    router.push('/shop/apply')
  } else if (cmd === 'myShop') {
    router.push('/my-shop')
  } else if (cmd === 'seller') {
    router.push('/seller')
  } else if (cmd === 'profile') {
    openProfile()
  } else if (cmd === 'help') {
    helpVisible.value = true
  }
}

// ============ 帮助中心 ============
const helpVisible = ref(false)

// ============ 个人资料 ============
const profileVisible = ref(false)
const avatarList = ref([])
const profileNickname = ref('')
const profileSaving = ref(false)

function openProfile() {
  avatarList.value = authStore.user?.avatar ? [authStore.user.avatar] : []
  profileNickname.value = authStore.user?.nickname || ''
  profileVisible.value = true
}

async function saveProfile() {
  profileSaving.value = true
  try {
    const user = await authApi.updateProfile({
      nickname: profileNickname.value,
      avatar: avatarList.value?.[0] || null
    })
    authStore.setUser(user)
    ElMessage.success('资料已更新')
    profileVisible.value = false
  } catch (e) {
    // 拦截器已弹出错误提示
  } finally {
    profileSaving.value = false
  }
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif; background: #f5f7fa; color: #303133; }
.app { min-height: 100vh; }
.topbar { background: #fff; border-bottom: 1px solid #e4e7ed; }
.topbar .inner { max-width: 1200px; margin: 0 auto; display: flex; align-items: center; height: 56px; padding: 0 16px; }
.brand { font-size: 20px; font-weight: 700; color: #409eff; text-decoration: none; margin-right: 32px; }
.nav a { margin-right: 20px; color: #606266; text-decoration: none; font-size: 14px; }
.nav a.router-link-active { color: #409eff; font-weight: 600; }
.user { margin-left: auto; }
.nick { cursor: pointer; color: #303133; }
.main { max-width: 1200px; margin: 16px auto; padding: 0 16px; }
.card { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 1px 4px rgba(0, 0, 0, .06); }
.page-title { font-size: 18px; font-weight: 600; margin-bottom: 16px; }
.help-body h4 { margin: 14px 0 6px; color: #303133; }
.help-body h4:first-child { margin-top: 0; }
.help-body p { margin: 4px 0; color: #606266; line-height: 1.7; font-size: 13px; }
</style>
