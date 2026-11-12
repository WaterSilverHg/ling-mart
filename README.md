# 轻营（LingMart）C2C 交易平台

> 基于 Spring Cloud Alibaba 微服务架构的轻量级 C2C（Consumer-to-Consumer）个体交易平台。
> 任何人可注册成为卖家，发布商品并交易，强调"轻量、简易、人人可经营"。

**技术基线**：Java 21 (LTS，虚拟线程) + Spring Boot 3.5.x + Spring Cloud 2025.0.x + Spring Cloud Alibaba 2025.0.0.0

**文档导航**

| 文档 | 内容 |
| --- | --- |
| 本文 | **当前状态**：有哪些功能、流程怎么走、怎么跑起来、有哪些接口 |
| [CHANGELOG.md](CHANGELOG.md) | 按版本记录「改了什么、要你执行什么」 |
| [docs/evolution.md](docs/evolution.md) | **沿革与取舍**：为什么是现在这样、替代过什么方案、踩过什么坑 |
| [docs/ops-checklist.md](docs/ops-checklist.md) | 部署与运维清单（环境变量、迁移、冒烟、故障排查） |
| [docs/architecture-diagrams.md](docs/architecture-diagrams.md) | 架构图集（部署拓扑 / 时序 / 状态机 / ER，Mermaid） |
| [docs/agent-copilot.md](docs/agent-copilot.md) | 网页浮窗 Agent 完整设计（架构、SSE 协议、安全模型） |
| [docs/todo-and-optimization.md](docs/todo-and-optimization.md) | 待办与待优化事项（含已知边界） |
| [docs/production-readiness.md](docs/production-readiness.md) | 生产级差距分析（P0/P1/P2） |
| [docs/load-testing.md](docs/load-testing.md) | 性能测试（k6）使用说明 |

---

## 一、项目概述

- **项目名称**：轻营（LingMart）
- **目标用户**：个体商户、闲置物品转卖者、小型手工艺人
- **核心价值**：极简入驻、快速上架、安全交易

| 维度 | 目标 |
| --- | --- |
| **功能完整性** | 注册登录 → 申请开店（带商品清单）→ 人工审核 → 发布商品 → 检索 → 下单支付 → 订单管理 → 售后退款 的完整闭环 |
| **技术目标** | Spring Cloud Alibaba 微服务全套组件（Nacos、OpenFeign、Sentinel、Seata、Gateway）的实战运用 |
| **架构目标** | 按业务边界划分模块，服务间 Feign 声明式调用，故障隔离 |
| **数据目标** | 每服务独立数据库；MySQL 存核心数据，Redis 缓存热点，ES 提供全文检索 |
| **部署目标** | Docker Compose 一键编排 |

---

## 二、功能与业务流程

### 2.1 角色与权限

| 角色 | 取得方式 | 能做什么 |
| --- | --- | --- |
| 游客 | — | 可匿名浏览**首页**（店铺列表 + 搜索）与**店铺主页**（`/shop/:id`）；商品详情、购物车、下单等需登录。首页只调网关白名单内的 `/api/shop/list` 与 `/api/search/shops`，因此无需 JWT |
| 买家 `ROLE_USER` | 注册即得 | 购物车、下单、支付、取消、确认收货、申请退款、与卖家聊天 |
| 卖家 `ROLE_SELLER` | 开店申请审核通过后自动提升 | 卖家中心：管理店铺与商品、发货、处理退款、经营数据看板、订单导出 |
| 管理员 `ROLE_ADMIN` | 启动预置（`ADMIN_PHONE` / `ADMIN_PASSWORD`） | 独立管理后台（**审核中心 + 审计日志 + 运维台**，不含商城功能）。管理端接口分布在 `/api/admin/**`（auth / shop / product 三个服务）与 `/api/agent/admin/**`（Agent 的知识库与审计），网关按角色拦截，非 ROLE_ADMIN 一律 403 |

### 2.2 平台模型：审核制

**开店申请必须附带要卖的商品清单** → 管理员审核（店铺 + 商品逐条）→ 通过后店铺营业、商品可售、用户角色提升为卖家。

**宽松重审**：修改店铺信息或商品内容会进入**待重审**状态，但**旧内容照常营业/销售**，
审核通过后新内容才生效；审核被拒则丢弃草稿、旧内容继续卖。**只改库存不触发重审**。
开店之后新发布的商品同样逐个审核。

### 2.3 开店流程

```
注册登录 → 「申请开店」填店铺信息 + 首批商品清单（至少 1 个完整商品，可拖拽多图）
        → 提交（审核中/营业中禁止重复申请；被拒后可复用原店铺行重来）
        → 管理员在 /admin/review 审核
            ├─ 通过：店铺营业 + 首批商品上架 + 角色提升为 ROLE_SELLER
            └─ 拒绝：整体拒绝，可修改后重新申请
        → 「我的店铺」随时查看三种状态：审核中 / 已开店 / 被拒绝
```

### 2.4 商品与检索

- 发布商品 → **待审核**、未上架；审核通过才可上架（写 ES）。
- 编辑商品内容 → **待重审**（宽松：旧内容先卖）；上架商品写入 ES，下架删除 ES 文档。
- **可见性门禁**：公开可见 = 已上架且审核通过；非公开时仅属主可预览，其他人按"商品不存在"处理。
- **搜索以店铺为入口**：首页搜索框按关键词检索商品（ES），返回**命中的店铺卡片**
  （`/api/search/shops`，点击进店查看在售商品），商品不直接展示；搜索"手机"会聚合同类商品的店铺。
- 中文分词用 IK（`title`/`subtitle` 用 `ik_max_word` 索引 + `ik_smart` 检索）。

### 2.5 下单与库存

```
选择履约方式（到店自提 / 快递）→ 加购 / 直接下单
  → POST /api/order（Seata 全局事务：建单 + 锁库存）
  → 支付（订单置已付 + 销量累加 + 写 Outbox 事件，同本地事务）→ 异步扣减库存
  → 卖家备货完成（自提）/ 填运单发货（快递）
  → 买家取货/收货确认（或发货 N 天后系统自动确认）→ 完成
  ↘ 超时未支付 → RabbitMQ 延迟队列 + 定时扫描兜底 → 自动取消 + 解锁库存
```

- **履约方式**：项目面向自营实体店，**到店自提是默认方式且始终可选**；快递由店铺的 `support_express` 决定。
  下单时确定（`deliveryMode`：1 快递 / 2 到店自提，不传默认 2），之后不可更改。
- **收货地址**：仅快递订单需要，下单时把地址**快照**进订单（`receiver_*`）；
  自提订单改为快照门店信息（`pickup_shop_name` / `pickup_address` / `pickup_phone`）。
  两种快照都不随地址簿/店铺信息后续变动而改变。
- **订单状态**：0 待支付 → 1 已支付 → 2 已发货（自提订单显示为「待自提」）→ 3 已完成
  （买家确认，或发货超过 `lingmart.order.auto-confirm-days` 天由系统确认）/ 4 已取消 / 5 已退款。
- **幂等**：`POST /api/order` 支持 `Idempotency-Key` 请求头，相同键只建一单（前端结算页自动携带）；
  数据库按 `(idempotency_key, buyer_id)` 联合唯一兜底（跨买家可安全复用客户端幂等键，防并发重复下单）。
- **禁止自买**：下单时校验买家不得购买自己店铺的商品（防自买刷销量/信用）。
- **库存语义**：`stock = NULL` 表示**不限量**，`0..N` 表示限量（0 = 售罄）。
  真实可售/扣减以 `lingmart_ware.ware_sku` 为准（`stock - locked_stock`），
  商品表的 `stock` 仅声明"是否限量/上限"；`ware_sku.unlimited=1` 时锁定/扣减/解锁/回补全部跳过。
- **服务端取价**：下单时经 `/internal/product/sku/{skuId}` 获取商品快照，防客户端改价。

**支付→扣减的事件驱动链路**：order 在支付事务内写 Outbox 表 `order_paid_event`，
提交后由 `OrderPaidOutboxRelay`（每 5s）投递到 `order.paid.ware.queue`；
ware 的 `OrderPaidListener` 幂等扣减（同一 `(order_sn, sku)` 已扣过即跳过），
同事务写 `stock_flow_log` 并把 `ware_order_task` 置"已扣减"。
失败按退避重试、耗尽进 `order.paid.ware.dlq`；`StockFlowCompensationJob`（每 5 分钟）
兜底重放异常流水，卡死超时置"人工介入"并打 `【STOCK_FLOW_ALERT】` 告警。

**`ware_order_task` 状态机**：0 已锁定 → 1 已扣减 → 2 已解锁 / 3 已退款回补。
**退款回补只在「1 已扣减」时才加库存**。

### 2.6 发货

履约方式在**下单时**已由买家确定，卖家发货时不再选择方式，只按订单类型走对应动作：

