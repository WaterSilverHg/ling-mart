# 轻营 · 待办与待优化事项清单

> 更新：**2026-09-20（v0.11）**
> 汇总来源：v0.11 全项目审计（33 条优化点）、production-readiness 差距分析、历次会话遗留问题。
> 约定：`✅` = 已完成；`⚠️` = 部分完成 / 需部署配合；空白 = 待做。
> 分工：本文只列**待办**；「怎么部署/迁移/冒烟」见 [ops-checklist.md](ops-checklist.md)，
> 「生产差距分析」见 [production-readiness.md](production-readiness.md)，
> 「历次改了什么」见 [../CHANGELOG.md](../CHANGELOG.md)。

---

## 一、部署与运维（需你操作 / 环境配合）

| # | 事项 | 优先级 | 说明 / 状态 |
| --- | --- | --- | --- |
| 1 | 幂等键联合索引 | P0 | 全新库自动建（`sql/lingmart_order.sql` 已含 `uk_idempotency_buyer`）；**已初始化过的库需手动** `ALTER TABLE order DROP INDEX uk_idempotency_key, ADD UNIQUE KEY uk_idempotency_buyer(idempotency_key, buyer_id)`。执行 `scripts/reset-data.sh` 重置后可自动带上 |
| 2 | order 超时扫描复合索引 | P2 | `idx_status_create (order_status, create_time)` 已入 SQL；现有库需手动 ALTER 或重置后自动 |
| 3 | 生产必配环境变量 | P0 | `JWT_SECRET`（≥32 字节，勿用仓库默认）、`NACOS_PASSWORD`（改强密码，勿用默认 `nacos`）、`DEV_CODE_ENABLED=false`；`ZIPKIN_ENABLED=false`（默认，部署 Zipkin 后置 `true`） |
| 4 | 前端生产地址 | P1 | `lingmart-frontend/.env.production` 改为真实网关域名（当前为 localhost:88 占位） |
| 5 | 生产关闭文档与 SQL 日志 | P1 | `SPRINGDOC_ENABLED=false`；MyBatis SQL 日志在共享配置 `lingmart-common.yaml`（`log-impl: StdOutImpl`），生产可覆盖为空 |
| 6 | HTTPS / 网关前置 TLS | P1 | 生产在网关前置 Nginx / 云 LB 终止 TLS |
| 7 | `/internal/**` 网络隔离 | P1 | 内部接口仅内网可达，公网需 IP 白名单 / 独立网络 |
| 8 | 日志采集 | P1 | traceId/spanId 已入日志；接入 ELK / Loki 完成聚合 |
| 9 | 图片存储 OSS | P2 | 当前本地磁盘（`UPLOAD_DIR` 已可切换），生产接 OSS / NAS |
| 10 | MySQL / ES 定时备份 | P1 | dump + 恢复演练（见 production-readiness） |
| 11 | CI/CD 流水线 | P1 | GitHub Actions：build → test → k6 回归 → 部署 |
| 12 | 前端容器化 | P2 | 后端 8 服务已容器化（`docker-compose.business.yml`）；前端 Vue 构建 + nginx 镜像未做 |
| 13 | 压测 | P2 | k6 脚本已备（`scripts/load-test/lingmart-core.k6.js`；早期那份 JMeter `.jmx` 因下单 sampler 请求体为空已删除，见 [load-testing.md](load-testing.md)），纳入 CI 后在真实环境压测（尤其下单 Seata 链路） |
| 14 | product 新表/新列 | P0 | 本次新增 `spu.min_price` 列、`es_write_retry` 表（见 `sql/lingmart_product.sql`）；**已初始化的库需手动 `ALTER TABLE spu ADD COLUMN min_price DECIMAL(10,2)` 并执行 `CREATE TABLE es_write_retry ...`**，重置库自动带上 |
| 15 | order 退款列 | P0 | v0.5 新增 `refund_status`/`refund_reason`/`refund_reject_reason`/`refund_time` 四列（`sql/lingmart_order.sql`）；**已初始化的库需手动 ALTER 补列**，重置库自动带上 |
| 16 | v0.10/v0.11 新表与唯一键 | P0 | 存量库需执行 `sql/upgrade/2026-09-20-sale-rollback-log.sql`（`sale_rollback_log`）与 `sql/upgrade/2026-09-20-integrity-and-indexes.sql`（**三段式**：先自检重复数据→清理→加 `shop.uk_user_id`、`cart.uk_buyer_sku` 与 4 条索引）。详见 [ops-checklist.md](ops-checklist.md) 第三节 |
| 17 | 容器编排必须提供 `JWT_SECRET` | P0 | v0.11 起 `docker-compose.business.yml` 用 `${JWT_SECRET:?…}`，**未在 `.env` 设置会拒绝启动**（这是有意为之）。生成：`openssl rand -hex 32` |

