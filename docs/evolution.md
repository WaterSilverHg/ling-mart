# 实现沿革与设计取舍

> 本文档回答的是 **「为什么会是现在这样」**——项目从哪来、替代过什么方案、踩过什么坑、哪条路走不通。
>
> **与 [CHANGELOG.md](../CHANGELOG.md) 的分工**（两者不重复）：
>
> | 文档 | 组织方式 | 回答的问题 |
> | --- | --- | --- |
> | `CHANGELOG.md` | **按版本**（时间线，只增不改） | 这一版**改了什么**、我**要执行什么** |
> | `docs/evolution.md`（本文） | **按主题** | 这个设计**为什么**是现在这样、替代过什么、踩过什么坑 |
> | [`README.md`](../README.md) | **只讲现状** | 现在**有什么功能**、**流程怎么走**、怎么跑起来 |
>
> README 里不写历史：版本号、"此前是…后来改成…"、已完成的里程碑表，全部归档到这里。

---

## 一、项目来源与范围取舍

**抄业务架构与表设计，不抄技术栈**：项目借鉴了谷粒商城的**业务架构与表设计**思路，
但底层技术栈全部替换为现代 LTS 组合（原规划的 Spring Boot 2.7.x 老栈不再使用）——
因为老栈的 `javax.*` 命名空间、`WebSecurityConfigurerAdapter` 继承式配置、Servlet 版网关过滤器
在 2026 年都已过时，且 JDK 21 的虚拟线程收益拿不到。

### 1.1 与谷粒商城的模块差异

| 谷粒商城模块 | 轻营处理方案 | 原因 |
| --- | --- | --- |
| `gulimall-member`（会员积分/等级） | **精简**为 `lingmart-auth`，去掉积分成长值体系 | C2C 不需要复杂会员体系 |
| `gulimall-coupon`（优惠券） | **暂不开发** | MVP 阶段砍掉营销模块 |
| `gulimall-seckill`（秒杀） | **暂不开发** | 复杂度高，后续迭代 |
| — | **新增** `lingmart-shop`（店铺服务） | C2C 核心：人人可开店 |

### 1.2 原规划里未落地的部分

- **`lingmart-third-party`（规划端口 30000，OSS + 短信）未落地**：
  图片上传改由 shop 服务用本地磁盘实现（`lingmart.upload.dir`）；短信通道留作后续，
  开发模式下 `/api/auth/code/send` 直接返回验证码明文。README 不再列出该模块。
- **商品分类维度于 v0.9 下线**（库表 `category`/`brand` 保留不删）：
  个人 C2C 场景下粗粒度分类价值有限，又遇上历史数据的编码乱码，
  改为关键词 + 价格区间检索；前端表单/详情/首页筛选不再出现分类。
  后端接口与 `spu.category_id` 字段仍在，只是不再作为主要入口。

---

## 二、开发阶段回顾（六个里程碑）

> 各阶段的任务与验收标准，作为"这块是什么时候、为什么建的"的索引。
> 每阶段当时附带的**接口一览**属于现状，已移到 README 的「接口」章节。

### 第一阶段 · 基础架构（Week 1）✅

| 任务 | 产出物 | 验收标准 |
| --- | --- | --- |
| 创建父工程 `ling-mart-parent` | `pom.xml` | 统一管理 Spring Boot 3.5.x / Cloud 2025.0.x 版本 |
| 创建 `lingmart-common` 公共模块 | R / 全局异常 / JWT 工具 | `common` 模块可被其他服务引用 |
| 搭建 Nacos + MySQL + Redis | `docker-compose.yml` | 一键拉起中间件，Nacos 控制台可见 |
| 创建 `lingmart-auth` 认证服务 | 注册/登录/验证码/JWT 接口 | 注册登录成功返回 JWT，管理员账号自动预置 |
| 创建 `lingmart-gateway` 网关 | 路由转发 + JWT 白名单过滤器 | 请求经网关正确转发，未登录请求被拦截 |

**关键产出**：项目可运行，注册登录闭环跑通，JWT 签发与网关鉴权验证正常。