| 订单履约方式 | 卖家动作 | 附加要求 |
| --- | --- | --- |
| 快递（`delivery_mode=1`） | 填运单发货 | 必须填 `shippingCompany` + `trackingNo` |
| 到店自提（`delivery_mode=2`） | 标记「备货完成」 | 两者**必须为空**，否则报错 |

物流信息在买家订单列表中展示；自提订单展示门店名称、地址与门店电话。

### 2.7 退款与售后

- **退款状态**（`refund_status`）：0 无退款 → 1 退款中（买家已申请）→ 2 已退款（订单置 5 + 库存回补）/ 3 退款拒绝（可重新申请）。
- 买家可在**已支付 / 已发货 / 已完成**且无退款记录时申请退款。
- 卖家通过 = 订单置「已退款」+ 库存回补 + 销量回退；拒绝需填原因。
- **卖家超时未处理则自动同意**：买家申请后超过 `lingmart.order.refund-auto-approve-ms`（默认 24 小时）
  卖家仍未处理，系统自动按「通过」处理，与卖家手动通过走**同一实现**（并发时只有一方能命中）。
  由 `RefundAutoApproveJob` 每分钟扫一轮（**只用定时扫描**：24 小时的窗口下，
  MQ 延迟队列的实时性没有意义，却要多一套队列与死信配置）。
  ⚠️ **计时锚点为空的历史申请（`refund_apply_time` 为 NULL）不会被自动同意**，仍需人工审核——
  这是刻意设计的：否则这类积压的申请会在上线后一次性被全部退掉。

### 2.8 聊天

买家 ↔ 店铺老板的实时会话：WebSocket(STOMP) 握手 `/ws`（在网关白名单内，token 经 STOMP CONNECT 头校验），
发送 `/app/chat/send` → 广播 `/topic/conversation/{id}`。

- **乐观渲染**：请求可带前端生成的 `clientMsgId`，先上屏"发送中"气泡，收到广播后按该 ID 替换为正式消息。
- **断线补齐**：重连成功后以本地最后一条消息 id 为游标调 `/messages/since` 增量补拉；
  返回条数达上限时降级为全量重载，避免中间留空洞。
- **失败回执**：限流/无权等失败不走 STOMP ERROR 帧断连，而是定向投递 `/user/queue/errors`
  （`{conversationId, clientMsgId, content, code, msg}`），前端把对应气泡标红并可一键重发。
- **频控**：默认 30 条 / 30 秒（`CHAT_SEND_MAX_PER_30S`）。

### 2.9 卖家中心

店铺信息维护（改内容走宽松重审）、发布/编辑/上下架商品、改库存、订单列表与发货、
退款审核、经营数据看板（销售额/状态分布/趋势/热销 TOP5，ECharts）、订单导出 xlsx。

### 2.10 网页浮窗 Agent（Copilot）

页面右下角的 🤖 浮窗，基于平台知识库问答，并能带用户操作页面。**完整设计见 [docs/agent-copilot.md](docs/agent-copilot.md)**。

**两类能力、两套执行模型**：

| 能力 | 执行模型 |
| --- | --- |
| **写操作**（跳转 / 点击 / 预填开店申请） | 模型只能"发起意图" → 前端渲染确认卡片 → **用户确认后由前端执行** → 回执续跑。服务端永不执行写操作 |
| **只读查询** | **免用户确认，服务端直接执行**（经网关透传用户 JWT，Agent 不做权限判断），一轮内可连查数次再作答 |

只读工具有四个：`query_my_orders`（我的订单，可按状态筛选）、`query_my_shop`（我的开店状态，零参数）、
`query_my_products`（我店铺的商品，可按上下架/审核状态筛选）、`search_shops`（搜索公开在售商品/店铺，可按价格区间）。
两类能力的判据是「**参数里有没有主体**」与「**会不会改状态**」，由 `ToolRegistry` 机械校验，
并在启动期用反射强制只读工具的参数里不含 `userId`/`buyerId` 等主体字段——让模型根本没有"查别人"这个旋钮。

**开店材料预填**：用户可粘贴清单文本，或上传文件（CSV / Excel / Word / TXT / Markdown）。
上传走 `POST /api/agent/doc`，服务端解码成文本、存在**当前会话**下（≤2MB、≤8000 字、每会话一份、随会话过期），
浮窗只显示一条附件条（文件名 + 「12 行 × 4 列」）；正文**不进输入框、不进会话历史**，
只在用户发送的那一轮注入 prompt（`<document>` 边界标签内）。
模型据此调用 `prefill_shop_application` 生成草稿，确认卡片把解析明细**原样摊给用户看**，
用户可以编辑，**提交必须用户自己点**。

> **通用填表（`fill`）刻意不开放**：它允许模型自由决定"往哪个输入框写什么值"，
> 而填表是把内容写进一个随后会被提交的表单，风险比跳转/点击高一个量级。
> 开店申请是**唯一的例外**，且只到"预填草稿"为止。

**四层约束策略**（设计说明见 [agent-copilot.md](docs/agent-copilot.md) §5.12）：

| 层 | 做法 |
| --- | --- |
| **前置路由** | 确定性规则闸门：命中元请求（索取系统提示词、越狱模式）直接回引导文案、**不调用模型**；命中覆盖式话术（"忽略之前的指令"）**不拦**，只重置上下文（这类话也是正常纠正，拦会误伤） |
| **白名单约束** | 能力 / 信息 / 路由三层白名单写进 System Prompt——把"你只有哪些"和"你根本没有哪些"都讲明白 |
| **角色重置** | 命中覆盖式话术时推进**上下文地平线**，此后不再把旧对话当依据（消息不删，只是不作为依据） |
| **注意力锚定** | 每轮在用户消息**末尾**重复一段约 150 字的硬约束提要（System Prompt 在最前面，长对话里注意力会衰减） |

四层都可用 `lingmart.agent.constraint.*` 单独关掉定位。

**模型**：对话默认 **DeepSeek（deepseek-chat）**、知识库向量化默认 **阿里云百炼（text-embedding-v3）**，
只需 `.env` 填 `DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY` 两把 Key（云上模型，无需本地 GPU）；
知识库存储依赖 ChromaDB（集合名 `lingmart_kb`）。

**检索可观测**：每次检索打一行 INFO（query 长度、命中块数、命中的来源文件名、最高相似度；**不记用户原文**）。
管理员可用 `GET /api/agent/admin/kb/eval` 跑内置评估集（`kb-eval/golden-set.json`），
得到 `recall@K` 与未命中明细——只走检索层，不消耗对话额度。

---

## 三、技术栈

### 3.1 后端核心框架

| 组件 | 技术选型 | 版本 / 备注 |
| --- | --- | --- |
| 基础语言 | Java | 21（LTS，开启虚拟线程） |
| 核心框架 | Spring Boot | 3.5.x（3.5.16） |
| 微服务框架 | Spring Cloud | 2025.0.x（2025.0.3） |
| Spring Cloud Alibaba | — | 2025.0.0.0 |
| 服务注册 / 配置中心 | Nacos | 2.4.x |
| 服务间调用 | OpenFeign | — |
| 网关 | Spring Cloud Gateway | 4.x（WebFlux / `GlobalFilter`） |
| 流量控制 / 熔断降级 | Sentinel | 1.8.9（网关路由级 + 服务注解级，无 Dashboard，规则支持 Nacos 热更新） |
| 分布式事务 | Seata | 2.5.0（AT 模式） |
| JWT 库 | JJWT | 0.12.x（链式 API，jakarta 命名空间） |
| 持久层 | MyBatis-Plus | 3.5.x（spring-boot3-starter，雪花主键） |
| 文档解析 | Apache POI | 5.2.5（Agent 读取 xlsx / docx） |
| Excel 导出 | 阿里 EasyExcel | 4.0.3（订单导出） |

### 3.2 数据与中间件

| 组件 | 用途 |
| --- | --- |
| MySQL 8.0 | 各服务独立数据库 |
| Redis 7.x | 商品/目录缓存、检索热点缓存、登录态与 JWT 黑名单、库存扣减限流（各服务自研 ZSET+Lua 滑动窗口，**未引入 Redisson**）、店名共享缓存、Agent 会话记忆 |
| Elasticsearch 8.15.x | 商品全文检索与店铺聚合（spring-data-elasticsearch；镜像内置 IK 中文分词） |
| RabbitMQ 3.13 | 订单超时取消（TTL+死信）、支付成功 Outbox 投递 |
| ChromaDB | Agent 知识库向量存储（端口 18000） |
| Zipkin 3.4 | 链路追踪（端口 9411）：接收各服务上报的 span，**数据落 Elasticsearch**（`zipkin-span-*` 索引），容器重启不丢 |
| Prometheus 2.55 | 指标采集与存储（端口 9090）：定时抓取 9 个服务的 `/actuator/prometheus` |
| Grafana 11.2 | 指标看板（端口 3000）：预置 Prometheus 数据源与「平台总览」看板 |
| 本地磁盘（`UPLOAD_DIR`） | 商品图片、店铺 logo、用户头像；**生产接 OSS 需自行替换实现**（当前无 OSS SDK 依赖） |

