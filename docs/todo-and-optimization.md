# 轻营 · 待办与待优化事项清单

> 更新：**2026-11-10（v0.39 交易闭环补齐后复核）**
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
| 7 | `/internal/**` 网络隔离 | P1 | ✅ 网络层已完成（v0.38）：`docker-compose.prod.yml` 不发布业务端口；`docker-compose.business.yml` 默认只绑 `127.0.0.1`（变量 `BIND_ADDR`）。⚠️ **仍无服务间签名**——能解析到服务端口（如同机进程、被改绑的 `BIND_ADDR`）即可直调内部接口；角色写入已加枚举校验且禁止提权为管理员，但其它内部写接口仍裸奔。建议后续加内部共享密钥头 |
| 8 | 日志采集 | P1 | traceId/spanId 已入日志；接入 ELK / Loki 完成聚合 |
| 9 | 图片存储 OSS | P2 | 当前本地磁盘（`UPLOAD_DIR` 已可切换），生产接 OSS / NAS |
| 10 | MySQL / ES 定时备份 | P1 | dump + 恢复演练（见 production-readiness） |
| 11 | CI/CD 流水线 | P1 | ⚠️ 部分完成（v0.39）：`.github/workflows/ci.yml` 已接入 build → test（后端 `mvn verify` + 前端 `npm test/build`）→ 每周依赖漏洞扫描。**仍缺**：k6 回归入流水线、镜像构建与部署（需先确定目标环境与凭据） |
| 12 | 前端容器化 | P2 | 后端 8 服务已容器化（`docker-compose.business.yml`）；前端 Vue 构建 + nginx 镜像未做 |
| 13 | 压测 | P2 | k6 脚本已备（`scripts/load-test/lingmart-core.k6.js`；早期那份 JMeter `.jmx` 因下单 sampler 请求体为空已删除，见 [load-testing.md](load-testing.md)），纳入 CI 后在真实环境压测（尤其下单 Seata 链路） |
| 14 | product 新表/新列 | P0 | 本次新增 `spu.min_price` 列、`es_write_retry` 表（见 `sql/lingmart_product.sql`）；**已初始化的库需手动 `ALTER TABLE spu ADD COLUMN min_price DECIMAL(10,2)` 并执行 `CREATE TABLE es_write_retry ...`**，重置库自动带上 |
| 15 | order 退款列 | P0 | v0.5 新增 `refund_status`/`refund_reason`/`refund_reject_reason`/`refund_time` 四列（`sql/lingmart_order.sql`）；**已初始化的库需手动 ALTER 补列**，重置库自动带上 |
| 16 | v0.10/v0.11 新表与唯一键 | P0 | 存量库需执行 `sql/upgrade/2026-09-20-sale-rollback-log.sql`（`sale_rollback_log`）与 `sql/upgrade/2026-09-20-integrity-and-indexes.sql`（**三段式**：先自检重复数据→清理→加 `shop.uk_user_id`、`cart.uk_buyer_sku` 与 5 条索引）。详见 [ops-checklist.md](ops-checklist.md) 第三节 |
| 17 | 容器编排必须提供 `JWT_SECRET` | P0 | v0.11 起 `docker-compose.business.yml` 用 `${JWT_SECRET:?…}`，**未在 `.env` 设置会拒绝启动**（这是有意为之）。生成：`openssl rand -hex 32` |
| 18 | v0.27 新列与索引 | P0 | `order` 表新增 4 列（`refund_apply_time`、`delivery_mode`、`shipping_company`、`tracking_no`）+ `idx_refund_apply`。**存量库需执行** `sql/upgrade/2026-10-03-refund-and-shipping.sql`（不幂等，重复执行报 Duplicate column 属预期），重置库自动带上。⚠️ 升级后**历史的「退款中」订单 `refund_apply_time` 为 NULL，不会被超时自动同意**，仍需人工审核——刻意的安全默认 |

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
| 14 | 收货地址 | ✅ 已完成（v0.39）：`user_address` 地址簿（CRUD + 默认地址 + 上限 20 条）+ 下单快照进订单（`receiver_*` 五列）；前端新增「收货地址」页，结算页可切换地址。**未见地址簿的省市区选择器**（当前是自由文本输入），可后续接区域数据 |
| 15 | 发货超时自动确认收货 | ✅ 已完成（v0.39）：`OrderAutoConfirmJob` 每轮 `LIMIT 200`，与买家手动确认共用原子守卫，退款中跳过 |

