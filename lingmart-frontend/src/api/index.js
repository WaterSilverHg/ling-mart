import request from './request'

// ============ 认证 ============
export const authApi = {
  sendCode: (phone) => request.post(`/auth/code/send?phone=${phone}`),
  register: (data) => request.post('/auth/register', data),
  login: (data) => request.post('/auth/login', data),
  me: () => request.get('/auth/user/me'),
  // 更新用户资料（昵称 / 头像，头像为上传接口返回的相对路径）
  updateProfile: (data) => request.put('/auth/user/me', data)
}

// ============ 图片上传 ============
export const uploadApi = {
  // 上传单张图片，返回可访问的相对路径（/uploads/...）
  image: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/shop/upload/image', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  }
}

// ============ 店铺 / 入驻 ============
export const shopApi = {
  apply: (data) => request.post('/shop/application', data),
  myApplication: () => request.get('/shop/application/my'),
  myShop: () => request.get('/shop/my'),
  updateShop: (data) => request.put('/shop/my', data),
  // 营业中店铺列表（首页公开浏览）
  shops: () => request.get('/shop/list'),
  // 店铺主页（公开浏览：店铺信息 + 店主公开信息 + 在售商品）
  storefront: (shopId) => request.get(`/shop/storefront/${shopId}`)
}

// ============ 聊天（买家 ↔ 店铺老板，WebSocket 实时 + REST 历史）============
export const chatApi = {
  // 发起/进入会话（返回会话详情，含对方头像/昵称/手机号）
  openConversation: (shopId) => request.post('/chat/conversation', { shopId }),
  // 我的会话列表（买家侧 + 老板侧）
  conversations: () => request.get('/chat/conversations'),
  // 会话消息历史（分页，升序）
  messages: (id, params) => request.get(`/chat/conversations/${id}/messages`, { params }),
  // 增量补拉（断线重连用）：返回 id 大于 sinceId 的消息（升序），最多 limit 条
  messagesSince: (id, params) => request.get(`/chat/conversations/${id}/messages/since`, { params })
}

// ============ 管理端（仅 ROLE_ADMIN，网关角色拦截）============
export const adminApi = {
  // 店铺侧
  applicationList: (params) => request.get('/admin/shop/applications', { params }),
  reviewApplication: (id, data) => request.post(`/admin/shop/applications/${id}/review`, data),
  shopList: (params) => request.get('/admin/shop/shops', { params }),
  reviewShop: (shopId, data) => request.post(`/admin/shop/shops/${shopId}/review`, data),
  shopAuditList: (params) => request.get('/admin/shop/audit/list', { params }),
  // 商品侧
  reviewList: (params) => request.get('/admin/product/review/list', { params }),
  reviewProduct: (id, data) => request.post(`/admin/product/review/${id}`, data),
  reviewBatch: (data) => request.post('/admin/product/review/batch', data),
  reviewByApplication: (applicationId) => request.get(`/admin/product/review/by-application/${applicationId}`),
  productAuditList: (params) => request.get('/admin/product/audit/list', { params })
}

// ============ 商品 ============
export const productApi = {
  detail: (id) => request.get(`/product/${id}`),
  myProducts: (params) => request.get('/product/my', { params }),
  create: (data) => request.post('/product', data),
  update: (id, data) => request.put(`/product/${id}`, data),
  updateStock: (id, skus) => request.put(`/product/${id}/stock`, skus),
  onShelf: (id) => request.post(`/product/${id}/onshelf`),
  offShelf: (id) => request.post(`/product/${id}/offshelf`)
}

// ============ 检索 ============
export const searchApi = {
  // 商品检索（内部/兼容，前端首页搜索不再直接展示商品）
  search: (params) => request.get('/search', { params }),
  // 店铺检索：首页搜索框按命中商品聚合出店铺，点击进店查看商品
  searchShops: (params) => request.get('/search/shops', { params })
}

// ============ 购物车 ============
export const cartApi = {
  list: () => request.get('/cart'),
  add: (data) => request.post('/cart', data),
  update: (id, params) => request.put(`/cart/${id}`, null, { params }),
  remove: (id) => request.delete(`/cart/${id}`)
}

// ============ 订单 ============
export const orderApi = {
  // 支持 Idempotency-Key 幂等头：重复提交（如超时重试）不会重复建单
  create: (data, idempotencyKey) =>
    request.post('/order', data, { headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {} }),
  pay: (orderSn) => request.post(`/order/${orderSn}/pay`),
  cancel: (orderSn) => request.post(`/order/${orderSn}/cancel`),
  ship: (orderSn) => request.post(`/order/${orderSn}/ship`),
  confirm: (orderSn) => request.post(`/order/${orderSn}/confirm`),
  // 售后退款：买家申请 / 卖家审核
  refund: (orderSn, data) => request.post(`/order/${orderSn}/refund`, data),
  reviewRefund: (orderSn, data) => request.post(`/order/${orderSn}/refund/review`, data),
  list: (params) => request.get('/order/list', { params }),
  sellerList: (params) => request.get('/order/seller/list', { params }),
  detail: (orderSn) => request.get(`/order/${orderSn}`),
  // 卖家经营统计（7d / 30d / all）
  sellerStats: (params) => request.get('/order/seller/stats', { params }),
  // 订单导出（返回 Blob，由调用方触发下载）
  sellerExport: (params) => request.get('/order/seller/export', { params, responseType: 'blob' }),
  buyerExport: (params) => request.get('/order/export', { params, responseType: 'blob' })
}
