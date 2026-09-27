# 轻营 · 代码综合评审核对报告

> ⚠️ **历史快照（归档）**：本文是 2026-08-29 对一份外部评审报告的**逐条核对记录**，其中的
> 「已落地改进」「待办」都以**当时（v0.2~v0.3）的代码**为准——如今多数条目已经实现、部分结论已过时。
> **不要把本文当作当前状态描述**；当前待办见 [todo-and-optimization.md](todo-and-optimization.md)，
> 当前生产能力见 [production-readiness.md](production-readiness.md)，历次变更见 [../CHANGELOG.md](../CHANGELOG.md)。
> 保留原文是为了留下「当时为什么这么改」的决策痕迹。

> 待办与待优化完整清单见 [todo-and-optimization.md](todo-and-optimization.md)。
> 日期：2026-08-29
> 背景：针对外部 AI 生成的「轻营 C2C 交易平台 · 代码综合评审报告」，逐条对照本项目实际源码核对，给出修正结论与改进清单。
> 结论：报告大方向准确（72 条论断中 62 条成立、10 条部分或不准确），技术选型、亮点总结、评分 A 均符合实际；本文修正其中的偏差，并记录本次已落地的改进。

---

## 一、核对方法

- 对象：外部评审报告的 **72 条论断**（order/ware 15 条、product/search/shop 21 条、common/auth/gateway 14 条、chat/frontend/部署 22 条）。
- 依据：`lingmart-*` 全部源码、`sql/` 建表脚本、`docker-compose.yml`、`lingmart-frontend/` 源码。

## 二、核对结论汇总

| 模块 | 论断数 | 正确 | 部分/不准确 | 说明 |
| --- | --- | --- | --- | --- |
| order/ware | 15 | 12 | 3 | `deductStock` 实际有 rows 校验；`isLockedTaskDeducted` 是死代码而非被注释 |
| product/search/shop | 21 | 20 | 1 | shop 审核为四态（0/1/2/3），三态只适用于入驻申请 |
| common/auth/gateway | 14 | 13 | 1 | Sentinel 网关规则为代码静态加载，非 classpath JSON |
| chat/frontend/部署 | 22 | 17 | 5 | `toPeer` 兜底仅限 data==null；Feign 超时已统一配置 |
| **合计** | **72** | **62** | **10** | 正确率约 86% |

**总体判断**：技术基线、服务拆分、审核制模型、Seata 边界、幂等与超时取消闭环、ES IK、原子库存等核心论断全部属实；"评分 A（优秀）"与 README 详实性评价合理。

---

## 三、需修正的论断（10 处）

### 1. 幂等键唯一索引——比报告说的更严重（P0）
- **报告**：`uk_idempotency_key` 单列唯一，跨买家幂等键会冲突。
- **核对**：属实，且问题升级——`OrderServiceImpl` 的 `DuplicateKeyException` 兜底**只按 `idempotency_key` 重查**（不按买家过滤），不同买家用相同幂等键时会**返回他人订单号**（跨买家数据泄露）。
- **修复**：索引改为 `(idempotency_key, buyer_id)` 联合唯一；兜底查询补 `buyerId` 条件。✅ 已实施（见五-4）。

### 2. ware 扣减/解锁的行数校验（P0）
- **报告**：`unlockStock` 与 `deductStock` 均未校验影响行数。
- **核对**：**不准确**。`deductStock` 已有 `rows == 0` 抛异常；仅 `unlockStock` 未校验。
- **修复**：`unlockStock` 增加行数校验 + 「任务已是已解锁则跳过」的幂等保护。✅ 已实施（见五-5）。

### 3. shop 审核状态为「三态」
- **核对**：**不准确**。店铺 `reviewStatus` 为四态（0 待审核/1 已通过/2 已拒绝/3 待重审）；「三态」仅指入驻申请 `ShopApplication.status`（0/1/2）。宽松重审机制本身描述正确。

### 4. sellerStats 统计口径 IN(1,2,3)
- **核对**：**不准确**。概览/销售趋势/热销/件数确为 `order_status IN (1,2,3)`，但**状态分布**（`selectStatusDistribution`）是全状态统计（含 0 待支付 / 4 已取消），口径不同属有意为之，文档未说明。

### 5. `isLockedTaskDeducted` 被注释/未使用
- **核对**：**部分准确**。方法实现完整但**全项目无调用者**（死代码），非被注释。
- **修复**：删除接口声明与实现。✅ 已实施（见五-5）。

### 6. Sentinel 网关规则静态加载于 classpath JSON
- **核对**：**不准确**。网关规则由 `GatewaySentinelConfig` **代码加载**（`GatewayRuleManager.loadRules`），阈值读 `lingmart.gateway.sentinel.*` 配置；业务服务才用 `classpath:sentinel-flow-rules.json`。两者均为静态加载（非 Nacos 数据源）属实。