---

## 二、功能与体验优化（P2 代码）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | `spu.min_price` 冗余字段 | ✅ 已完成：SPU 增 `min_price`，SKU 增删/审核生效时重算回写，列表/详情/检索/店铺主页读冗余字段（老数据回退实时计算） |
| 2 | ES 检索热点关键词缓存 | ✅ 已完成：search 纯关键词检索结果缓存 Redis（`lingmart:search:hot:*`，5 分钟 TTL，fail-open） |
| 3 | `AdminReview.vue` 拆分 | ✅ 已完成：拆为 `ApplicationReview` / `ProductReview` / `ShopInfoReview` 三个独立组件 |
| 4 | 聊天/上传内存限流 Redis 化 | ✅ 已完成：common 新增 `RedisRateLimiter`（ZSET+Lua 滑动窗口），chat 发消息、shop 上传改用 Redis 分布式限流 |
| 5 | logout 服务端失效 | ✅ 已完成：JWT 加 jti，logout 写入 Redis 黑名单（TTL=剩余有效期），网关 AuthGlobalFilter 校验（Reactive、fail-open） |
| 6 | ES 写失败补偿 + 对账 | ✅ 已完成：`es_write_retry` 补偿表 + 定时重放（指数退避、超限放弃待人工），见 production-readiness P1 |
| 7 | 全局异常处理器细化 | ✅ 已完成：新增 400/405/415/上传超限等细粒度处理与错误码（`METHOD_NOT_ALLOWED`/`UNSUPPORTED_MEDIA_TYPE`） |
| 8 | 角色提升失败手工修正入口 | ✅ 已完成：auth 新增 `/api/admin/auth/users` 管理端接口（按手机号查询 + 手工改角色），网关已加 `/api/admin/auth/**` 路由 |
| 9 | 订单导出流式化 | ✅ 已完成：Mapper 改游标（`Cursor`）+ service 逐行回调 + EasyExcel 分批写出（攒 500 行 flush），内存占用恒定 |
| 10 | 商品详情 SKU 分页 | ✅ 已完成：新增 `GET /api/product/{id}/skus` 分页接口；详情内嵌 SKU 上限 100 条 |
| 11 | `update_time` 自动填充 | 收益低：多数表 SQL 已有 `ON UPDATE CURRENT_TIMESTAMP`；建议**不做** |
| 12 | `ShopDraftDTO` / `ApplyShopDTO` 合并 | 字段重复，当前分离清晰；可统一为 `ShopProfileDTO` |
| 13 | 售后/退款流程 | ✅ 已完成（v0.5 退款 + v0.10 销量回退）：买家申请退款 → 卖家审核 → 通过则订单置「已退款」+ 库存回补 + **SPU/SKU 销量回退与 ES 修正**（`order_status=5`、`refund_status` 四态、`sale_rollback_log` 幂等）；**纠纷仲裁仍缺**（第六节 #15） |

---

## 三、安全加固（生产）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | 图片内容安全检测 | 仅校验了类型/魔数/大小；涉黄/违规内容需接检测服务 |
| 2 | 依赖漏洞扫描 | OWASP Dependency-Check / Dependabot 纳入 CI |
| 3 | 生产覆盖全部默认密钥 | JWT / Nacos / 管理员密码（`ADMIN_PASSWORD`）均需环境变量覆盖 |
| 4 | 验证码真实短信通道 | 当前 dev 模式返回明文、验证码存 Redis；生产需接第三方短信（`VerificationCodeService` 已留 TODO） |
| 5 | 手机号隐私复核 | 仅 chat 会话窗口展示手机号；确认 shop/storefront 及导出接口不泄露 |

---

## 四、可观测性

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | 指标暴露 | ✅ 已完成：`micrometer-registry-prometheus` 随 common 引入，共享配置暴露 `prometheus` 端点，各服务 `/actuator/prometheus` 可抓取 |
| 2 | 告警 | QPS / 延迟 / 错误率告警（Alertmanager）未配置（依赖 Prometheus 部署） |
| 3 | Zipkin 链路上报 | ✅ 依赖已就绪（`zipkin-reporter-brave` + `management.tracing.zipkin.*`）；默认关闭（`ZIPKIN_ENABLED=false`），部署 Zipkin 后置 `ZIPKIN_ENABLED=true` 即全链路上报 |
| 4 | Sentinel Dashboard | 规则已接 Nacos 热更新；Dashboard 监控面板未接入 |