### 第二阶段 · 商品与店铺核心域（Week 2-3）✅

| 任务 | 产出物 | 验收标准 |
| --- | --- | --- |
| 创建 `lingmart-shop` 店铺服务 | 入驻申请、审核、店铺管理 | 用户可提交入驻，管理员可审核通过/拒绝并提升角色 |
| 创建 `lingmart-product` 商品服务 | 分类/品牌 + SPU/SKU + 上下架 | 商家可发布商品、编辑、上下架（Feign 取店铺做数据隔离） |
| 商品数据同步 ES | 上架写入 / 下架删除 ES 文档 | 已上架商品写入 ES（检索服务在第四阶段提供） |
| 网关集成 JWT 鉴权 | 网关过滤器 + 白名单 | 未登录请求被拦截，合法 Token 放行 |

**关键产出**：申请开店（带商品清单）→ 审核通过 → 商品可售 → 上架同步 ES；改内容重新审核。

> **管理员/用户分离于 v0.2 完成**：管理端接口统一 `/api/admin/**`，网关按角色拦截（仅 ROLE_ADMIN，其余 403）；
> 管理后台前端改为独立布局，只有「审核中心 + 审计日志」，不含任何商城功能。

### 第三阶段 · 订单与库存闭环（Week 4-5）✅

| 任务 | 产出物 | 验收标准 |
| --- | --- | --- |
| 创建 `lingmart-order` 订单服务 | 下单、购物车、订单流转 | 用户可下单生成订单，支持支付/取消/发货 |
| 创建 `lingmart-ware` 仓储服务 | 库存锁定/扣减/解锁 | 下单时经 Feign 锁库存，库存原子扣减 |
| 集成 Seata 分布式事务 | `@GlobalTransactional` AT + 自建 XID 传播 | 下单→锁库存，任一失败整体回滚（含 undo_log） |
| 集成 RabbitMQ | TTL+死信 延迟队列 | 支付超时订单状态自动变为已取消并解锁库存 |

**关键产出**：完整下单流程跑通（下单→锁库存→支付→扣库存→发货→超时自动取消）。

> ⚠️ **上表第 3 行的验收标准，在 v0.26 之前是不成立的**：当时只配了 TC 和 `@GlobalTransactional`，
> 却没有人把 XID 放进跨服务请求头，于是 `lingmart-ware` 的写入根本不在全局事务内。
> 完整来龙去脉见本文第四节第 1.1 条。

### 第四阶段 · 检索与前端联调（Week 6-7）✅

| 任务 | 产出物 | 验收标准 |
| --- | --- | --- |
| 完善 `lingmart-search` 检索服务 | 多条件搜索、分页 | 可按关键词、分类、价格区间、排序搜索商品 |
| Vue3 前端搭建 | 首页/详情/购物车/下单/订单/卖家中心 | 前端可调用后端所有核心接口 |
| 前后端联调 | 全流程打通 | 用户可完成"注册→开店→发布商品→搜索→下单"完整链路 |

**关键产出**：完整 C2C 交易链路跑通。

### 第五阶段 · 高可用与部署（Week 8）✅（批7 落地）

| 任务 | 产出物 | 验收标准 |
| --- | --- | --- |
| Sentinel 限流配置 | 网关/核心接口限流规则 | 高并发下触发限流返回友好提示（HTTP 429 + R JSON） |
| 中文检索增强 | IK 分词 + 显式 mapping | 「苹果」能搜到「苹果手机」 |
| 订单导出 | 卖家/买家 xlsx 导出 | 中文表头、无乱码、文件名带日期 |
| 卖家数据统计 | 卖家中心经营看板 | 销售额/趋势/热销与订单列表一致 |
| Docker Compose 编排 | `docker-compose.yml` | 一键启动所有服务 + MySQL + Redis + ES + MQ |
| 部署文档 | `README.md` | 新开发者可按文档 5 分钟启动项目 |

**关键产出**：项目可容器化部署，具备基础高可用能力。

### 第六阶段 · 体验打磨（v0.2）✅

管理员/用户功能分离、多图上传、开店三态规则、聊天 + 店铺主页、空数据兜底、测试体系。