### 3.3 前端

| 组件 | 用途 |
| --- | --- |
| Vue 3 | 前端框架 |
| Element Plus | UI 组件库 |
| ECharts | 卖家数据统计看板图表 |
| Pinia / Vue Router | 状态管理 / 路由 |
| Axios | HTTP 请求封装、JWT 拦截 |
| STOMP.js | WebSocket 聊天 |
| Vite | 构建工具 |

---

## 四、服务模块与端口

### 4.1 核心业务服务（8 个）

| 服务名 | 模块名 | 端口 | 核心职责 |
| --- | --- | --- | --- |
| 认证中心 | `lingmart-auth` | 8000 | 用户注册/登录、JWT 签发与校验、手机号验证码 |
| 店铺服务 | `lingmart-shop` | 8100 | 商家入驻申请、店铺信息管理、店铺状态审核、店铺主页、图片上传 |
| 商品服务 | `lingmart-product` | 10001 | SPU/SKU 管理、分类/品牌、商品上下架、ES 同步、销量维护 |
| 订单服务 | `lingmart-order` | 9000 | 下单流程、购物车、订单状态流转、超时取消、退款、导出与统计 |
| 仓储服务 | `lingmart-ware` | 11000 | 库存锁定/扣减/解锁、库存流水与补偿 |
| 检索服务 | `lingmart-search` | 12000 | Elasticsearch 商品多条件检索、店铺聚合 |
| 聊天服务 | `lingmart-chat` | 13000 | 买家↔店铺老板 WebSocket(STOMP) 会话 + 消息持久化 |
| API 网关 | `lingmart-gateway` | 88 | 统一路由转发、JWT 鉴权（含登出黑名单）、CORS、路由级限流、`TX_XID` 剥离 |

### 4.2 Agent 助手服务（1 个）

| 服务名 | 模块名 | 端口 | 核心职责 |
| --- | --- | --- | --- |
| Agent 助手 | `lingmart-agent` | 14000 | 网页浮窗 Agent(Copilot)：知识库问答 + 本人数据查询（只读工具，免确认）+ 页面操作引导 + 开店材料预填 + SSE 流式（Spring AI；RAG 存 ChromaDB，会话记忆存 Redis，操作审计存 `lingmart_agent` 库） |

> ⚠️ **启动前置比其它服务多三条**——缺任一条都会起不来或带病运行，先看这里再排障：
>
> | 前置 | 缺了会怎样 | 怎么确认 |
> | --- | --- | --- |
> | `.env` 里的 `DEEPSEEK_API_KEY`（对话）与 `DASHSCOPE_API_KEY`（知识库嵌入） | 服务能起，但**一对话就报错**（401） | 看 `.env`；`verify-health.sh` 查不到 Key 的有效性，只能靠日志 |
> | ChromaDB 容器 | **直接启动失败**：Spring AI 的 `ChromaVectorStore` 在 bean 构造期就会请求它 | `verify-health.sh` 的 `ChromaDB` 项 |
> | 审计库 `lingmart_agent`（首次部署执行 `sql/lingmart_agent.sql`） | 服务照常起、照常对话，但**审计静默失效** | `verify-health.sh` 的「库结构」项 |
>
> **它不依赖任何业务库**（不连 auth/shop/product/order/ware/chat 的数据库、也不建业务表；
> 只读工具是经**网关**调它们的 HTTP 接口，见第九节），因此这三条前置与业务链路是解耦的。

### 4.3 公共模块

| 模块名 | 职责 |
| --- | --- |
| `lingmart-common` | 统一返回值 `R`、全局异常处理、JWT 工具、Redis 限流器、`X-User-Id` 取值与角色校验、`.env` 加载器、统一 logback 配置、Seata XID 传播拦截器、雪花 `Long` 的 JSON 序列化、分页参数封顶（`PageSizeCapFilter`）、默认凭据启动期守卫（`DefaultCredentialGuard`）、`/actuator` 观测排除 |

> `start-services.bat` / `verify-health.sh` / `.run/全部服务` 覆盖的就是以上 **9 个服务**。

---

## 五、快速启动

### 5.1 一键脚本（Windows）

```bat
scripts\pull-images.bat      REM 1. 拉取中间件镜像（含失效加速器的绕过方案）
scripts\start-infra.bat      REM 2. 拉起 Nacos+MySQL+Redis+ES+RabbitMQ+Seata+Zipkin+ChromaDB+Prometheus+Grafana（首次自动执行 sql/ 建库）
scripts\build.bat            REM 3. 编译打包（需本机 JDK 21）
scripts\start-services.bat   REM 4. 启动全部 9 个服务（各开一个独立窗口）
```

```bash
bash scripts/verify-health.sh          # 5. 一键体检：基础设施 + 库结构 + 9 个服务
```

> 📋 部署/运维完整清单（环境变量与密钥、首次/干净部署、数据库增量迁移、端到端冒烟、生产加固、故障排查）见
> [docs/ops-checklist.md](docs/ops-checklist.md)。启动后建议先跑 `verify-health.sh` 确认环境就绪。

### 5.2 IDEA 一键启动

`.run/` 目录内置 9 个 Spring Boot 运行配置 + 1 个复合配置「全部服务」，IDEA 打开项目后自动识别
（未出现时 `File → Reload All from Disk`）。

前置设置：

1. `File → Project Structure → Project SDK` 设为 **JDK 21**；
2. `Settings → Build → Compiler → Annotation Processors` 勾选 **Enable**（Lombok）；
3. 先 `docker compose up -d` 拉起基础设施（首次会自动执行 `sql/` 建库）。

之后点「全部服务」即可同时启动全部 9 个服务，各服务独立控制台；也可用底部 **Services** 窗口逐个管理/重启。
⚠️ 若只想跑核心链路、环境里暂时没有模型 Key 或 Chroma，请逐个启动其余 8 个，**不要用「全部服务」**。

### 5.3 手工 / 容器化启动

```bash
# 手工（等价于脚本）：需 JDK 21
docker compose up -d
JAVA_HOME=<你的JDK21路径> mvn -DskipTests clean package
java -jar lingmart-agent/target/lingmart-agent-0.0.1-SNAPSHOT.jar    # 各服务同理，或按需启动

# 容器化（业务服务与基础设施全栈）
mvn -DskipTests clean package
scripts/push-nacos-config.sh                                                 # 必须先做：推送配置到 Nacos
docker compose -f docker-compose.yml -f docker-compose.business.yml build
docker compose -f docker-compose.yml -f docker-compose.business.yml up -d
```

> ⚠️ 业务容器与 `java -jar` 宿主机启动**端口映射一致，勿同时运行**（会端口冲突）。
> 业务容器内连接基础设施用 compose 服务名（`mysql:3306` / `redis:6379` / `elasticsearch:9200` 等）。

**联调入口**：所有请求经网关 `http://localhost:88` 转发。统一 OpenAPI 入口
`http://localhost:88/v3/api-docs/merged`。

> ⚠️ **本地联调验证码**：`/api/auth/code/send` 默认返回验证码明文（`dev-code-enabled` 本地默认 `true`，
> 因无真实短信通道）。生产务必设 `DEV_CODE_ENABLED=false` 关闭。
> ⚠️ 容器编排默认 `DEV_CODE_ENABLED=false`——用容器联调时若取不到验证码，需显式设 `true`。

---

## 六、环境要求与注意事项

**硬性要求**

| 项 | 要求 | 不满足会怎样 |
| --- | --- | --- |
| **JDK** | **必须 21** | 更高版本（如 26）会出现 product/search 端口占用但 health 不响应（verify-health 报 FAIL）、Feign/Seata 异常导致开店申请**静默回滚**（申请后看不到店铺） |
| **Nacos 注册 IP** | 本地默认 `127.0.0.1`；容器编排必须用 `NACOS_DISCOVERY_IP` 覆盖为宿主机局域网 IP | Windows 虚拟网卡（`169.254.x.x`）会被 Nacos 误识别为注册 IP，导致网关/服务间首连间歇超时；容器间 Feign 报「无实例」。**换机/换网络后要重启全部服务**让新 IP 生效 |
| **`-parameters` 编译开关** | 父 POM 已配置；新增控制器参数请保持 | 缺失时 Spring MVC 无法解析未显式命名的 `@PathVariable` / `@RequestParam` / `@RequestHeader`，带此类参数的请求返回 500 `系统繁忙` |
| **Seata 客户端** | 默认「直连 TC」（`SEATA_REGISTRY_TYPE=file` + `SEATA_GROUPLIST`），**无需额外配置** | 若显式切回 `nacos` 模式，`SEATA_IP` 必须是**真实 IPv4**，填 `host.docker.internal` 这类域名会被静默忽略 |
| **`undo_log` 表** | `lingmart_shop` / `lingmart_product` / `lingmart_order` / `lingmart_ware` 四库需有 | Seata AT 回滚失败 |
| **Nacos 客户端版本** | 父 POM 固定 `nacos-client:2.5.1`，与 compose 里的服务端 2.4.x 对齐，**不要随意移除** | SCA 2025 默认带 3.0.3，与服务端跨大版本时配置查询返回空**且不报错**，共享配置（端点暴露、MyBatis-Plus 通用配置等）全部静默失效 |