---

## 三、安全加固（生产）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | 图片内容安全检测 | 仅校验了类型/魔数/大小；涉黄/违规内容需接检测服务 |
| 2 | 依赖漏洞扫描 | OWASP Dependency-Check / Dependabot 纳入 CI |
| 3 | 生产覆盖全部默认密钥 | ✅ 已完成（v0.38）：`DefaultCredentialGuard` 在启动期检查 JWT 密钥 / 数据库 / Nacos / RabbitMQ / 预置管理员口令 / 验证码明文开关，命中默认值即拒绝启动并列出全部违规项。`docker-compose.prod.yml` 已为 9 个服务开启；本地开发默认关闭 |
| 4 | 验证码真实短信通道 | 当前 dev 模式返回明文、验证码存 Redis；生产需接第三方短信（`VerificationCodeService` 已留 TODO） |
| 5 | 手机号隐私复核 | 仅 chat 会话窗口展示手机号；确认 shop/storefront 及导出接口不泄露 |
| 6 | 身份头伪造 | ✅ 已完成（v0.38）：`AuthGlobalFilter` 在**所有分支之前**剥离入站 `X-User-Id` / `X-User-Role`，下游只可能收到网关写入的身份（此前白名单路径是原样放行的） |
| 7 | 内部接口提权 | ✅ 已完成（v0.38）：`/internal/user/role` 禁止写入 `ROLE_ADMIN`；`updateRole` 增加角色枚举校验（此前「传什么写什么」） |
| 8 | 分页与下单入参放大 | ✅ 已完成（v0.38）：`PageSizeCapFilter` 全局封顶 `size ≤ 100`、`page ≤ 100000`（common 自动配置，Servlet 栈）；下单 `items ≤ 50`、`num ≤ 999` |

---

## 四、可观测性

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | 指标暴露 | ✅ 已完成：`micrometer-registry-prometheus` 随 common 引入，共享配置暴露 `prometheus` 端点；**v0.37 起 compose 内置 Prometheus（:9090）定时抓取 + Grafana（:3000）预置总览看板**；`http.server.requests` 已开直方图，可算 p95/p99 |
| 2 | 告警 | QPS / 延迟 / 错误率告警（Alertmanager）未配置；指标与看板已就绪，只差告警规则 |
| 3 | Zipkin 链路上报 | ✅ 依赖已就绪（`zipkin-reporter-brave` + `management.tracing.zipkin.*`）；默认开启（`ZIPKIN_ENABLED=true`），不需要时置 `false`。**v0.36 起补上 Feign 出站埋点**（`feign-micrometer`）：此前后续服务调用是彼此独立的 trace，Zipkin 里看不到服务间链路 |
| 4 | Sentinel Dashboard | 规则已接 Nacos 热更新；Dashboard 监控面板未接入 |

---

## 五、测试

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | order/ware 核心逻辑单测 | ✅ 已完成：`WareServiceImplTest`（同步/锁定/解锁/扣减边界）、`OrderServiceImplTest`（幂等/状态机/超时/流式导出） |
| 2 | order 端到端集成测试 | ✅ 已完成：`OrderFlowIntegrationTest` 服务级模拟（真实 WareServiceImpl + 内存 Mapper）：建单→锁库存→支付→扣减、建单→取消→解锁 |
| 3 | 前端测试 | ⚠️ 部分完成：Vitest 单测已纳入 CI（3 个 spec / 28 用例）；**Cypress E2E（登录/下单/审核主链路）仍未做** |
| 4 | 性能测试入 CI | k6 回归脚本接入流水线待做（需先有稳定的测试环境） |