> 第六阶段之后的每一版改动（v0.3 ~ 至今）见 [CHANGELOG.md](../CHANGELOG.md)，本文不重复。

---

## 三、关键设计的取舍

> 这些是"为什么选 A 不选 B"的判断，与版本无关，所以留在本文；README 里只写结论。

| 决策点 | 决策内容 | 为什么 |
| --- | --- | --- |
| **JDK 版本** | Java 21 LTS（虚拟线程） | 虚拟线程显著提升 IO 密集型接口吞吐，且为当前 LTS |
| **服务拆分粒度** | 9 个服务（8 核心 + Agent 助手） | C2C 场景足够，不过度拆分增加复杂度 |
| **分布式事务** | 开店申请/审核、下单/支付/取消/退款→库存均用 Seata AT | 建店+建商品、订单状态与库存锁定/扣减/解锁强一致；异步场景（超时取消兜底）另配 MQ / 定时扫描 |
| **XID 传播** | 自建 Feign 拦截器 + 网关剥离，不引入 `spring-cloud-starter-alibaba-seata` | Seata 只自带服务端读头回绑、不提供 Feign 集成；自建零新依赖，且传播机制完全掌握在自己手里。网关剥离是为了堵住「外部请求伪造 `TX_XID`」——详见第四节 1.1 |
| **鉴权方案** | 网关统一 JWT 校验 | 无状态、易扩展，下游服务通过 Header 获取用户信息 |
| **数据隔离** | 商品表通过 `shop_id` 关联店铺 | 每个卖家只能操作自己店铺的商品 |
| **商品搜索** | ES 仅存储检索所需字段 | 详情查 MySQL，ES 只存 ID+标题+价格等检索字段；避免双写两份全量数据 |
| **SKU 数量** | `stock=NULL` = 不限量；限量才展示/扣减 | 满足个人闲置转卖"默认无限供应、可限量"的需求；下单到退款全链路按 `ware_sku.unlimited` 跳过库存操作 |
| **Agent 定位** | 从"可选 AI 组件"转为**必要模块**（v0.30） | 它已是产品功能的一部分，而不是外挂演示；代价是「全部服务」从此要求它那三条前置齐备（见 README §4.2） |
| **通用填表工具 `fill`** | **刻意不开放** | 它允许模型自由决定"往哪个输入框写什么值"，而填表是把内容写进一个**随后会被提交**的表单，风险比跳转/点击高一个量级。改为**领域专用预填**（只开"开店申请"一种表单，参数是固定 schema，模型没有"字段名"自由度）。完整论证见 [agent-copilot.md](agent-copilot.md) |
| **Agent 前置路由** | 用**确定性规则**而不是第二个小模型 | 引入分类模型会让"跑起来"的门槛再抬高一档（连 ChromaDB 都已是启动强依赖），而收益只是省一次调用。详见 [agent-copilot.md](agent-copilot.md) §5.12 |
| **游客可浏览范围** | **首页**（店铺列表 + 搜索）与**店铺主页**公开；商品详情、购物车、下单需登录 | C2C 的入口是"先看哪家在卖"，首页与店铺主页只读、不涉及任何身份数据，放开无风险；商品详情的取数接口不在网关白名单，且 `/api/product/**` 前缀下混着卖家写接口，整段加白会一并撤掉网关鉴权，故留着 |

### 3.1 技术栈基线为什么是现在这套

技术基线确定为 **2026 年现代组合**（2026.0 系列对齐）之后，带来的迁移注意点：

- 包名 `javax.*` → `jakarta.*`；
- Spring Security 弃用 `WebSecurityConfigurerAdapter` 继承方式；
- 网关过滤器用 WebFlux 的 `GlobalFilter`，而不是 Servlet 的 `WebFilter`；
- **Seata 2.5 起坐标由 `io.seata` 迁移至 `org.apache.seata`**
  （`org.apache.seata:seata-spring-boot-starter:2.5.0`，与 Spring Cloud Alibaba 2025.0.0.0 官方对齐）；
  TC 镜像相应改为 `apache/seata-server:2.5.0`，且 **2.x 服务端用 `application.yml` 配置，1.x 的 `registry.conf` 已弃用**。

