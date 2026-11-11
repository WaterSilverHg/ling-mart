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

// ============ Agent 文档上传（开店材料）============
export const agentDocApi = {
  /**
   * 上传开店材料，服务端解析后返回元信息（含 documentId）。
   *
   * <p>正文<b>不回传</b>：它留在服务端，发送消息时凭 documentId 引用。
   * 早先是前端把表格解析成文本塞回输入框再随消息发出，那既淹没了输入框，
   * 又会撞上服务端的消息长度上限（2000 字）而直接发不出去。</p>
   *
   * @param {File} file .csv / .txt / .md / .xlsx / .docx
   * @param {string} sessionId 会话标识：文档存在这个会话下
   */
  upload: (file, sessionId) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('sessionId', sessionId)
    return request.post('/agent/doc', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
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
  productAuditList: (params) => request.get('/admin/product/audit/list', { params }),
  // 运维：重建某店铺在售商品的 ES 文档（店名/销量漂移的人工兜底）
  reindexShopEs: (shopId) => request.post(`/admin/product/shop/${shopId}/reindex-es`),
  // 运维：按手机号查用户 + 手工修正角色（角色提升失败时兜底）
  findUserByPhone: (phone) => request.get('/admin/auth/users', { params: { phone } }),
  updateUserRole: (userId, role) => request.post(`/admin/auth/users/${userId}/role`, { userId, role })
}

// ============ Agent 运维（仅 ROLE_ADMIN；路径不在网关 /api/admin/** 段，
// 由 agent 服务自行验签 JWT 并校验角色）============
export const agentAdminApi = {
  kbStatus: () => request.get('/agent/admin/kb/status'),
  kbRebuild: () => request.post('/agent/admin/kb/rebuild'),
  // topK 为空时用服务端配置值
  kbEval: (topK) => request.get('/agent/admin/kb/eval', { params: topK ? { topK } : {} }),
  // 操作审计（分页）：userId / sessionId / eventType / from / to / page / size
  audit: (params) => request.get('/agent/admin/audit', { params })
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

// ============ 收货地址簿 ============
export const addressApi = {
  list: () => request.get('/order/addresses'),
  create: (data) => request.post('/order/addresses', data),
  update: (id, data) => request.put(`/order/addresses/${id}`, data),
  remove: (id) => request.delete(`/order/addresses/${id}`),
  setDefault: (id) => request.put(`/order/addresses/${id}/default`)
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
  // 发货：必须传发货方式（1 快递 / 2 无需物流）；方式为快递时另需快递公司与运单号
  ship: (orderSn, data) => request.post(`/order/${orderSn}/ship`, data),
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
