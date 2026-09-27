import { createRouter, createWebHistory } from 'vue-router'
import { isTokenExpired, clearLocalAuth } from '../utils/authToken'

const routes = [
  // layout: blank = 纯净页（不渲染商城顶部栏/导航/用户区），登录/注册专用
  { path: '/login', component: () => import('../views/Login.vue'), meta: { title: '登录', public: true, layout: 'blank' } },
  { path: '/register', component: () => import('../views/Register.vue'), meta: { title: '注册', public: true, layout: 'blank' } },
  { path: '/', component: () => import('../views/Home.vue'), meta: { title: '首页' } },
  { path: '/product/:id', component: () => import('../views/ProductDetail.vue'), meta: { title: '商品详情' } },
  { path: '/shop/:id', component: () => import('../views/ShopStorefront.vue'), meta: { title: '店铺主页', public: true } },
  { path: '/cart', component: () => import('../views/Cart.vue'), meta: { title: '购物车' } },
  { path: '/checkout', component: () => import('../views/Checkout.vue'), meta: { title: '结算' } },
  { path: '/orders', component: () => import('../views/OrderList.vue'), meta: { title: '我的订单' } },
  { path: '/chat', component: () => import('../views/ChatView.vue'), meta: { title: '消息' } },
  { path: '/shop/apply', component: () => import('../views/ShopApply.vue'), meta: { title: '申请开店' } },
  { path: '/my-shop', component: () => import('../views/MyShop.vue'), meta: { title: '我的店铺' } },
  { path: '/seller', component: () => import('../views/SellerDashboard.vue'), meta: { title: '卖家中心', seller: true } },
  // 管理后台：独立布局（仅审核与审计日志，不含商城功能），/api/admin/** 由网关做角色拦截
  {
    path: '/admin',
    component: () => import('../views/AdminLayout.vue'),
    meta: { title: '管理后台', admin: true },
    children: [
      { path: 'review', component: () => import('../views/AdminReview.vue'), meta: { title: '审核中心', admin: true } },
      { path: 'logs', component: () => import('../views/AdminLogs.vue'), meta: { title: '审计日志', admin: true } },
      { path: '', redirect: 'review' }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  document.title = `${to.meta.title || '轻营'} · 轻营电商`
  let token = localStorage.getItem('token')

  // token 仍在本地但已按 exp 过期：视为未登录，立刻清除并拦到登录页，
  // 避免“过期了还能停留在受保护页面、直到操作报 401 才跳转”。
  if (token && isTokenExpired(token)) {
    clearLocalAuth()
    token = null
    if (!to.meta.public) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const user = JSON.parse(localStorage.getItem('user') || 'null')

  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 管理员仅限管理后台，任何商城页面一律重定向（含首页/商品/购物车/聊天等）
  if (user?.role === 'ROLE_ADMIN' && !to.path.startsWith('/admin')) {
    return { path: '/admin/review' }
  }
  if (to.meta.seller && user?.role !== 'ROLE_SELLER') {
    return { path: '/', query: { needSeller: '1' } }
  }
  if (to.meta.admin && user?.role !== 'ROLE_ADMIN') {
    return { path: '/', query: { needAdmin: '1' } }
  }
  return true
})

export default router
