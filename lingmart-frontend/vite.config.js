import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 轻营前端：开发服务器 5173；API 走网关 localhost:88（网关已配 CORS，无需代理）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    open: false,
    proxy: {
      // 上传图片静态资源：后端返回相对路径 /uploads/...，开发态经 Vite 代理到网关（网关再转发 shop），
      // 否则 5173 上的 <img src="/uploads/..."> 会落到 SPA 兜底路由导致图片裂图
      '/uploads': { target: 'http://localhost:88', changeOrigin: true }
    }
  }
})