**其它注意事项**

- **Nacos 鉴权**：2.4 开启鉴权后必须配置 JWT 密钥（base64，解码 ≥32 字节），经项目根 `.env` 提供。
  改 `.env` 后需重建 Nacos 容器生效：`docker compose up -d --force-recreate nacos`。
  控制台登录默认 `nacos / nacos`。
- **凭据环境化**：各服务 `application.yml` 的密钥/密码均为 `${ENV:默认值}` 占位符，
  开发用默认值，生产用环境变量覆盖（见下方环境变量表）。
- **product 端口是 10001（非 10000）**：本机 10000 端口被百度网盘后台进程占用。
  网关按 Nacos 服务名（`lb://lingmart-product`）路由，不受端口变更影响。
- **Sentinel 客户端端口各服务唯一**（8720~8723），避免同机冲突；被限流统一返回 HTTP 429 + `{"code":429,...}`。
- **健康检查**：各服务暴露 `/actuator/health`（含优雅停机 `server.shutdown: graceful`），网关已将该路径加入 JWT 白名单。
- **存量库补迁移**：MySQL 的 `docker-entrypoint-initdb.d` 只在数据目录为空时执行一次，
  老库不会自动补上后续新增的表/唯一键。执行 `scripts/migrate.sh`（按序跑 `sql/upgrade/` 下全部脚本，
  「已存在」类报错自动跳过），
  或先跑 `verify-health.sh` 看「库结构」一项缺什么。

---

## 七、配置与环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NACOS_SERVER_ADDR` | `localhost:8848` | Nacos 地址（各服务注册 + Seata 客户端发现 TC） |
| `NACOS_DISCOVERY_IP` | `127.0.0.1` | 服务注册到 Nacos 的 IP。本机开发无需配置；**容器编排必须覆盖为宿主机局域网 IP** |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | `nacos` / `nacos` | Nacos 鉴权账号 |
| `SEATA_REGISTRY_TYPE` | `file` | Seata 客户端如何找 TC：`file`=直连 `SEATA_GROUPLIST`（默认）；`nacos`=从 Nacos 的 `SEATA_GROUP` 取（此时才需要 `SEATA_IP` 正确） |
| `SEATA_GROUPLIST` | `127.0.0.1:8091` | 直连模式下的 TC 地址；容器编排由 compose 覆盖为 `seata-server:8091` |
| `SEATA_IP` | `127.0.0.1` | TC **往注册中心登记**的 IP，仅 `SEATA_REGISTRY_TYPE=nacos` 时被读到。必须是**真实 IPv4** |
| `DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD` | `localhost` / `3307` / `root` / `root` | MySQL 连接（每服务独立库） |
| `ES_URIS` | `http://localhost:9201` | Elasticsearch 地址（product/search） |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` / `RABBITMQ_VIRTUAL_HOST` | `localhost` / `5672` / `guest` / `guest` / `/` | RabbitMQ（order 发支付事件、ware 消费扣库存） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6380` / 空 | Redis（product 商品缓存 + 检索热点缓存；auth 验证码/登录限流；chat 发消息限流；shop 上传限流；gateway 退出登录黑名单；agent 会话记忆） |
| `CHAT_SEND_MAX_PER_30S` | `30` | 聊天发消息频控：单个会话每 30 秒上限（超出走 `/user/queue/errors` 回执，不断连） |
| `JWT_SECRET` | 开发默认值 | JWT 签名密钥（HS256 ≥32 字节）。启动时强制校验长度，不足 32 字节拒绝启动。**容器编排必填** |
| `JWT_EXPIRE_MINUTES` | `120` | Token 有效期（分钟） |
| `ADMIN_PHONE` / `ADMIN_PASSWORD` | `13800000000` / `admin123` | 启动预置管理员账号 |
| `AUTO_CONFIRM_DAYS` / `AUTO_CONFIRM_SCAN_MS` | `7` / `3600000` | 发货后多少天自动确认收货，以及扫描间隔（毫秒）。联调时可把天数调到 `0` 立刻生效 |
| `UPLOAD_DIR` | `./uploads` | 图片上传根目录（本地磁盘；生产接 OSS 时替换实现） |
| `DEV_CODE_ENABLED` | `true`（容器编排为 `false`） | 为 `true` 时验证码接口返回明文便于联调；**生产必须设 `false`** |
| `SPRINGDOC_ENABLED` | `true` | 生产设 `false` 关闭各服务 OpenAPI/Swagger |
| `SENTINEL_ENABLED` | `true` | Sentinel 限流总开关 |
| `SENTINEL_AUTH_QPS` / `SENTINEL_SHOP_QPS` / `SENTINEL_PRODUCT_QPS` / `SENTINEL_ORDER_QPS` / `SENTINEL_WARE_QPS` / `SENTINEL_SEARCH_QPS` | `200/100/100/100/100/50` | 网关各路由 QPS 阈值（注意每个键都带 `_QPS` 后缀） |
| `lingmart.gateway.cors-allowed-origins` | `http://localhost:5173` | 网关跨域来源白名单（逗号分隔，生产填前端域名）。**注意键名**：不存在 `CORS_ALLOWED_ORIGINS` 环境变量 |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Micrometer Tracing 采样率（0~1）；traceId/spanId 输出到日志 MDC |
| `ZIPKIN_ENABLED` / `ZIPKIN_BASE_URL` | `true` / `http://localhost:9411/api/v2/spans` | 是否上报 Zipkin 全链路 / 上报地址（UI http://localhost:9411）。两者都在 Nacos 共享配置，改完要跑 `scripts/push-nacos-config.sh` |
| `GATEWAY_PUBLIC_ADDR` | `http://localhost:88` | OpenAPI 聚合对外地址（Apifox 导入用） |
| `BIND_ADDR` | `127.0.0.1` | `docker-compose.business.yml` 中 8 个业务服务的宿主机绑定地址。默认只允许本机访问，局域网无法直达 `/internal/**`；采用「服务注册宿主机 IP、容器间经宿主机端口互访」的部署时，需设为与 `NACOS_DISCOVERY_IP` 相同的宿主机 IP |
| `LINGMART_SECURITY_STRICT_CREDENTIALS` | `false` | 置 `true` 时启动期校验默认凭据（JWT 密钥 / 数据库 / Nacos / RabbitMQ / 管理员口令 / 验证码明文），命中即**拒绝启动**并列出全部违规项。`docker-compose.prod.yml` 已为 9 个服务打开 |
| `lingmart.web.max-page-size` / `lingmart.web.max-page-number` | `100` / `100000` | 列表接口分页参数的全局上限（common 的 `PageSizeCapFilter`）；`lingmart.web.page-cap-enabled=false` 可整体关闭 |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | Grafana 管理员账号（生产务必改密码） |

