import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// 统一请求实例：走网关，携带 JWT，解包 R{code,msg,data}
// 网关地址经 VITE_API_BASE_URL 环境变量注入（见 .env.development / .env.production），未配置时回退本地开发地址
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:88/api',
  timeout: 15000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    // 文件下载（如订单导出 xlsx）：直接返回原始响应，由调用方处理 Blob，不走 R 解包
    if (response.config.responseType === 'blob') {
      return response
    }
    const res = response.data
    // 服务端业务错误统一处理（HTTP 200 + 业务 code）
    if (res.code !== 200) {
      if (res.code === 401) {
        redirectToLogin(res.msg || '登录已过期，请重新登录')
        return Promise.reject(new Error(res.msg))
      }
      ElMessage.error(res.msg || '操作失败')
      return Promise.reject(new Error(res.msg || '业务错误'))
    }
    return res.data
  },
  (error) => {
    // 网关对过期/无效 token 返回 HTTP 401（axios 走 error 分支），需清除本地凭证并跳转登录，
    // 否则 token 过期后会一直卡在「已登录」状态
    if (error.response?.status === 401) {
      redirectToLogin(error.response?.data?.msg || '登录已过期，请重新登录')
      return Promise.reject(error)
    }
    ElMessage.error(error.response?.data?.msg || error.message || '网络异常')
    return Promise.reject(error)
  }
)

/** 清除本地凭证并跳转登录页（携带当前路径，登录后回跳）；已在登录页时避免循环跳转 */
function redirectToLogin(msg) {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  // 同步清空 Pinia 登录态：App.vue 监听该事件调用 authStore.logout()，
  // 避免登录失效后顶部栏仍显示旧用户头像/导航（登录页为纯净布局时不可见，离开登录页前需清干净）
  window.dispatchEvent(new CustomEvent('auth:expired'))
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
  ElMessage.error(msg)
}

export default request