---

## 四、踩过的坑与修复记录

> 按**主题**归档。每条写清"当时是什么样、为什么坏、怎么修好的"——
> README 只保留修好之后的**当前做法**，不保留这些来龙去脉。

### 1. 分布式事务（Seata）

#### 1.1 跨服务 XID 从未传播，`@GlobalTransactional` 实际只有一个分支（v0.26 修复）

**当时是什么样**：项目配好了 TC、写了 `@GlobalTransactional`，README 里也写着
「下单→锁库存，任一失败整体回滚」。但**没有人把 XID 放进跨服务请求头**——
Seata 只自带「服务端读头回绑」（`SeataHttpAutoConfiguration` 随 starter 默认注册：读 `TX_XID` 头 →
`RootContext.bind()`），**不提供 Feign 客户端集成**。于是 `ware` / `product` 的写入根本不在全局事务内。

**具体后果**：`lockStock` 成功后若全局提交失败（TC 不可达/超时），order 行经 `undo_log` 回滚，
但 ware 锁住的库存**永不释放**——订单行都没了，60 秒的超时扫描也扫不到它，
留下一条 `ware_order_task` 僵尸任务。

**怎么修的**：由 `lingmart-common` 的 `SeataXidFeignInterceptor` 补上客户端一环（自动配置，order/shop 生效）；
网关侧另有 `TxXidSanitizeFilter` 剥离外部请求伪造的 `TX_XID`（否则可注入伪造的事务 ID）。

**怎么确认它真的生效**（这条排障经验值得留）：把 order / ware 的 `logging.level.org.apache.seata`
设为 `DEBUG`（注意 `.env` 是普通属性源，**必须写带点的规范形式**，`LOGGING_LEVEL_*` 那种环境变量写法配不上），
跑一单后看**被调方**日志里有没有 `org.apache.seata.rm.datasource` 行——没有就是没成为分支。
注意 `TransactionPropagationInterceptor` 的行**不算数**（它对所有请求都执行）。

> 连带影响：`lingmart_product` 库的 `undo_log` 表在 SQL 里一直存在、注释也写着「必需」，
> 但在 v0.26 之前**从未生效**过。

#### 1.2 `can not connect to services-server`：TC 注册的是容器内网 IP（v0.12 修复）

**为什么坏**：TC 跑在容器里时，会把自己注册成 **Docker 内网 IP**（如 `172.18.0.9`），
宿主机上的服务路由不到。

**怎么修的**：v0.12 起客户端默认**「直连 TC」**（`SEATA_REGISTRY_TYPE=file` + `SEATA_GROUPLIST`），
完全不看注册 IP——宿主机跑服务用 `127.0.0.1:8091`（默认），容器编排由 compose 覆盖为 `seata-server:8091`。
所以**这项现在无需配置**。