---

## 五、测试

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | order/ware 核心逻辑单测 | ✅ 已完成：`WareServiceImplTest`（同步/锁定/解锁/扣减边界）、`OrderServiceImplTest`（幂等/状态机/超时/流式导出） |
| 2 | order 端到端集成测试 | ✅ 已完成：`OrderFlowIntegrationTest` 服务级模拟（真实 WareServiceImpl + 内存 Mapper）：建单→锁库存→支付→扣减、建单→取消→解锁 |
| 3 | 前端测试 | Vitest 单测 + Cypress E2E（登录/下单/审核主链路）待做 |
| 4 | 性能测试入 CI | k6 回归脚本接入流水线待做 |

---

## 六、v0.11 审计后未做的优化项（按「影响 × 成本」排序）

| # | 事项 | 影响 | 成本 | 说明 / 建议修法 |
| --- | --- | --- | --- | --- |
| 1 | product 未引入 Seata 客户端，其写入不是 AT 分支 | **高**（正确性） | 中 | 开店申请的 `@GlobalTransactional applyShop` 内会调 product 建 SPU/SKU；一旦全局事务回滚（如角色提升前抛错、Seata 超时），product 的写入**不会回滚**，留下挂在已回滚申请单上的孤儿商品。修法：给 product 引 seata starter 并让 `/internal/**` 参与分支，或把建商品改为「shop 事务提交后异步补偿 + `application_id` 幂等 + 定时清理孤儿」 |
| 2 | RabbitMQ 未开 publisher confirm | **高**（可靠性） | 中 | `convertAndSend` 无 confirm 时，broker 端路由失败（绑定缺失/交换机不存在）不会抛异常 → Outbox 被标记「已发」但从未入队 → 扣库存丢失（仅靠流水补偿兜底）。修法：`publisher-confirm-type: correlated` + `publisher-returns: true`，用 `CorrelationData` 决定是否置已发 |
| 3 | 5 个 `@Scheduled` 无分布式锁 | 中（多实例才暴露） | 中 | 超时扫描、Outbox 转发、ES 重放、ES 对账、角色补偿在水平扩容后会 N 倍重复执行。修法：统一「任务租约」（Redis SETNX + 续期）或引入 ShedLock |
| 4 | `/api/search/shops` 免登录且每次拉 1000 条 ES 文档、无缓存 | 中（安全/性能） | 中 | 未登录即可反复触发「1000 文档 + 内存聚合」，是 ES 与 search 的免费放大器。修法：加短 TTL 缓存 + ES 侧 `terms` 聚合，或给匿名维度限流 |
| 5 | `/api/shop/list` 无分页、`listConversations`/购物车无上限 | 中 | 中 | 店铺数上万后首页一次响应几十 MB（该接口还在白名单内，无鉴权放大）。修法：分页 + 前端懒加载 |
| 6 | 超时订单兜底扫描无 LIMIT、每单一个全局事务 | 中 | 中 | 停机后积压几万待支付订单时，一轮扫描串行开几万个 Seata 全局事务打满 TC。修法：`LIMIT 200` 分批，或先批量置状态再逐个解锁 |
| 7 | ES 对账任务逐文档 `exists` + 每条两次字典查询 | 中 | 中 | 每 5 分钟一轮、1 万在售商品 = 1 万次 ES 往返。修法：`multiGet`/`msearch` 批量判存在；分类品牌字典入内存（字典本就全表返回） |
| 8 | 下单链路每 SKU 一次 Feign，全程在全局事务内 | 中 | 中 | 买 5 件 = 6 次跨服务 RPC。修法：product 增 `POST /internal/product/sku/batch`，order 一次批量取 |
| 9 | ware 同一 SKU 一次调用内反复查库 | 低 | 中 | 一笔 5 件订单的支付扣减路径约 15 次单行查询（`isUnlimited` 甚至可能同方法查两次）。修法：入口一次 `in(skuIds)` 预加载成 Map |
| 10 | `reviewProducts` 批量审核存在自调用，内层 `@Transactional` 失效 | 低 | 中 | 目前靠外层事务兜住，但每轮循环的 Feign + ES 写在同一个长事务里；一旦外层注解被去掉会静默退化为无事务批量写 |
| 11 | CORS 用 `addAllowedOriginPattern` + `allowCredentials(true)` | 低（配置面风险） | 小 | 默认值安全，但运维写成 `*.example.com` 或 `*` 就变成「任意来源 + 携带凭据」。修法：改 `addAllowedOrigin` 精确白名单 + 启动校验不含 `*` |
| 12 | 聊天无心跳（`setHeartbeatValue` + TaskScheduler） | 低 | 小 | 靠 `reconnectDelay` + 重连补拉达到最终一致；要做需服务端与前端同时配，否则协商为 0,0 无效 |
| 13 | 聊天单机 simple broker | 中（仅多实例） | 大 | chat 多实例时 A 实例推送的消息 B 实例上的订阅者收不到；需换外部 broker（RabbitMQ STOMP relay） |
| 14 | 无未读机制；`Idempotency-Key` 永不过期 | 低 | 中 | 见第七节已知边界；前者是体验缺口，后者靠 DB 唯一键天然幂等，仅浪费存储 |
| 15 | 纠纷仲裁 / 真实支付渠道 / 实名认证 | 中 | 大 | 见 [production-readiness.md](production-readiness.md) 第六节 P0 |