### 7. 高亮使用了「废弃的 co.elastic.clients 包」
- **核对**：**不准确**。`co.elastic.clients` 是当前官方 Elasticsearch Java Client（非废弃），且 `BoolQuery` 正被使用；仅有一处被注释掉的 `Highlight` import 属残留，可清理（不影响功能）。

### 8. 聊天 `toPeer` 在 auth 服务不可用时兜底
- **核对**：**不准确**。兜底仅在 auth **返回 data==null**（如用户不存在）时生效；auth 真正宕机（Feign 连接失败）会抛异常导致 500。
- **修复**：`toPeer`/`toMessageVO` 对 Feign 异常 try-catch 降级返回空并记警告日志。✅ 已实施（见五-7）。

### 9. AdminReview「批量审核」合并商品审核与入驻审核
- **核对**：**不准确**。批量审核只提交 `spuIds` 到 `/admin/product/review/batch`，**不涉及入驻审核**；「逻辑复杂」属实（入驻 tab 内嵌商品卡片）。

### 10. 各服务配置重复（Nacos/DB/Redis/MQ）
- **核对**：**部分准确**。Nacos 与 MySQL 数据源确在 8/6 个服务重复；但 Redis 仅 product、RabbitMQ 仅 order，**并非都重复**。Feign 超时（connect 3s/read 30s）已在 chat/product/order/shop 四服务**统一配置**，报告"未统一"不成立。

---

## 四、报告遗漏、本次补充发现的问题

| # | 级别 | 问题 | 处理 |
| --- | --- | --- | --- |
| A | P0 | 验证码明文返回**无环境开关**：`AuthController.sendCode` 无条件返回 `devCode`，生产同样泄露 | 新增 `DEV_CODE_ENABLED`（默认 false）✅ |
| B | P0 | 幂等键兜底跨买家订单泄露（同第三节-1，比原报告更严重） | 联合索引 + 兜底查询双字段 ✅ |
| C | P1 | 聊天 `toPeer`/消息历史对 auth 服务 Feign 异常无兜底 | try-catch 降级 ✅ |
| D | 文档 | 网关 `/ws/**` 白名单注释称 token 走查询参数，实际走 STOMP CONNECT 头 | 注释修正 ✅ |
| E | 部署 | docker-compose `SEATA_IP` 注释自相矛盾（"不设 SEATA_IP"却已设置） | 注释修正 ✅ |

---

## 五、本次已实施的修改清单

### lingmart-common（公共模块）
- **JWT 密钥启动校验**：新增 `JwtSecretValidatorAutoConfiguration`（`@AutoConfiguration`，经 `META-INF/.../AutoConfiguration.imports` 注册，网关也能加载）。凡配置 `lingmart.jwt.secret` 的服务启动时校验：<32 字节直接拒绝启动；等于仓库默认开发值输出 ERROR 级告警。
- **统一日志**：新增 `logback-spring.xml`，全部服务日志输出 `[%X{traceId:-},%X{spanId:-}]`，UTF-8 可读格式。
- **链路追踪**：`micrometer-tracing-bridge-brave` 依赖（版本由 Boot BOM 托管），全部服务继承。
- **BizException 默认错误码**：由字面量 `60000` 改为 `ResultCode.BIZ_ERROR.getCode()`，消除重复定义。

### lingmart-auth（认证中心）
- **验证码 Redis 化**：`VerificationCodeService` 由 ConcurrentHashMap 改为 Redis（`lingmart:auth:code:{phone}`，TTL 5 分钟、一次性）。
- **限流 Redis 化**：`AuthRateLimiter` 改为 Redis ZSET + Lua 原子脚本滑动窗口（`lingmart:ratelimit:*`），多实例共享；登录失败计数/发码防刷随之分布式化。
- **明文开关**：`/code/send` 仅在 `DEV_CODE_ENABLED=true` 时返回 `devCode`（默认 false）。
- **管理员日志**：`AdminUserInitializer` 已存在时补充 INFO 日志。
- **配置**：新增 `spring.data.redis`（默认 `REDIS_HOST:localhost` / `REDIS_PORT:6380`）。
- **测试适配**：`VerificationCodeServiceTest` 改为 Mockito 打桩 `StringRedisTemplate`/`AuthRateLimiter`，不依赖真实 Redis。

### lingmart-gateway（网关）
- `/ws/**` 白名单注释修正（token 走 STOMP CONNECT 头，由 chat 的 `JwtChannelInterceptor` 校验）。
- `AggregatedOpenApiController`：硬编码 `http://localhost:88` 改为 `lingmart.gateway.public-addr`（`GATEWAY_PUBLIC_ADDR`），拉取下游与聚合文档 server 地址均可配置。

