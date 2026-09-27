import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// 图片加载失败兜底：替换为本地灰色占位图（不依赖外网占位服务）
const IMG_FALLBACK = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120"><rect width="100%" height="100%" fill="#f0f2f5"/><text x="50%" y="50%" fill="#c0c4cc" font-size="12" text-anchor="middle" dominant-baseline="middle">暂无图片</text></svg>'
)

app.directive('img-fallback', {
  mounted(el) {
    el.addEventListener('error', () => {
      el.src = IMG_FALLBACK
    }, { once: true })
  }
})

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