---

## 六、未做的优化项（按「影响 × 成本」排序）

| # | 事项 | 影响 | 成本 | 说明 / 建议修法 |
| --- | --- | --- | --- | --- |
| 1 | ~~product 未引入 Seata 客户端，其写入不是 AT 分支~~ | ✅ 已完成（v0.26） | — | 排查时发现**根因比这条更大**：不只是 product 未接入，而是**整条链路都没有跨服务传递 XID**——Seata 不提供 Feign 集成，项目里也没有任何写 `TX_XID` 头的代码，所以 ware 同样不是分支，`@GlobalTransactional` 实际只有一个分支（TM 自己）。修法：lingmart-common 增 `SeataXidFeignInterceptor`（自动配置，order/shop 生效）+ product 引入 seata starter + 网关 `TxXidSanitizeFilter` 剥离外部伪造头。详见 [evolution.md](evolution.md) 第四节 1.1 与 CHANGELOG v0.26 |
| 2 | ~~RabbitMQ 未开 publisher confirm~~ | ✅ 已完成（v0.37） | — | 已开 `publisher-confirm-type: correlated` + `publisher-returns: true`，Outbox 只在「broker ack 且未被退回」时才置已发；退回消息通过 `ReturnedMessageTracker`（按 messageId 关联 `CorrelationData`）识别 |
| 3 | 8 个 `@Scheduled` 无分布式锁 | 中（多实例才暴露） | 中 | 超时扫描、退款超时、Outbox 转发、ES 重放、ES 对账、角色补偿、库存流水补偿、Agent 审计清理在水平扩容后会 N 倍重复执行。**v0.37 已把订单超时扫描独立成 `OrderTimeoutScanJob`**（顺带修掉自调用事务失效），但仍是"裸定时"。<br>三种修法，取舍见下：<br>**① 任务租约/分布式锁（推荐先做）**：Redis SETNX + 续期，或引入 ShedLock。改动小，不动部署形态，多实例下只有一个执行。<br>**② 抽出独立定时任务服务**：新增 `lingmart-job`，只跑定时任务，业务服务不再 `@Scheduled`（需删注解或用开关关掉）。要求任务执行走 HTTP/Feign 回调业务接口，否则会绕过事务与状态守卫、直连数据库造成跨库写入。<br>**③ 交给调度平台**：XXL-JOB / PowerJob，由调度中心分发，天然单点。<br>注意：①解决的是"重复执行"，②解决的是"业务实例被定时任务占用"。本项目 8 个任务都是幂等/带状态守卫的轻量扫描，**重复执行的损失主要是数据库与 ES 的无效查询**，尚未到必须拆服务的程度；先做①，等任务变重（如对账全量扫描）再考虑②。 |
| 4 | `/api/search/shops` 免登录且每次拉 1000 条 ES 文档、无缓存 | 中（安全/性能） | 中 | 未登录即可反复触发「1000 文档 + 内存聚合」，是 ES 与 search 的免费放大器。修法：加短 TTL 缓存 + ES 侧 `terms` 聚合，或给匿名维度限流 |
| 5 | `/api/shop/list` 无分页、`listConversations`/购物车无上限 | 中 | 中 | 店铺数上万后首页一次响应几十 MB（该接口还在白名单内，无鉴权放大）。修法：分页 + 前端懒加载 |
| 6 | 超时订单兜底扫描无 LIMIT、每单一个全局事务 | 中 | 中 | 停机后积压几万待支付订单时，一轮扫描串行开几万个 Seata 全局事务打满 TC。修法：`LIMIT 200` 分批，或先批量置状态再逐个解锁 |
| 7 | ES 对账任务逐文档 `exists` + 每条两次字典查询 | 中 | 中 | 每 5 分钟一轮、1 万在售商品 = 1 万次 ES 往返。修法：`multiGet`/`msearch` 批量判存在；分类品牌字典入内存（字典本就全表返回） |
| 8 | 下单链路每 SKU 一次 Feign，全程在全局事务内 | 中 | 中 | 买 5 件 = 6 次跨服务 RPC。v0.38 已用 `@Size(max=50)` 把**最坏情况**从「无上限」压到 51 次 RPC，但单量级的放大仍在。修法：product 增 `POST /internal/product/sku/batch`，order 一次批量取 |
| 9 | ware 同一 SKU 一次调用内反复查库 | 低 | 中 | 一笔 5 件订单的支付扣减路径约 15 次单行查询（`isUnlimited` 甚至可能同方法查两次）。修法：入口一次 `in(skuIds)` 预加载成 Map |
| 10 | `reviewProducts` 批量审核存在自调用，内层 `@Transactional` 失效 | 低 | 中 | 目前靠外层事务兜住，但每轮循环的 Feign + ES 写在同一个长事务里；一旦外层注解被去掉会静默退化为无事务批量写 |
| 11 | CORS 用 `addAllowedOriginPattern` + `allowCredentials(true)` | 低（配置面风险） | 小 | 默认值安全，但运维写成 `*.example.com` 或 `*` 就变成「任意来源 + 携带凭据」。修法：改 `addAllowedOrigin` 精确白名单 + 启动校验不含 `*` |
| 12 | 聊天无心跳（`setHeartbeatValue` + TaskScheduler） | 低 | 小 | 靠 `reconnectDelay` + 重连补拉达到最终一致；要做需服务端与前端同时配，否则协商为 0,0 无效 |
| 13 | 聊天单机 simple broker | 中（仅多实例） | 大 | chat 多实例时 A 实例推送的消息 B 实例上的订阅者收不到；需换外部 broker（RabbitMQ STOMP relay） |
| 14 | 无未读机制；`Idempotency-Key` 永不过期 | 低 | 中 | 见第七节已知边界；前者是体验缺口，后者靠 DB 唯一键天然幂等，仅浪费存储 |
| 15 | 纠纷仲裁 / 真实支付渠道 / 实名认证 | 中 | 大 | 见 [production-readiness.md](production-readiness.md) 第六节 P0 |
| 16 | ~~ES 对账只做单向，无法清理「幽灵文档」~~ | ✅ 已完成（v0.37） | — | 反向对账已加：`removeGhostDocs()` 按 `lingmart.es.ghost-scan-limit`（默认 1000）扫描 ES 文档，凡是 DB 不存在、未上架或未审核通过的一律删除。触发场景（全局回滚后 ES 残留）不再需要人工清理 |
| 17 | ~~`scanTimeoutOrders` 自调用导致事务注解失效~~ | ✅ 已完成（v0.37） | — | `@Scheduled` 入口已挪到独立 Bean `order/job/OrderTimeoutScanJob`，逐单经注入的 `OrderService` 代理调用 `cancelByTimeout`，`@Transactional`/`@GlobalTransactional` 恢复生效，与 MQ 路径行为一致 |
| 18 | `mvn verify` 无法一次跑完 | 中（CI/发布） | 小 | order 的测试依赖 ware 模块（test scope，复用真实 `WareServiceImpl`）。同一次反应堆里走到 `package` 时，`spring-boot-maven-plugin` 会把 ware 的 jar **原地 repackage** 成 fat jar（业务类挪进 `BOOT-INF/classes`），随后 order 编译测试就找不到那些类——表现为 `mvn verify` 在 order 的 test-compile 阶段报「找不到符号 WareServiceImpl」，而单独 `mvn test` 正常（那时 ware 只走到 test 阶段，依赖解析到 `target/classes`）。**临时对策**：CI 拆成 `mvn test` + `mvn -Dmaven.test.skip=true package` 两步。**彻底修法**：给 repackage 加 `<classifier>exec</classifier>`，让主产物保持普通 jar、fat jar 单独叫 `*-exec.jar`；代价是 `docker/business.Dockerfile`、`scripts/start-services.bat`、IDEA 运行配置与文档里所有 `lingmart-xxx.jar` 路径都要同步改成 `-exec.jar` |