### 7.1 Agent 专用

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 占位符 | 对话模型（**必填**，不填则对话 401） |
| `DASHSCOPE_API_KEY` | 占位符 | 知识库嵌入模型（百炼 text-embedding-v3，**必填**） |
| `AGENT_MEMORY_TTL_HOURS` / `AGENT_MAX_MESSAGES` | 24 / 20 | 会话保留时长 / 单会话最多消息条数 |
| `AGENT_KB_ENABLED` / `AGENT_KB_REBUILD_ON_START` / `AGENT_KB_TOP_K` | true / true / 3 | 知识库开关 / 空库时启动后台重建 / 检索返回段落数 |
| `AGENT_KB_CHUNK_SIZE` / `AGENT_KB_EMBED_BATCH_SIZE` / `AGENT_KB_OVERLAP_CHARS` | 400 / 10 / 60 | 切块大小 / **单次嵌入条数（百炼硬上限 10，调大即重建失败）** / 相邻块重叠 |
| `AGENT_AUDIT_ENABLED` / `AGENT_AUDIT_RETENTION_DAYS` / `AGENT_AUDIT_EXCERPT_CHARS` | true / 30 / 80 | 审计开关 / 保留天数 / 用户消息摘录长度（0 = 完全不记正文） |
| `AGENT_READONLY_ENABLED` | true | 只读工具总开关（关闭后退化为纯问答 + 页面操作引导） |
| `AGENT_READONLY_MAX_CALLS_PER_TURN` / `AGENT_READONLY_TURN_BUDGET_MS` / `AGENT_READONLY_MAX_CALLS_PER_SESSION` | 5 / 15000 / 50 | 只读工具三道闸门（单轮次数 / 单轮时长 / 单会话累计） |
| `AGENT_READONLY_CACHE_TTL_SECONDS` / `AGENT_READONLY_MAX_RESULT_CHARS` | 30 / 1500 | 结果短缓存（键含 userId）/ 返回文本上限 |
| `AGENT_GATEWAY_BASE_URL` | `http://localhost:88` | 只读工具调业务 API 的入口，**必须指向网关**；容器编排覆盖为 `http://lingmart-gateway:88` |
| `AGENT_DOC_ENABLED` / `AGENT_DOC_MAX_FILE_SIZE` | true / 2MB | 开店材料上传开关 / 单文件上限（须与 `spring.servlet.multipart.max-file-size` 一致） |
| `AGENT_DOC_MAX_CHARS` / `AGENT_DOC_MAX_ROWS` / `AGENT_DOC_RATE_PER_MINUTE` | 8000 / 500 / 5 | 抽取文本上限 / 表格最多读取行数 / 每用户每分钟上传次数 |
| `AGENT_CONSTRAINT_ENABLED` | true | 四层约束策略总开关 |
| `AGENT_PREROUTE_ENABLED` / `AGENT_RESET_ON_OVERRIDE` / `AGENT_ATTENTION_DIGEST` | true / true / true | 前置路由 / 角色重置 / 注意力锚定 三个子开关 |
| `AGENT_MAX_MESSAGE_LENGTH` / `AGENT_MAX_CONTEXT_CHARS` | 2000 / 30000 | 单条用户消息上限 / 单轮上下文字符预算。上传的文档**不占**这个额度 |
| `AGENT_MAX_TOOLS_PER_TURN` / `AGENT_MAX_PENDING_ACTIONS` / `AGENT_MAX_SESSIONS_PER_USER` | 1 / 5 / 50 | 单轮下发操作数 / 挂起意图上限 / 单用户会话数上限 |
| `AGENT_RATE_PER_USER_MINUTE` / `AGENT_MAX_CONCURRENT` | 10 / 32 | 每用户每分钟请求数 / SSE 最大并发 |
| `AGENT_MAX_TOOL_ITERATIONS` / `AGENT_TYPING_DELAY_MS` / `AGENT_LLM_TIMEOUT_MS` | 20 / 0 / 30000 | 单会话操作次数上限 / 打字机延迟 / 单次 LLM 超时 |
| `HTTP_CLIENT_CONNECT_TIMEOUT` / `HTTP_CLIENT_READ_TIMEOUT` | 5s / 35s | 底层 HTTP 连接/读超时（防"取消不了"的僵尸连接） |

> 📌 **`.env` 在本地也生效**：项目根 `.env` 由 `lingmart-common` 的 `DotenvEnvironmentPostProcessor` 加载，
> **9 个服务**（含本地 `java -jar` 与 IDEA 启动）都读它，优先级为
> `真实系统环境变量 > .env > application.yml 默认值`。
> `.env` 里 `KEY=value # 说明` 的行尾注释会被正确剥掉。
> 想确认加载成功：看 `/actuator/env` 里有没有名为 `dotenv-file-<路径>` 的属性源。
> 容器内没有该文件（镜像只拷 jar），自动跳过，仍由 compose 注入的环境变量提供。

### 7.2 Nacos 配置中心

`mybatis-plus` / `management` 抽取到 Nacos 共享 DataId `lingmart-common.yaml`，
9 个服务经 `spring.config.import` 引入（`optional:` 前缀，Nacos 不可用时服务仍可启动）。
**Nacos 注册中心（discovery）配置保留在本地** `application.yml`——避免"注册依赖配置中心"的鸡生蛋问题。

- 配置文件集中在 `nacos-config/`（共享配置 + Sentinel 规则）。
- 推送到 Nacos：`scripts/push-nacos-config.sh`（登录 → 逐文件发布，改配置后推送即热更新）。
- **Sentinel 动态规则**：网关与 product/order/search 的限流规则走 **Nacos 数据源**
  （DataId `sentinel-*.json`），本地 classpath 规则作为兜底。

---

## 八、数据库设计

### 8.1 库划分（每服务独立库）

| 数据库名 | 所属服务 | 核心表 |
| --- | --- | --- |
| `lingmart_auth` | 认证中心 | `user`（角色在 `user.role` 字段；**验证码存 Redis、无独立表**） |
| `lingmart_shop` | 店铺服务 | `shop`、`shop_application`、`audit_log` + `undo_log` |
| `lingmart_product` | 商品服务 | `category`、`brand`、`spu`、`sku`、`sku_images`、`audit_log`、`es_write_retry`、`sale_rollback_log` + `undo_log` |
| `lingmart_order` | 订单服务 | `order`、`order_item`、`cart`、`order_paid_event`（Outbox） + `undo_log` |
| `lingmart_ware` | 仓储服务 | `ware_sku`、`ware_order_task`、`stock_flow_log` + `undo_log` |
| `lingmart_chat` | 聊天服务 | `chat_conversation`、`chat_message` |
| `lingmart_agent` | Agent | `agent_audit_log`（操作审计）。**唯一落库的数据**——会话记忆/挂起态/限流仍在 Redis；该库不可达时 Agent 仍可启动与对话 |

> `undo_log` 由 Seata AT 使用，**引入 Seata 客户端的 shop / order / ware / product 四库有**。
> search / chat / auth 不参与全局事务，故无此表；agent 的审计表是 append-only 单表写入，也不参与。
> 完整表结构以 `sql/lingmart_*.sql` 为准。

### 8.2 核心表关键字段

**user（用户表）**
`id`、`phone`（唯一）、`password`（BCrypt）、`nickname`、`avatar`、`role`（`ROLE_USER`/`ROLE_SELLER`/`ROLE_ADMIN`）、`status`

**shop（店铺表）**
- `id`、`user_id`（店主，**唯一键 `uk_user_id`**：一个用户只能有一家店铺）、`shop_name`（唯一）、`shop_logo`、`description`
- 履约：`support_express`（1 支持快递 / 0 **仅到店自提**，默认 0）、`address`（门店地址=自提点）、`contact_phone`（门店电话）
- `status`（**营业状态**：0 未启用 / 1 营业中 / 2 已禁用）与 `review_status`（**审核状态**：0 待审核 / 1 已通过 / 2 已拒绝 / 3 待重审）是**两个独立字段**——没有"审核中"这种 `status` 取值
- `draft_content`：待审核的新店铺信息草稿（JSON）；宽松重审下旧信息照常营业，通过后才生效

**sku（商品 SKU 表）**
- `id`、`spu_id`、`shop_id`（**数据隔离关键字段**）、`title`、`price`、`stock`、`sale_count`、`status`（上架/下架）
- 注意：**`category_id` 在 `spu` 表，不在 `sku`**
- `stock` 语义：**NULL = 不限量；0..N = 限量可售（0 = 售罄）**。真实可售/扣减以 `lingmart_ware.ware_sku` 为准

**order（订单表）**
- `id`、`order_sn`（唯一）、`buyer_id`、`shop_id`、`total_amount`、`pay_status`、`order_status`、`create_time`
- 退款：`refund_status`（0 无 / 1 待审核 / 2 已通过 / 3 已拒绝）、`refund_reason`、`refund_reject_reason`、
  `refund_apply_time`（**申请时间**，超时自动同意的计时锚点）、`refund_time`（**处理时间**）。两者语义不同，别混用
- 履约：`delivery_mode`（**下单时确定**：1 快递 / 2 到店自提；NULL = v0.40 之前的存量订单）、`shipping_company`、`tracking_no`、`ship_time`
- 收货（**下单快照**，仅快递）：`address_id`（仅留痕）、`receiver_name`、`receiver_phone`、`receiver_region`（省市区）、`receiver_detail`
- 自提（**下单快照**，仅自提）：`pickup_shop_name`、`pickup_address`、`pickup_phone`
- 幂等：`uk_idempotency_buyer(idempotency_key, buyer_id)`
- `order_status`：0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 / 5 已退款

**user_address（收货地址簿表）**
- `id`、`user_id`、`receiver_name`、`receiver_phone`、`province`、`city`、`district`、`detail_address`、`is_default`
- 与 `order` **不是外键关系**：下单时把地址快照进订单，之后改/删地址簿不影响已生成的订单
- 默认地址唯一性由应用层事务保证（MySQL 无部分索引，`is_default` 大量为 0 不能进联合唯一键）；单用户上限 20 条

**ware_order_task（库存任务表）**
`status`：0 已锁定 / 1 已扣减 / 2 已解锁 / 3 已退款回补——退款回补只在「1 已扣减」时才加回库存

**stock_flow_log（库存流水表）**
`status`：0 待处理 / 1 已完成 / 2 补偿中 / 3 人工介入（补偿任务每 5 分钟重放 status=0；卡在 2 超时会置 3 告警）

### 8.3 索引设计要点

- **订单表**：`order_sn`（唯一）、`uk_idempotency_buyer`（唯一）、`buyer_id`、`order_status`、`idx_status_create(order_status, create_time)`（超时扫描）
- **商品表**：`spu` 上有 `shop_id`、`category_id`、`status`、`review_status`、`application_id`，
  以及 `idx_status_review_create(status, review_status, create_time)`（首页/检索）、
  `idx_shop_status_review(shop_id, status, review_status, create_time)`（店铺主页）