### lingmart-order（订单服务）
- `sql/lingmart_order.sql`：`uk_idempotency_key(idempotency_key)` → `uk_idempotency_buyer(idempotency_key, buyer_id)` 联合唯一。
- `OrderServiceImpl` 幂等兜底查询补 `buyerId` 过滤，杜绝跨买家返回他人订单。

### lingmart-ware（仓储服务）
- `unlockStock`：库存解锁影响行数 0 时抛异常（避免静默失败）；任务已是「已解锁」则跳过（幂等，防超时取消与手动取消重复触发）。
- 删除死代码 `isLockedTaskDeducted`（接口 + 实现）。

### lingmart-shop（店铺服务）
- `UploadController`：图片校验升级为 **Content-Type 白名单 + 文件头魔数双重校验**（JPEG/PNG/GIF/BMP/WEBP 手写魔数识别），伪装文件直接拒绝，杜绝仅按 Content-Type 放行。

### lingmart-chat（聊天服务）
- `toPeer` / `toMessageVO`：新增 `safeGetUser`，auth 服务 Feign 异常时降级返回空并记警告日志，避免单点故障拖垮会话/历史。
- `application.yml` 注释修正（token 经 STOMP CONNECT 头携带）。

### lingmart-frontend（前端）
- `request.js`：baseURL 改 `import.meta.env.VITE_API_BASE_URL`（回退本地地址）。
- `ChatView.vue`：WebSocket 地址支持 `VITE_WS_URL`（回退 `ws://<hostname>:88/ws`）。
- 新增 `.env.example` / `.env.development` / `.env.production`。

### 全部 8 个服务
- `management.tracing.sampling.probability` 统一为 `${TRACING_SAMPLING_PROBABILITY:1.0}`，配合 common 统一 logback 输出 traceId/spanId。
- 注：**Feign 超时**经核对 chat/product/order/shop 四服务已统一（connect 3s / read 30s），无需重复修改。

### 文档 / 脚本
- `README.md`：环境变量表新增 `DEV_CODE_ENABLED` / `TRACING_SAMPLING_PROBABILITY` / `GATEWAY_PUBLIC_ADDR`；幂等/生产加固/日志追踪条目、auth 接口表、目录结构同步更新；联调入口补充验证码开关说明。
- `.env.example`：新增上述变量模板与注释。
- `docker-compose.yml`：`SEATA_IP` 矛盾注释修正。

---

## 六、修正版改进建议清单

### P0（安全/正确性，本次已全部完成）
1. JWT 密钥：启动期强制校验（<32 字节拒绝启动）+ 默认值 ERROR 告警。✅
2. 幂等键：`(idempotency_key, buyer_id)` 联合唯一 + 兜底查询双字段。✅
3. ware `unlockStock` 行数校验（`deductStock` 原本已有）。✅
4. 验证码/限流 Redis 分布式化。✅
5. 验证码明文生产泄露（新增开关）。✅
6. Nacos 密码：各服务默认值已对齐为 `nacos`（与 Nacos 2.4 服务端默认管理员一致）；生产经 `NACOS_PASSWORD` 覆盖。⚠️ 另修复 Seata TC 注册 Nacos 的密码（原为 `123456`，见 `docker/seata/application.yml`）。

### P1（本次已完成）
- 统一日志 + Micrometer Tracing（traceId/spanId）。✅
- 图片上传魔数校验。✅
- 前端 API / WebSocket 地址环境变量化。✅
- 聊天对 auth 服务故障降级。✅
- OpenAPI 聚合地址可配置。✅

### P1（后续已完成）
- **Nacos 配置中心统一管理** ✅ 已落地（v0.3）：nacos discovery / mybatis-plus / management 抽取到 Nacos 共享 DataId `lingmart-common.yaml`，8 服务加 `spring-cloud-starter-alibaba-nacos-config` + `spring.config.import`（`optional:` 前缀，Nacos 不可用仍可启动）；差异配置（datasource/redis/mq/端口/Seata/Sentinel）保留本地。配置文件见 `nacos-config/`，推送脚本 `scripts/push-nacos-config.sh`。
- **Sentinel 规则接 Nacos 动态数据源** ✅ 已落地（v0.3）：网关与 product/order/search 改 `NacosDataSource`（DataId `sentinel-*.json`），本地 classpath 规则作为兜底（Nacos 缺失/解析失败/不可达时沿用，避免失防），Nacos 修改规则即时生效。
- **业务服务容器化** ✅ 已落地（v0.3）：`docker/business.Dockerfile`（8 服务共用）+ `docker-compose.business.yml`（独立文件，与基础设施 compose 叠加，不影响 start-infra 流程）。