---

## 七、已知边界 / 备注

| # | 说明 |
| --- | --- |
| 1 | sellerStats「状态分布」口径为**全状态**统计（含待支付/已取消），概览/趋势/热销才是 `IN(1,2,3)`；属有意为之，文档此前未说明 |
| 2 | 验证码明文 `dev-code-enabled` 本地默认 `true`（无真实短信通道）；**生产必须 `DEV_CODE_ENABLED=false`** |
| 3 | chat 会话窗口展示手机号（`PeerInfo.phone`），会话列表不展示；依赖 `/internal` 不暴露约定 |
| 4 | ~~`SEATA_IP` 硬编码为宿主机 IP（`docker-compose.yml`），换机器部署需改~~ → **已解决（v0.12 换了一种更彻底的做法）**：不再依赖 TC 往 Nacos 注册的 IP，客户端默认改为「直连 grouplist」（`SEATA_REGISTRY_TYPE=file` + `SEATA_GROUPLIST`）。此前仅参数化 `SEATA_IP` 并未真正解决问题——TC 在容器里会把 Docker 内网 IP（如 `172.18.0.9`）注册上去，宿主机服务依旧连不上 |
| 5 | Nacos 配置中心当前为「可选导入」（`optional:` 前缀）：注册中心配置在本地，配置中心不可用不影响注册，仅丢失 mybatis/management 集中配置；**改 `lingmart-common.yaml` 后必须 `scripts/push-nacos-config.sh` 推送才生效** |
| 6 | `stock_flow_log` 卡在 `status=2`（进程中断遗留）会被定时任务**置 3 人工介入并告警，不会自动重放**（无法区分「已扣」与「未扣」，重放可能超卖）；运维需用 `POST /internal/ware/flow/manual?id=&action=reset\|done` 处置 |
| 7 | product 的 `refreshEsByShop` 单次重建上限 1000 个 SPU（超过部分需再调管理端重建入口） |
| 8 | `.env` 对**全部 9 个服务**生效（v0.11 起本地 `java -jar` 也读），里面不要放「只想影响单个服务」的键 |
| 9 | 前端内存/首屏优化未做：`/api/shop/list` 全量渲染无虚拟滚动、大列表无分页（见第六节 #5） |
| 10 | **Chroma 镜像未固定版本**（`chromadb/chroma:latest`）：Spring AI 1.1.8 走 `/api/v2` 协议（已反编译确认），Chroma 将来大版本改 API 会直接打断 agent 启动。修法：确认当前镜像 tag 后写死（`docker images \| grep chroma`） |
| 11 | **ES 不是 Seata 事务参与者**：AT 的一阶段先把本地分支提交、全局回滚在其后，所以「本地事务内 / 本地提交后」触发的 ES 写入不会随全局回滚撤销。已知两处：`approveInternal` 的 index（留幽灵文档，见第六节 #16）、`SalesEsRefreshListener` 的 `AFTER_COMMIT` 异步刷新（销量值不一致，危害低）。这是分布式事务与非事务型存储的固有矛盾，**不是代码缺陷**；触发需一次全局回滚，概率低但后果不可自愈 |

---

## 八、快速参考：推荐执行顺序（非阻塞）

1. **立即（v0.11 收尾）**：`.env` 补 `JWT_SECRET` → 存量库跑两个 2026-09-20 迁移脚本（三段式那个先看第一段自检）→ 重新打包并重启全部服务 → 跑 [ops-checklist.md](ops-checklist.md) 第四节冒烟，重点验「ware 补事务后的 Seata 全局回滚」
2. **近期（1~2 周）**：第六节 #1（product 补 Seata 覆盖）、#2（publisher confirm）——两条都是「会静默丢数据」类型，优先于性能项；生产环境变量（一-3/4/17）、日志采集（一-8）、MySQL 备份（一-10）
3. **里程碑（P2 批量）**：第六节 #3~#9（分布式锁、检索/对账/下单链路的批量优化）、四-1~4 可观测性、五-1~4 测试补齐
4. **上线前**：三-1~5 安全加固、HTTPS、/internal 隔离、依赖扫描