- **店铺表**：`shop_name`（唯一）、`user_id`（唯一）
- **购物车/会话**：`cart.uk_buyer_sku(buyer_id, sku_id)`、`chat_conversation.uk_buyer_shop(buyer_id, shop_id)`（防重复）
- **审计日志**：`audit_log` 的 `action`、`target_id`、`create_time`（管理端按时间倒序翻页）
- **用户表**：`phone`（唯一）

---

## 九、服务间调用关系

```
                    前端（Vue3 :5173）
                            │ HTTP / WS
                            ▼
              lingmart-gateway (:88)  JWT 鉴权 + 路由 + CORS + 限流
                            │
    ┌───────────┬───────────┼───────────┬───────────┬──────────┐
    ▼           ▼           ▼           ▼           ▼          ▼
  auth       shop       product       order       search      chat
  :8000      :8100      :10001        :9000       :12000     :13000
                             │           │
                             │ Feign     │ Feign（Seata 全局事务，XID 经请求头传播）
                             ▼           ▼
                          ware ◄─────────┘
                         :11000

  lingmart-agent (:14000) —— 独立于业务链路：
    · 只读工具经网关转发业务 API（透传用户 JWT，自身不做权限判断）
    · 知识库 ChromaDB，会话记忆 Redis，审计 MySQL(lingmart_agent)
```

**关键调用**：

- `shop` → `auth`（审核通过后提升用户角色）、`product`（审核申请时批量建商品）
- `product` → `ware`（发布/更新商品时同步库存）、`shop`（取店铺做数据隔离、店名）。
  **product 不经 search 服务**——它自己用 `ElasticsearchOperations` 读写 ES 索引，两模块之间没有 Feign
- `order` → `product`（取 SKU 快照防改价、支付后累加销量）、`ware`（锁/扣/解锁库存）、`shop`（取店铺信息）
- `chat` → `auth`（批量取用户信息）、`shop`（校验店铺）——会话列表用批量接口避免 N+1
- `shop` 的店铺主页聚合 `product`（在售商品）与 `auth`（店主公开信息）

管理端 `/api/admin/**` 由网关按角色拦截（仅 ROLE_ADMIN）。
更详细的部署拓扑 / 时序 / 状态机 / ER 图见 [docs/architecture-diagrams.md](docs/architecture-diagrams.md)。

**请求头约定**：登录后携带 `Authorization: Bearer <token>`；网关校验通过后向下游写入 `X-User-Id`、`X-User-Role`。
另有**仅在服务间生效**的 `TX_XID`（Seata 全局事务 ID）：由 `SeataXidFeignInterceptor` 在跨服务调用时写入，
被调方据此加入全局事务；**网关会剥离外部请求携带的同名头**（防伪造事务 ID）。

**链路追踪**：Micrometer Tracing 自动把 `traceparent`/B3 头带到下游；**Feign 出站由
`feign-micrometer` 负责埋点与透传**——缺了它，Feign 调用不建 client span、也不传 trace 头，
被调服务会另起一条独立 trace，Zipkin 里看起来就像"没有服务间调用"（chat/order/product/shop/ware
五个用 Feign 的服务都已引入）。

Zipkin 的数据**落 Elasticsearch**（`zipkin-span-YYYY-MM-DD` 索引），容器重启不丢；
`/actuator/**`（健康检查、Prometheus 抓取）**默认不产生 trace**——否则每 15 秒一次抓取会把
追踪列表刷满，看不出真实业务链路（开关 `lingmart.tracing.exclude-actuator`，默认 `true`）。

**指标**：各服务暴露 `/actuator/prometheus`，由 `docker compose up -d` 里的 Prometheus（:9090）
每 15 秒抓取一次；Grafana（:3000，账号见 `GRAFANA_ADMIN_*`）预置了「平台总览」看板
（在线率 / 请求速率 / P95 / 5xx / 堆内存 / 连接池 / Agent Token）。

---

## 十、接口一览

所有接口经网关 `http://localhost:88` 访问。

### 10.1 认证（auth）

| 方法 | 路径 | 说明 | 登录 |
| --- | --- | --- | --- |
| POST | `/api/auth/code/send` | 发送验证码（`DEV_CODE_ENABLED=true` 时返回明文 devCode） | 否 |
| POST | `/api/auth/register` | 注册（手机号 + 验证码 + 密码 + 昵称） | 否 |
| POST | `/api/auth/login` | 登录，返回 JWT + 用户信息 | 否 |
| GET | `/api/auth/user/me` | 查询当前用户信息 | 是 |
| PUT | `/api/auth/user/me` | 更新个人资料（昵称/头像） | 是 |
| POST | `/api/auth/logout` | 退出登录（`jti` 写入 Redis 黑名单，网关后续校验命中即 401） | 是 |
| GET | `/api/admin/auth/users?phone=` | 按手机号查询用户 | 管理员 |
| POST | `/api/admin/auth/users/{userId}/role` | 手工修正用户角色（角色提升失败的兜底入口） | 管理员 |

公开白名单：`/api/auth/register`、`/api/auth/login`、`/api/auth/code/send`。

### 10.2 店铺（shop）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/api/shop/application` | 提交入驻申请（**必须附商品清单 + 门店地址/电话**）；审核中/营业中禁再申请，被拒后可复用重来 | 用户 |
| GET | `/api/shop/application/my` | 我的申请状态 | 用户 |
| GET | `/api/shop/my` | 我的店铺 | 用户 |
| PUT | `/api/shop/my` | 更新店铺信息 → 宽松重审 | 用户 |
| GET | `/api/shop/storefront/{shopId}` | 店铺主页（店铺信息 + 履约标签/自提点 + 店主公开信息 + 在售商品） | 公开 |
| GET | `/api/shop/{shopId}/fulfillment` | 店铺履约能力（能否快递、自提地址与门店电话），结算页用 | 公开 |
| GET | `/api/shop/list` | 营业中店铺列表 | 公开 |
| POST | `/api/shop/upload/image` | 图片上传（拖拽多图/店铺 logo/头像统一入口，本地磁盘，每用户 1 分钟限 20 张） | 用户 |
| GET | `/api/admin/shop/applications` | 入驻申请列表（可按状态过滤） | 管理员 |
| POST | `/api/admin/shop/applications/{id}/review` | 审核申请（通过→店铺营业 + 商品通过上架 + 角色提升；拒绝→整体拒绝） | 管理员 |
| GET | `/api/admin/shop/shops` | 店铺列表（按审核状态过滤，用于信息变更重审） | 管理员 |
| POST | `/api/admin/shop/shops/{shopId}/review` | 审核店铺信息变更（通过→应用新信息并重建该店 ES 文档；拒绝→丢弃草稿） | 管理员 |
| GET | `/api/admin/shop/audit/list` | 审核审计日志（分页） | 管理员 |