### P2（v0.4 已完成）
- ~~订单超时扫描索引 `(order_status, create_time)`~~ ✅ 已加 `idx_status_create`（见 `sql/lingmart_order.sql`）。
- ~~`updateSale` 刷 ES 由逐条查 SPU 改批量查询~~ ✅ 已改批量（`selectBatchIds` + 店铺名去重 + `EsProductService.indexAll` bulk 写入，失败降级逐条）。
- ~~商品详情 SKU 价格冗余字段 `spu.min_price`~~ ✅ 已加冗余字段并自动维护（老数据回退实时计算）。
- ~~ES 检索热点关键词加 Redis 缓存~~ ✅ 已加（纯关键词检索缓存 5 分钟 TTL，fail-open）。
- ~~`AdminReview.vue` 拆分入驻/商品/店铺审核组件~~ ✅ 已拆为三个独立组件。
- ~~全局异常处理器细粒度~~ ✅ 已加 400/405/415/上传超限等处理与错误码（`AccessDeniedException` 由网关角色拦截 + `BizException(FORBIDDEN)` 承担）。
- ~~logout 服务端失效~~ ✅ 已加：JWT 增加 jti，logout 写 Redis 黑名单，网关校验（Reactive、fail-open）。
- ~~角色提升失败手工修正入口~~ ✅ 已加：`/api/admin/auth/users/**` 按手机号查用户 + 手工改角色。
- ~~订单导出流式化~~ ✅ 已改：Mapper 游标 + service 逐行回调 + EasyExcel 分批写出。
- ~~商品详情 SKU 分页~~ ✅ 已加 `GET /api/product/{id}/skus` 分页接口；详情内嵌 SKU 上限 100。
- ~~库存销量 SQL 拼接改参数绑定~~ ✅ 已改 `sale_count = sale_count + {0}`。
- 数据库 `update_time` 自动填充（MyBatis-Plus MetaObjectHandler）：收益低，多数表已有 `ON UPDATE CURRENT_TIMESTAMP`，建议不做。
- ~~ES 写失败补偿表 + 定时对账~~ ✅ 补偿已落地（`es_write_retry` + 指数退避重放）；字段级对账可选。
- ~~shop/chat 的发送/上传内存限流器 `SlidingWindowRateLimiter` 多实例化~~ ✅ 已改 common `RedisRateLimiter`（ZSET+Lua），chat 发消息 / shop 上传均走 Redis 分布式限流。

---

## 七、部署与联调注意事项（改完代码后需你执行）

1. **auth 服务现在依赖 Redis**：启动前确认 Redis 已启动（`docker compose up -d redis` 或 infra 脚本）。Redis 不可用时验证码/登录/发码会失败。
2. **幂等键索引变更**：若 `lingmart_order` 库已初始化过（非首次启动），`docker-entrypoint-initdb.d` 不会重跑，需手动执行：
   ```sql
   ALTER TABLE `lingmart_order`.`order` DROP INDEX `uk_idempotency_key`,
       ADD UNIQUE KEY `uk_idempotency_buyer` (`idempotency_key`, `buyer_id`);
   ```
3. **本地联调验证码**：auth 本地默认返回 `devCode`（`lingmart.auth.dev-code-enabled` 默认 `true`，因无真实短信通道，前端注册/登录需回填）；生产部署设 `DEV_CODE_ENABLED=false`。
4. **生产必配**：`JWT_SECRET`（≥32 字节，勿用仓库默认值）、`NACOS_PASSWORD`、`DEV_CODE_ENABLED=false`。
5. **前端**：地址改在 `lingmart-frontend/.env.development`（开发）/ `.env.production`（生产）配置，改完重启 Vite 即可。
6. **日志**：各服务控制台新增 `[traceId,spanId]` 列；排查跨服务问题时按 traceId 串联日志。
7. **Nacos 凭据一致性**：Nacos 2.4 服务端默认管理员 `nacos / nacos`（docker-compose 已为 Nacos 挂载数据卷，避免重建后密码重置）。各服务 nacos discovery、Seata 客户端与 Seata TC（`docker/seata/application.yml`）的 Nacos 密码均已对齐为 `nacos`。凭据不匹配时：服务注册 403 `user not found`、Seata 客户端无法发现 TC、网关 `lb://` 路由不到实例。
8. **chat 服务依赖**：`lingmart-chat` 使用 `@FeignClient` 调用 auth/shop，已补 `spring-cloud-starter-loadbalancer`（缺依赖启动报 `No Feign Client for loadBalancing defined`）。