若显式切回 `SEATA_REGISTRY_TYPE=nacos`，则必须把 `SEATA_IP` 设成本机局域网 IP——
注意它**只接受真实 IPv4**，填 `host.docker.internal` 这类域名会被静默忽略、回退到容器内网地址
（[官方 issue #3401](https://github.com/apache/incubator-seata/issues/3401)）。
TC 注册到 Nacos 这件事**只是为了在控制台能看到它**，客户端并不依赖那个注册地址。

#### 1.3 支付→库存扣减的异步化（2026-09-06 重构为事件驱动）

**为什么改**：原先支付时同步跨服务扣库存，把 ware 的网络 IO 拖在支付事务里。

**改成什么**：order `pay()` 在**同一本地事务**内完成"订单置已付 + 销量同步累加(product) +
支付成功事件写入 Outbox 表 `order_paid_event`(status=0)"→ 提交后由 `OrderPaidOutboxRelay`（order 内每 5s）
发布到 `order.paid.exchange / order.paid.ware.queue` 并置已发 → ware 的 `OrderPaidListener` 幂等扣减。

**必达保障**：Outbox 与订单状态同库落盘（提交失败事件随之回滚）；队列 durable；
ware 消费失败按退避重试、耗尽进 `order.paid.ware.dlq`；Outbox 发布失败下轮重发
（eventId 全局唯一 + ware 幂等 → 不会重复扣）。

**补偿兜底**：`StockFlowCompensationJob`（每 5 分钟）只处理**异常/历史遗留**的 status=0 流水
（正常链路已为 1）：0→2 抢占→重放扣减成功 1 / 失败 3；失败或单次扫描超 100 条打
`【STOCK_FLOW_ALERT】`（含 orderSn/skuId/原因），**绝不静默跳过**。
**只补不删**：本机制只重放/异步扣减，取消/退款解锁仍走既有同步链路。

**顺带异步化的还有 ES 销量刷新**：`updateSale` 只发布 `SaleUpdateEvent`，
由 `SalesEsRefreshListener` 在事务提交后（`@Async`，专用线程池 `config/AsyncConfig`）执行，
避免 ES / 店名 Feign 的网络 IO 拖住支付事务与 SPU 行锁；ES 写失败仍落 `es_write_retry` 补偿表。

### 2. 环境与构建

| 坑 | 当时的现象 | 处置 |
| --- | --- | --- |
| **JDK 高于 21** | product/search 端口占用但 health 不响应（verify-health 报 FAIL）、Feign/Seata 异常导致开店申请**静默回滚**（申请后看不到店铺） | 固定 JDK 21（虚拟线程要求，见 README 的环境要求） |
| **Windows 虚拟网卡被当成注册 IP** | `169.254.x.x` 被 Nacos 误识别为注册 IP，导致网关/服务间首连间歇超时（「系统繁忙」偶发） | 各服务强制注册 `127.0.0.1`，可用 `NACOS_DISCOVERY_IP` 覆盖；**换机/换网络后要重启全部服务**让新 IP 生效 |
| **Docker 镜像加速器失效** | 本机 `~/.docker/daemon.json` 里配置的阿里云加速器（`39h4n4b7.mirror.aliyuncs.com`）返回 403 | `pull-images.bat` 改用 `docker.1panel.live` 拉取并重打标准标签；长期建议直接替换 `registry-mirrors` |
| **Maven 全局配置损坏** | `conf/settings.xml` 因重复 `<mirrors>` 标签导致解析失败 | 已删除多余标签修复 |
| **product 端口 10001（非 10000）** | 本机 10000 端口被百度网盘后台进程 `YunDetectService.exe` 占用（会随百度网盘自启） | product 改用 10001；网关按 Nacos 服务名（`lb://lingmart-product`）路由，不受端口变更影响 |
| **`-parameters` 编译开关缺失** | Spring MVC 无法解析未显式命名的 `@PathVariable` / `@RequestParam` / `@RequestHeader`，请求带此类参数即返回 500 `系统繁忙`（如 `PUT /api/product/{id}`） | 父 POM 已配置 `<parameters>true</parameters>`（自定义父 POM 未继承 `spring-boot-starter-parent` 时默认不开） |
| **`.env` 只在 agent 里生效**（v0.11 修） | 其余服务在本地启动时完全读不到项目根 `.env`，是个容易误判的坑 | 加载器下沉到 `lingmart-common` 的 `DotenvEnvironmentPostProcessor`，9 个服务本地启动都读 `.env` |
| **Mockito 自附加警告** | JDK 21+ 跑 `mvn test` 输出 `Mockito is currently self-attaching` | 父 POM 统一为 surefire 配 `-javaagent` 加载 `mockito-core`；`mockito.version` 必须在父 POM 显式定义（BOM 的属性不随 `import` 继承，与 `lombok.version` 同理），**升级 Spring Boot 时要同步核对** |
| **`mvn test` 中文乱码** | Windows 控制台默认代码页 936(GBK) 而 JVM 输出 UTF-8 | 先执行 `chcp 65001`，仅影响可读性、不影响结果 |

### 3. 数据与序列化

| 坑 | 当时的现象 | 处置 |
| --- | --- | --- |
| **自增主键可枚举**（2026-09-06 雪花化） | 业务表主键是 DB 自增，暴露递增量、可被枚举 | 改为 MyBatis-Plus 雪花（`@TableId(ASSIGN_ID)`）；**雪花 id 超出 JS 精确整数上限**，由 `LongJsonAutoConfiguration` 把 JSON 中装箱 `Long` 统一输出为字符串（基本类型 `long` 如分页 total 仍为数字）。多实例部署同一服务时需为各实例设不同 `mybatis-plus.global-config.worker-id`，否则雪花冲突 |
| **`R.checkOk` 的 Integer 引用陷阱** | `code=200` 经 Feign/Jackson 反序列化后是**新的 `Integer` 实例**，与 `SUCCESS` 常量引用不同，用 `!=` 比较会把成功误判为失败 → Seata 回滚「店铺不入表」 | 改 `equals` 比较，并补回归测试 `checkOk_成功码为独立Integer实例不误判失败` |
| **库存流水卡在"补偿中"会导致退款不回补** | `ware_order_task` 退款回补只在「1 已扣减」时才加库存 | v0.11 加了卡死回收与告警（见 README 的表状态机说明） |
| **历史库不补新表** | MySQL 的 `docker-entrypoint-initdb.d` 只在数据目录为空时执行一次，老库不会自动补上后续版本新增的表/唯一键。典型症状：启动后报 `Table 'lingmart_order.order_paid_event' doesn't exist` | `verify-health.sh` 提前检查关键表与唯一键；补迁移按 `docs/ops-checklist.md` 第三节 |

### 4. 前端

| 坑 | 当时的现象 | 处置 |
| --- | --- | --- |
| **单图「点击更换」被数量校验误拦** | 头像/logo 想换一张，点上去没反应 | 由前端单测发现并修复（`ImageUploader` 的 `before-upload` 对"替换"与"新增"要分开判断） |
| **上传清单把正文灌进输入框**（v0.29 修） | 前端把表格解析成文本塞回输入框再随消息发出，**稍大的清单必然撞上服务端 `max-message-length`(2000) 而被整轮拒绝**——二十来行商品就超了，只能处理玩具级输入 | 改为**服务端存储**：文件走 `POST /api/agent/doc`，服务端解码成文本存进当前会话，消息里只带 `documentId` |
| **浮窗头部按钮点不动**（v0.29 修） | 【收起】【清空】点了没反应 | 头部 `pointerdown` 启动拖拽时会 `setPointerCapture`，而**指针一旦被捕获，浏览器会把随后的 `click` 派发到「pointerdown 目标」与「捕获元素」的最近公共祖先**（头部按钮即 `.cp-head`），按钮自己的 `@click` 永远收不到。修法：头部按钮不参与拖拽 |

### 5. 检索与中间件

| 坑 | 当时的现象 | 处置 |
| --- | --- | --- |
| **ES 镜像缺 IK 中文分词**（批7） | 「苹果」搜不到「苹果手机」 | 镜像从 `elasticsearch:8.15.5` 改为本地构建（`docker/elasticsearch/`），内置 IK；重建容器后索引清空，商品重新上架即按新 mapping 写入（`title`/`subtitle` 用 `ik_max_word` 索引 + `ik_smart` 检索） |
| **Sentinel 规则静态加载**（批7） | 未引入 Dashboard，改规则要重启 | 规则以代码/JSON 静态加载为兜底，**另配 Nacos 数据源**（DataId `sentinel-*.json`）支持热更新；被限流统一返回 HTTP 429 + `{"code":429,...}`。Sentinel 客户端端口各服务唯一（8720~8723），避免同机冲突 |
| **`lingmart_chat` 库补建**（v0.2 新增库） | 存量 MySQL 数据卷不会重跑 initdb | 需手动执行一次 `docker exec -i lingmart-mysql mysql -uroot -proot < sql/lingmart_chat.sql`（Windows 下用 Git Bash），或走 `scripts/migrate.sh` |

### 6. 可观测性

**统一日志与链路追踪（v0.3）**：`lingmart-common` 提供统一 `logback-spring.xml`，
日志输出 `[%X{traceId:-},%X{spanId:-}]`（Micrometer Tracing + Brave，采样率 `TRACING_SAMPLING_PROBABILITY` 默认 1.0），
跨服务排障可按 traceId 串联。Zipkin 自 v0.11 起随 `docker compose up -d` 默认启动。

---

## 五、Agent(Copilot) 的演进

Agent 是迭代最多的模块，单独成线。**完整设计见 [agent-copilot.md](agent-copilot.md)**，
这里只记"它经历了哪些阶段"。

| 阶段 | 主题 |
| --- | --- |
| 阶段 0（v0.15） | **安全加固**：回执 nonce 校验、单轮单工具、身份强制、会话数上限 |
| 阶段 1（v0.16） | **知识库诊断**：定位「知识库从未加载」，Token 改日志输出 |
| 阶段 2（v0.17） | **操作审计**：审计日志落库 + 管理端查询 + 30 天保留 |
| v0.18 / v0.19 | **语料扩充**（4 篇 → 13 篇）；修知识库重建失败（嵌入服务单次输入条数上限 10） |
| 阶段 3（v0.20 / v0.21） | **只读工具 + 自主循环**：查我的订单免确认自动执行、身份自校验、文本边界标记；扩展到查店铺/查商品 |
| 阶段 4（v0.22） | **记忆修复**：按「轮」裁剪历史、落盘配对不变量、最近会话指针 |
| 阶段 5（v0.23） | **元素 ID 化**：click 从 CSS 选择器改为稳定 `elementId`，修掉"重排后误点错行" |
| v0.24 | **搜索工具** `search_shops`（首个 READ_ONLY_PUBLIC）、navigate 支持查询串 |
| v0.25 | **缺陷修复**：记忆裁剪方向（会话记忆在第 20 条后冻结）、浮窗 DOM 混入页面上下文、金额谎报 ￥0、停止按钮失效、注入缺口（`<pending_intent>`）、拖拽泄漏 |
| v0.26 ~ v0.27 | 订单侧配套（Seata 传播、退款超时自动同意、发货模式） |
| v0.28 | **领域专用预填**：解析清单预填开店申请；通用 `fill` 仍关闭 |
| v0.29 | **文档改服务端存储**（修掉大清单发不出去）+ 浮窗按钮修复 |
| v0.30 | **转为必要模块** + **四层约束策略**（前置路由 / 白名单 / 角色重置 / 注意力锚定） |

### 5.1 Agent 为什么从"可选组件"变成"必要模块"

**v0.11 时的判断**：把它当可选 AI 组件，不纳入 `verify-health.sh` 与「全部服务」——
理由是"纳入会让没配模型 Key 的环境一键巡检/一键启动出现无意义的失败项"。

**v0.30 改判**：它已经是产品功能的一部分（开店材料预填、订单查询都在浮窗里），
继续挂在主流程之外，会让"一键启动"缺一个用户能直接感知的功能。

**改判的代价（必须一并承担）**：它有三条其它服务没有的前置——两把模型 Key、ChromaDB、审计库，
其中 **ChromaDB 缺失会导致启动直接失败**（`ChromaVectorStore` 在 bean 构造期就 `getCollection`）。
所以「全部服务」现在**要求环境齐备**。缓解措施：README 把这前置列成了表；
`verify-health.sh` 的 `ChromaDB` 与「库结构」两项正好覆盖其中两条。

---

## 六、还留在代码里的历史包袱

> 这些是"知道但暂时不动"的，避免后来者以为它们是干净的现状。

- **`lingmart-third-party` 端口 30000 已废弃**，但旧文档/图中可能仍出现该模块名。
- **分类/品牌接口仍在**（`/api/product/category/list`、`/api/product/brand/list`），
  但前端自 v0.9 起不再使用；库表保留不删。
- **`dev-start.sh` 只启动 7 个服务（不含 chat 与 agent）**，是脚本层面的历史遗留，
  与 IDEA「全部服务」/`start-services.bat` 的 9 个服务不一致，改动前请先对齐。
- **任务与待优化事项**统一记在 [todo-and-optimization.md](todo-and-optimization.md)，
  本文不重复登记。