### 10.3 商品（product）

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/product/category/list` | 分类列表（分类维度已下线，接口保留） | 用户 |
| GET | `/api/product/brand/list` | 品牌列表 | 用户 |
| POST | `/api/product` | 发布商品 → **待审核**，未上架 | 卖家 |
| PUT | `/api/product/{id}` | 编辑商品内容 → **待重审** | 卖家 |
| PUT | `/api/product/{id}/stock` | 仅改库存（**不触发重审**） | 卖家 |
| POST | `/api/product/{id}/onshelf` | 上架（**仅审核通过的商品**可上架，写 ES） | 卖家 |
| POST | `/api/product/{id}/offshelf` | 下架（删除 ES 文档） | 卖家 |
| GET | `/api/product/{id}` | 商品详情（含店铺名与审核状态；**可见性门禁**） | 用户/卖家 |
| GET | `/api/product/my` | 我的商品列表（含审核状态） | 卖家 |
| GET | `/api/product/list` | 已通过审核且在售的商品列表 | 用户 |
| GET | `/api/product/{id}/skus` | 商品 SKU 分页（详情内嵌 SKU 上限 100 条，超出走此接口） | 用户 |
| GET | `/api/admin/product/review/list` | 待审商品列表（status=0 新商品 / 3 内容重审，分页） | 管理员 |
| POST | `/api/admin/product/review/{id}` | 审核商品（通过→上架+应用草稿；拒绝→丢弃草稿） | 管理员 |
| POST | `/api/admin/product/review/batch` | 批量审核商品 | 管理员 |
| GET | `/api/admin/product/review/by-application/{id}` | 按入驻申请查其携带的商品 | 管理员 |
| GET | `/api/admin/product/audit/list` | 审核审计日志（分页） | 管理员 |
| POST | `/api/admin/product/shop/{shopId}/reindex-es` | 人工重建某店铺在售商品的 ES 文档（店名/销量修正兜底） | 管理员 |
| POST | `/api/admin/product/category` / `/brand` | 新增分类 / 品牌 | 管理员 |

### 10.4 购物车与订单（order）

> `ware` **没有对外接口**：`/api/ware/**` 的路由虽在，但控制器端点已注释下线，
> 它只被 order / product 经 Feign 调用（见 10.8）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/cart` | 加入购物车 |
| GET | `/api/cart` | 购物车列表 |
| PUT | `/api/cart/{id}` | 更新购物车条目（数量/勾选） |
| DELETE | `/api/cart/{id}` | 删除购物车条目 |
| POST | `/api/order` | 下单（Seata：建单 + 锁库存），返回订单号；支持 `Idempotency-Key`。`deliveryMode`：1 快递 / 2 到店自提（**不传默认自提**）；快递需 `addressId`（不传取默认地址）且店铺须支持快递；自提无需地址 |
| GET | `/api/order/addresses` | 收货地址列表（默认地址排最前） |
| POST | `/api/order/addresses` | 新增收货地址（首个地址自动设为默认，单用户上限 20 条） |
| PUT | `/api/order/addresses/{id}` | 修改收货地址（仅本人才可改） |
| DELETE | `/api/order/addresses/{id}` | 删除收货地址（删默认地址时自动提升最近新增的一条） |
| PUT | `/api/order/addresses/{id}/default` | 设为默认地址 |
| POST | `/api/order/{orderSn}/pay` | 模拟支付（触发异步扣库存） |
| POST | `/api/order/{orderSn}/cancel` | 取消订单（解锁库存） |
| POST | `/api/order/{orderSn}/ship` | 卖家发货：快递订单填 `shippingCompany` + `trackingNo`；自提订单什么都不填（表示「备货完成，可来取货」）。`deliveryMode` 仅对存量订单生效，传了必须与订单一致 |
| POST | `/api/order/{orderSn}/confirm` | 买家确认收货 |
| POST | `/api/order/{orderSn}/refund` | 买家申请退款 |
| POST | `/api/order/{orderSn}/refund/review` | 卖家审核退款（通过 = 订单置「已退款」+ 库存回补 + 销量回退；拒绝需填原因） |
| GET | `/api/order/list` | 买家订单列表（分页） |
| GET | `/api/order/seller/list` | 卖家订单列表（按店铺过滤，分页） |
| GET | `/api/order/{orderSn}` | 订单详情（买家或所属卖家可看，其余 403） |
| GET | `/api/order/export?days=N` | 买家订单导出 xlsx（流式） |
| GET | `/api/order/seller/export?days=N` | 卖家订单导出 xlsx |
| GET | `/api/order/seller/stats?range=7d\|30d\|all` | 卖家经营看板（概览/状态分布/趋势/热销 TOP5） |

### 10.5 检索（search）

`/api/search/**` **免登录**（网关白名单内，不校验 JWT），前端首页路由同样是公开的，
游客可直接搜索并浏览店铺卡片（见 2.1）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/search?keyword=&categoryId=&minPrice=&maxPrice=&sort=&page=&size=` | 商品检索（内部/兼容，前端首页不直接展示商品） |
| GET | `/api/search/shops?keyword=&categoryId=&minPrice=&maxPrice=&page=&size=` | **店铺检索（首页搜索）**：按命中商品聚合出店铺（shopId/shopName/在售数/起售价/代表图） |

### 10.6 聊天（chat）

| 方法 / 通道 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/conversation` | 发起/进入会话（`{shopId}`），返回会话详情 |
| GET | `/api/chat/conversations` | 我的会话列表（买家侧 + 老板侧） |
| GET | `/api/chat/conversations/{id}/messages` | 消息历史（分页，升序） |
| GET | `/api/chat/conversations/{id}/messages/since` | 增量补拉（`sinceId` + `limit`，默认 100、上限 200），供重连补齐 |
| WS | `/app/chat/send` → 广播 `/topic/conversation/{id}` | STOMP 实时发送；可带 `clientMsgId` 支持乐观渲染 |
| WS | `/user/queue/errors` | 发送失败回执（定向投递，前端标红并可一键重发） |

### 10.7 Agent（agent）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/agent/chat` | 用户 | SSE 对话（核心），事件：`message_start / content_delta / tool_run / tool_result / tool_call / message_end / error` |
| POST | `/api/agent/doc` | 用户 | 上传开店材料（multipart：`file` + `sessionId`），返回 `{documentId, name, summary, chars, truncated}` |
| GET | `/api/agent/session/latest` | 用户 | 该用户最近活跃的会话 ID（localStorage 为空时接回上次对话） |
| GET | `/api/agent/session/{sessionId}/history` | 用户 | 历史恢复 |
| GET | `/api/agent/session/{sessionId}/pending` | 用户 | 取挂起（已发起未确认）的操作卡片 |
| DELETE | `/api/agent/session/{sessionId}` | 用户 | 清空会话（记忆 + 挂起态 + 操作计数 + 会话文档） |
| POST | `/api/agent/admin/kb/rebuild` | 管理员 | 重建知识库 |
| GET | `/api/agent/admin/kb/status` | 管理员 | 知识库状态 |
| GET | `/api/agent/admin/kb/eval?topK=` | 管理员 | 知识库**召回评估**：用内置评估集跑一遍检索层，返回 `recall@K`、空召回数与未命中明细（**不调用模型**，结果可重复） |
| GET | `/api/agent/admin/audit` | 管理员 | 操作审计查询 |

> **改了 `kb/` 下的用户文档后必须重建知识库**，否则助手仍按旧文档回答：
> `POST /api/agent/admin/kb/rebuild`。
>
> 以上 `admin` 接口都可以在管理后台 **「运维台」（`/admin/ops`）** 里点击操作，不必 curl——
> 包括知识库状态/重建、召回评估、操作审计查询、用户角色修正、店铺 ES 索引重建。

### 10.8 服务间内部接口（`/internal/**`，不对公网暴露）

网关只路由 `/api/**`，故请求不会经网关进入这些接口。

> ⚠️ 「网关不转发」不等于「对外不可达」：接口本身**没有鉴权**，隔离完全依赖端口可达性。
> 因此部署侧做了两层约束——`docker-compose.prod.yml` 不发布业务端口、
> `docker-compose.business.yml` 默认只绑定 `127.0.0.1`；
> 业务侧也做了自保——`/internal/user/role` **禁止写入 `ROLE_ADMIN`**（v0.38）。

- **auth**：`/internal/user/role`、`/internal/user/{id}`、`/internal/user/batch`
- **shop**：`/internal/shop/{shopId}`、`/internal/shop/by-user/{userId}`、`/internal/shop/batch`
- **product**：`/internal/product/sku/{skuId}`、`/internal/product/batch`、
  `/internal/product/application/{id}/approve|reject`、`/internal/product/sale`（支付后累加销量）、
  `/internal/product/sale/rollback`（退款后回退销量，`order_sn` 幂等）、`/internal/product/shop/{shopId}/reindex-es`、
  `/internal/product/shop/{shopId}`（某店铺在售商品，供店铺主页调用）
- **ware**：`/internal/ware/lock|deduct|unlock|restock`、`/internal/ware/sku/sync`、`/internal/ware/stock/batch`、
  `/internal/ware/stock/{skuId}`（单个 SKU 库存快照）、
  `/internal/ware/flow/human`（查人工介入流水）、`/internal/ware/flow/manual`（人工处置）

---

## 十一、前端

`lingmart-frontend`：Vite + Vue3 + Vue Router + Pinia + Axios + Element Plus + ECharts + STOMP.js。

```bash
npm install
npm run dev      # 端口 5173
npm test         # Vitest 单测
npm run build
```

API 直连网关 `http://localhost:88/api`（网关已配 CORS，请求拦截器自动带 JWT）。

**页面**：首页搜索（店铺聚合）、商品详情、店铺主页、购物车、结算下单、订单列表、**收货地址**、申请开店、我的店铺、
卖家中心（店铺/发布/上下架/发货/退款审核/经营看板）、消息、管理后台（审核中心 / 审计日志 / **运维台**），
另有登录/注册两个纯净页。
**浮窗组件**在 `src/components/copilot/`：登录/注册等纯净页不渲染。

环境变量：`lingmart-frontend/.env.development` 的 `VITE_API_BASE_URL` / `VITE_WS_URL`（默认指向本地网关）。

---

## 十二、测试

| 层级 | 位置 | 说明 | 运行方式 |
| --- | --- | --- | --- |
| 单元测试 | 各服务 `src/test` | JUnit5 + Mockito，不依赖数据库与中间件 | `mvn test`（默认执行） |
| 服务级集成测试 | `lingmart-order/.../OrderFlowIntegrationTest` | 真实 `WareServiceImpl` + 内存 Mapper，模拟 Seata 流程（建单→锁库存→支付→扣减 / 建单→取消→解锁） | 随 `mvn test` |
| 熔断/降级测试 | product / search / order 的 Sentinel blockHandler 单测 + 规则文件解析校验 | 直接验证降级行为与规则文件可加载 | 随 `mvn test` |
| 集成测试 | `lingmart-auth/.../AuthApiIT` | REST Assured 对运行中的 auth(8000) 发真实请求 | 启动 auth 后：`mvn test -Dtest=AuthApiIT -Dsurefire.failIfNoSpecifiedTests=false -pl lingmart-auth -am` |
| 性能测试 | `scripts/load-test/lingmart-core.k6.js`（+ `seed/` 播种与清理） | 四场景：只读（检索/店铺主页/详情）、购物车闭环、下单取消闭环、下单支付；每请求带 `flow` 标签 → 报告可看分链路 p95 | 见 [docs/load-testing.md](docs/load-testing.md)（**先播种再压**） |
| 前端单测 | `lingmart-frontend/src/__tests__` | Vitest + @vue/test-utils + jsdom；mock 网络与 Element Plus | `cd lingmart-frontend && npm test` |
| 分布式事务验证 | `scripts/verify-seata-rollback.sh` | **故障注入**：锁库存后强制失败，查库对比 `locked_stock` / `ware_order_task` / `undo_log` 是否被回滚干净 | 见 CHANGELOG v0.37（order 需带故障注入开关） |
| 持续集成 | `.github/workflows/ci.yml` | 推送/PR 触发：后端 `mvn verify`（编译 + 全部单测 + 打包）、前端 `npm ci && npm test && npm run build`；每周一额外跑依赖漏洞扫描 | GitHub Actions 自动执行 |

**覆盖场景**（要点）：

- **JWT**：签发-解析往返、合法/篡改/过期/错误密钥校验
- **认证**：注册/登录/当前用户/全链路；logout 后 token 加入黑名单（jti）、非法 token 静默忽略
- **开店申请**：首次创建、营业中/审核中/已禁用禁止、被拒后复用原店铺行重来、必须携带商品
- **入驻审核**：通过（传店铺名避免回调 + 角色提升 + 审计）、拒绝（不提升角色）、非管理员 403
- **商品**：开店批量建商品、按申请审核通过、单商品审核拒绝、商品不存在
- **检索**：空索引返回空结果（不报"系统繁忙"）、Sentinel 限流降级返回空
- **仓储**：库存同步、锁定库存不足抛异常、解锁幂等、解锁/扣减行数 0 抛异常
- **订单**：下单幂等、禁止自买、支付/取消/确认/发货状态机、超时兜底、流式导出
- **订单↔仓储流程**：建单锁库存 → 支付扣减 / 取消解锁
- **通用**：`R.checkOk` 下游错误透传（含 Integer 比较引用陷阱的回归用例）、Sentinel 规则文件可加载
- **前端**：auth 登录态、request 拦截器（Bearer 注入 / R 解包 / 401 跳登录 / Blob 直通）、
  ImageUploader（单图与多图两种模式的校验、排序、删除、数量上限）

> `AuthApiIT` 以 `*IT` 结尾，`mvn test` 不会自动执行；auth 未启动时自动跳过，不会导致构建失败。
> Windows 控制台默认代码页 936(GBK) 而 JVM 输出 UTF-8，`mvn test` 里的中文会乱码——先执行 `chcp 65001` 即可，
> 仅影响可读性、不影响结果。

---

## 十三、接口文档（OpenAPI / Apifox）

各服务均集成 springdoc OpenAPI，并通过网关统一聚合，**推荐使用统一入口**：

- **统一 OpenAPI JSON（Apifox 一键导入）**：`http://localhost:88/v3/api-docs/merged`
  - 网关并行拉取各服务文档并合并，路径取并集，已过滤 Feign 内部接口（`/internal/**`），服务地址统一指向网关。
  - 单个服务未启动时自动跳过，其余服务仍正常导出。
- **统一 Swagger UI**：`http://localhost:88/swagger-ui.html`（右上角下拉切换各服务）
- **各服务独立文档**（调试或单独导入用）：`http://localhost:8000/v3/api-docs`（auth）、
  `:8100`（shop）、`:10001`（product）、`:11000`（ware）、`:9000`（order）、`:12000`（search）、
  `:13000`（chat）、`:14000`（agent）

**导入 Apifox**：新建/打开项目 → 「导入数据」→ 「OpenAPI (Swagger)」→ **URL 导入**填
`http://localhost:88/v3/api-docs/merged`，一次导入全部服务接口。文档已声明 JWT Bearer 鉴权，
在 Apifox 环境变量中配置 `Authorization: Bearer {{token}}`（token 由 `/api/auth/login` 返回）后可直接调试。

> 网关需先启动（依赖 Nacos）；文档路径已加入网关 JWT 白名单，无需登录即可访问。

---

## 十四、项目结构

```text
ling-mart/                          # Git 仓库根目录
├── pom.xml                         # 父工程（Maven 多模块，统一版本管理）
├── lingmart-common/                # 公共模块
│   └── src/main/java/com/lingmart/common/
│       ├── result/                 # 统一返回 R、状态码枚举
│       ├── exception/              # 业务异常、全局异常处理器
│       ├── jwt/                    # JWT 工具 + 密钥启动校验
│       ├── constant/               # 认证常量（请求头/角色/白名单）
│       ├── seata/                  # XID 跨服务传播（Feign 拦截器 + 自动配置）
│       ├── config/                 # .env 加载器、logback、Jackson 长整型序列化
│       └── util/                   # RedisRateLimiter 等
├── lingmart-gateway/               # API 网关 :88（JWT 白名单鉴权、CORS、TX_XID 剥离）
├── lingmart-auth/                  # 认证中心 :8000（注册/登录/验证码/用户信息 + 管理端角色修正）
├── lingmart-shop/                  # 店铺服务 :8100（入驻/审核/店铺 + 图片上传 + Feign 提升角色）
├── lingmart-product/               # 商品服务 :10001（分类/品牌/SPU-SKU + 上下架 + ES 同步 + 销量）
├── lingmart-order/                 # 订单服务 :9000（购物车/下单/支付/发货/退款 + Seata + MQ + 导出统计）
├── lingmart-ware/                  # 仓储服务 :11000（库存锁定/扣减/解锁 + 流水补偿）
├── lingmart-search/                # 检索服务 :12000（ES 检索 + IK 分词 + 店铺聚合）
├── lingmart-chat/                  # 聊天服务 :13000（WebSocket STOMP + 消息持久化）
├── lingmart-agent/                 # 网页浮窗 Agent :14000（Spring AI + SSE，知识种子 kb/）
├── lingmart-frontend/              # Vue3 前端（含 components/copilot 浮窗组件）
├── sql/                            # 数据库脚本（每服务一个建库脚本）
│   ├── lingmart_*.sql              # auth / shop / product / order / ware / chat / agent
│   └── upgrade/                    # 存量库增量迁移脚本（按日期命名，见 ops-checklist 第三节）
├── scripts/                        # 一键执行脚本
│   ├── build.bat                   # 编译打包（JDK 21，全部模块）
│   ├── pull-images.bat             # 拉取中间件镜像
│   ├── start-infra.bat             # 启动基础设施容器
│   ├── start-services.bat          # 启动全部 9 个服务（各开一个窗口）
│   ├── stop-services.bat           # 停止业务服务
│   ├── dev-start.sh                # Git Bash 下 nohup 启动（7 个服务，不含 chat / agent）
│   ├── verify-health.sh            # 一键健康巡检（基础设施 + 库结构 + 9 个服务）
│   ├── migrate.sh / migrate.bat    # 存量库增量迁移（按序执行 sql/upgrade 全部脚本）
│   ├── verify-seata-rollback.sh    # Seata 全局回滚验证（故障注入 + 查库对比）
│   ├── gen-self-signed-cert.sh     # 生成本地自签 TLS 证书（生产加固 tls profile 用）
│   ├── push-nacos-config.sh        # 推送共享配置与 Sentinel 规则到 Nacos（改配置后必跑）
│   ├── reset-data.sh / .bat        # 重置数据库与数据卷（慎用：会删光数据）
│   └── load-test/                  # k6 压测脚本 + 数据播种/清理/校验
├── docker-compose.yml              # 基础设施编排（Nacos/MySQL/Redis/ES/RabbitMQ/Seata/Zipkin/Chroma/Prometheus/Grafana）
├── docker-compose.business.yml     # 业务服务容器编排（9 个服务，与上面叠加使用）
├── docker-compose.prod.yml         # 生产加固覆盖层（业务端口不外发布 + 可选 nginx TLS）
├── docker/business.Dockerfile      # 业务服务统一镜像（多阶段构建）
├── docker/seata/                   # Seata TC 配置
├── docker/elasticsearch/           # ES 镜像 Dockerfile（内置 IK 中文分词）
├── docker/prometheus/              # Prometheus 抓取配置（9 个服务）
├── docker/grafana/                 # Grafana 数据源/看板自动装配
├── docker/nginx/                   # TLS 终止配置（自签证书不入库）
├── dependency-check-suppressions.xml  # 依赖漏洞扫描误报登记（mvn -Psecurity-scan）
├── nacos-config/                   # Nacos 共享配置与 Sentinel 规则源文件
├── .run/                           # IDEA 运行配置（9 个服务 + 复合配置「全部服务」）
├── docs/                           # 设计 / 分析 / 运维文档
└── CHANGELOG.md                    # 按版本的变更记录
```