---

## 七、已知边界 / 备注

| # | 说明 |
| --- | --- |
| 1 | sellerStats「状态分布」口径为**全状态**统计（含待支付/已取消），概览/趋势/热销才是 `IN(1,2,3)`；属有意为之，文档此前未说明 |
| 2 | 验证码明文 `dev-code-enabled` 本地默认 `true`（无真实短信通道）；**生产必须 `DEV_CODE_ENABLED=false`** |
| 3 | chat 会话窗口展示手机号（`PeerInfo.phone`），会话列表不展示；依赖 `/internal` 不暴露约定 |
| 4 | ~~`SEATA_IP` 硬编码为宿主机 IP（`docker-compose.yml`），换机器部署需改~~ → **已解决（v0.12 换了一种更彻底的做法）**：不再依赖 TC 往 Nacos 注册的 IP，客户端默认改为「直连 grouplist」（`SEATA_REGISTRY_TYPE=file` + `SEATA_GROUPLIST`）。此前仅参数化 `SEATA_IP` 并未真正解决问题——TC 在容器里会把 Docker 内网 IP（如 `172.18.0.9`）注册上去，宿主机服务依旧连不上 |
| 10 | Chroma 镜像未固定版本（`chromadb/chroma:latest`） | 低 | 小 | Spring AI 1.1.8 走 `/api/v2` 协议（已反编译确认），Chroma 将来大版本改 API 会直接打断 agent 启动。修法：确认当前镜像 tag 后写死（`docker images \| grep chroma`） |
| 5 | Nacos 配置中心当前为「可选导入」（`optional:` 前缀）：注册中心配置在本地，配置中心不可用不影响注册，仅丢失 mybatis/management 集中配置；**改 `lingmart-common.yaml` 后必须 `scripts/push-nacos-config.sh` 推送才生效** |
| 6 | `stock_flow_log` 卡在 `status=2`（进程中断遗留）会被定时任务**置 3 人工介入并告警，不会自动重放**（无法区分「已扣」与「未扣」，重放可能超卖）；运维需用 `POST /internal/ware/flow/manual?id=&action=reset\|done` 处置 |
| 7 | product 的 `refreshEsByShop` 单次重建上限 1000 个 SPU（超过部分需再调管理端重建入口） |
| 8 | `.env` 对**全部 9 个服务**生效（v0.11 起本地 `java -jar` 也读），里面不要放「只想影响单个服务」的键 |
| 9 | 前端内存/首屏优化未做：`/api/shop/list` 全量渲染无虚拟滚动、大列表无分页（见第六节 #5） |

---

## 八、快速参考：推荐执行顺序（非阻塞）

1. **立即（v0.11 收尾）**：`.env` 补 `JWT_SECRET` → 存量库跑两个 2026-09-20 迁移脚本（三段式那个先看第一段自检）→ 重新打包并重启全部服务 → 跑 [ops-checklist.md](ops-checklist.md) 第四节冒烟，重点验「ware 补事务后的 Seata 全局回滚」
2. **近期（1~2 周）**：第六节 #1（product 补 Seata 覆盖）、#2（publisher confirm）——两条都是「会静默丢数据」类型，优先于性能项；生产环境变量（一-3/4/17）、日志采集（一-8）、MySQL 备份（一-10）
3. **里程碑（P2 批量）**：第六节 #3~#9（分布式锁、检索/对账/下单链路的批量优化）、四-1~4 可观测性、五-1~4 测试补齐
4. **上线前**：三-1~5 安全加固、HTTPS、/internal 隔离、依赖扫描
