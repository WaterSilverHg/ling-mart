# 轻营（LingMart）C2C交易平台 · 项目开发全案

> 基于 Spring Cloud Alibaba 微服务架构的轻量级 C2C（Consumer-to-Consumer）个体交易平台。  
> 任何人可注册成为卖家，发布商品并交易，强调“轻量、简易、人人可经营”。

---

## 一、项目概述

- **项目名称**：轻营（LingMart）
- **仓库地址**：`https://github.com/你的用户名/ling-mart`
- **技术基线**：Java 21 (LTS，虚拟线程) + Spring Boot 3.5.x + Spring Cloud 2025.0.x + Spring Cloud Alibaba 2025.0.0.0
- **目标用户**：个体商户、闲置物品转卖者、小型手工艺人
- **核心价值**：极简入驻、快速上架、安全交易

---

## 开发进度速览

| 里程碑             | 状态     | 说明                                                                 |
| ------------------ | -------- | -------------------------------------------------------------------- |
| 第一阶段 · 基础架构 | ✅ 已完成 | 父工程 + common + auth + gateway 已落地，注册/登录/JWT 链路可运行     |
| 第二阶段 · 商品店铺 | ✅ 已完成 | lingmart-shop / lingmart-product 已落地，入驻审核 → 开店 → 发布商品 → 上架同步 ES |
| 第三阶段 · 订单库存 | ✅ 已完成 | lingmart-order / lingmart-ware / Seata AT / RabbitMQ 超时取消已落地 |
| 第四阶段 · 检索前端 | ✅ 已完成 | lingmart-search（ES 多条件检索）+ Vue3 前端（Vite+Element Plus）已落地 |
| 第五阶段 · 高可用   | ✅ 已完成 | Sentinel 限流已接入（网关路由级 + 服务注解级）；Docker Compose 一键部署 |
| 第六阶段 · 体验打磨（v0.2） | ✅ 已完成 | 管理员/用户功能分离、多图上传、开店三态规则、聊天+店铺主页、空数据兜底、测试体系（详见 [CHANGELOG.md](CHANGELOG.md)） |
| v0.3 ~ v0.11 迭代 | ✅ 已完成 | Sentinel/IK/导出/统计 → 可靠性四件套 → 退款 → 不限量 → **v0.11 全项目审计收敛（正确性修复 + 文档对齐）**；逐版本记录见 [CHANGELOG.md](CHANGELOG.md) |

> **平台模型（审核制）**：用户开店申请时**必须附上要卖的商品清单** → 管理员审核（商品逐条）→
> 通过后店铺营业、商品可售、角色提升为卖家。**改店铺信息或商品内容 → 重新审核（宽松：旧内容照常营业/销售，
> 新内容审核通过才生效）**；改库存不触发重审。新商品（开店后）同样逐个审核。

> **当前可运行闭环**：`docker compose up -d` 拉起中间件 → 依次启动 auth/shop/product/ware/order/search/chat/gateway →
> 验证码(开发模式返回随机 6 位码) → 注册 → 登录取 JWT → 网关鉴权透传 → 查询用户信息 → 申请开店（拖拽多图）→ 管理员审核
> （`/admin/review` 独立后台）→ 卖家发布商品 → 检索 → 下单 → 进店主页联系老板实时聊天。

### 快速启动（第一阶段）

Windows 环境提供一键脚本（`scripts/` 目录），按顺序执行：

```bat
scripts\pull-images.bat      REM 1. 拉取中间件镜像（含失效加速器的绕过方案，见下方注意）
scripts\start-infra.bat      REM 2. 拉起 Nacos+MySQL+Redis+ES+RabbitMQ+Seata+Zipkin+ChromaDB（首次自动执行 sql/ 建库）
scripts\build.bat            REM 3. 编译打包（需本机 JDK 21）
scripts\start-services.bat   REM 4. 启动 auth/shop/product/ware/order/search/chat/gateway 八个核心服务
```
```bash
bash scripts/verify-health.sh          # 5. 一键体检：基础设施 + 8 个核心服务健康状态
```

> 📋 **部署/运维完整清单**（环境变量与密钥、首次/干净部署、数据库增量迁移、端到端冒烟、日常运维、生产加固、故障排查）：见 [docs/ops-checklist.md](docs/ops-checklist.md)。启动后建议先跑 `verify-health.sh` 确认环境就绪。

**IDEA 一键启动**：项目 `.run/` 目录已内置 9 个 Spring Boot 运行配置（auth/shop/product/ware/order/search/chat/gateway/agent）+ 1 个复合配置「全部服务」。
IDEA 打开项目后会自动识别（若未出现，`File → Reload All from Disk`）。
前置：`File → Project Structure → Project SDK` 设为 JDK 21；`Settings → Build → Compiler → Annotation
Processors` 勾选 Enable（Lombok）；先 `docker compose up -d` 并执行两份新 SQL 建库。
之后点运行「全部服务」即可同时启动 8 个核心服务（不含可选的 lingmart-agent），各服务独立控制台；也可用底部 **Services** 窗口逐个管理/重启。

手工执行（等价于上述脚本）：

```bash
# 1. 拉起基础设施
docker compose up -d

# 2. 编译整个工程（需 JDK 21；Maven 需运行在 JDK 21 上）
JAVA_HOME=<你的JDK21路径> mvn -DskipTests clean package

# 3. 依次启动认证中心与网关
java -jar lingmart-auth/target/lingmart-auth-0.0.1-SNAPSHOT.jar
java -jar lingmart-gateway/target/lingmart-gateway-0.0.1-SNAPSHOT.jar
```

**联调入口**：所有请求经网关 `http://localhost:88` 转发。auth 接口见下方「认证中心接口」，完整接口文档见「10.2 接口文档」，统一 OpenAPI 入口 `http://localhost:88/v3/api-docs/merged`。

> **⚠️ 本地联调验证码**：`/api/auth/code/send` 默认返回验证码明文（`dev-code-enabled` 本地默认
> `true`，因无真实短信通道）。生产务必设环境变量 `DEV_CODE_ENABLED=false` 关闭。
> 前端 API 地址经 `lingmart-frontend/.env.development` 的 `VITE_API_BASE_URL` / `VITE_WS_URL`
> 配置（默认指向本地网关）。

> **⚠️ 环境注意事项**
>
> 1. **JDK 版本（必须 21）**：项目要求 JDK 21（已启用虚拟线程）。**用更高版本 JDK（如 26）启动服务会
>    出现：product/search 端口占用但 health 不响应（verify-health 报 FAIL）、Feign/Seata 异常导致
>    开店申请静默回滚（申请后看不到店铺）**。启动前确认 `JAVA_HOME` / IDEA Project SDK = JDK 21。
> 1. **Nacos 注册 IP**：Windows 虚拟网卡（`169.254.x.x`）会被 Nacos 误识别为注册 IP，导致网关/服务间
>    首连间歇超时（「系统繁忙」偶发）。各服务已强制注册 `127.0.0.1`（本地），可用 `NACOS_DISCOVERY_IP`
>    覆盖；**换机/换网络后重启全部服务让新 IP 生效**。Docker 部署（业务服务也在容器内）时需在 `.env`
>    把 `NACOS_DISCOVERY_IP` 设为宿主机局域网 IP，否则容器间 Feign 报「无实例」。
> 2. **Seata 连不上 TC（`can not connect to services-server`）**：根因是 TC 在容器里会把自己注册成
>    **Docker 内网 IP**（如 `172.18.0.9`），宿主机服务路由不到。**v0.12 起客户端默认「直连 TC」**
>    （`SEATA_REGISTRY_TYPE=file` + `SEATA_GROUPLIST`），完全不看注册 IP：宿主机跑服务用
>    `127.0.0.1:8091`（默认），容器编排由 compose 覆盖为 `seata-server:8091`。所以**这项现在无需配置**；
>    若你显式切回 `SEATA_REGISTRY_TYPE=nacos`，则必须把 `SEATA_IP` 设成本机局域网 IP——
>    注意它**只接受真实 IPv4**，填 `host.docker.internal` 这类域名会被静默忽略。
> 2. **Docker 镜像加速器**：本机 `~/.docker/daemon.json` 配置的阿里云加速器
>    （`39h4n4b7.mirror.aliyuncs.com`）已失效（403）。`pull-images.bat` 已改用
>    `docker.1panel.live` 拉取并重打标准标签。长期建议将 daemon.json 的 `registry-mirrors`
>    替换为可用镜像源（如 `https://docker.1panel.live`）后重启 Docker Desktop。
> 3. **Maven 全局配置**：曾发现 Maven 安装目录 `conf/settings.xml` 因重复 `<mirrors>` 标签损坏，
>    已修复（删除多余标签）。若之后 mvn 报 settings 解析错误，请检查该文件。
> 4. **Nacos 鉴权密钥**：Nacos 2.4 开启鉴权后必须配置 JWT 密钥（base64，解码 ≥32 字节），
>    通过项目根目录 `.env` 文件提供（`.env` 已 gitignore，模板见 `.env.example`）。
>    修改 `.env` 后需重建 Nacos 容器才能生效：`docker compose up -d --force-recreate nacos`。
>    Nacos 控制台登录账号密码仍为默认 `nacos / nacos`。
> 5. **product 端口 10001（非 10000）**：本机 10000 端口被百度网盘后台进程 `YunDetectService.exe`
>    占用（会随百度网盘自启），product 服务因此改用 10001。网关按 Nacos 服务名（`lb://lingmart-product`）
>    路由，不受端口变更影响。若百度网盘未启动时想用回 10000，改 `lingmart-product` 的
>    `application.yml` 中 `server.port` 即可（文档与脚本同步改）。
> 6. **Nacos 鉴权凭据**：Nacos 2.4 服务端默认管理员为 `nacos / nacos`（docker-compose 已为 Nacos 挂载
>    数据卷持久化 Derby，避免重建容器后密码重置）。各服务（含网关）`application.yml` 中 nacos 密码默认值
>    已对齐为 `nacos`，可经 `NACOS_PASSWORD` 环境变量统一覆盖。凭据不匹配时服务注册返回 403 `user not found`，
>    且网关 `lb://` 路由不到实例。
> 7. **`-parameters` 编译开关**：父 POM 已为 maven-compiler-plugin 配置 `<parameters>true</parameters>`
>    （自定义父 POM 未继承 spring-boot-starter-parent 时默认不开启）。缺失时 Spring MVC 无法解析
>    未显式命名的 `@PathVariable` / `@RequestParam` / `@RequestHeader` 参数，请求带此类参数即返回
>    500 `系统繁忙`（如 `PUT /api/product/{id}`、`POST /api/admin/shop/applications/{id}/review`）。
>    若新增控制器参数请保持该配置，或显式写参数名（如 `@PathVariable("id")`）。
> 8. **RabbitMQ / Seata 端口与账号**：RabbitMQ `5672`（AMQP）+ `15672`（管理台 guest/guest）；
>    Seata TC `8091`。两容器镜像较大，首次 `docker compose up` 前先跑 `pull-images.bat`（本机
>    阿里云加速器已失效，脚本走 `docker.1panel.live`）。
> 9. **Seata 坐标与版本**：2.5 起必须用 `org.apache.seata:seata-spring-boot-starter:2.5.0`
>    （`io.seata` 已停止发布 2.1+），TC 镜像 `apache/seata-server:2.5.0`；TC 需在
>    `docker/seata/application.yml`（Seata 2.x 服务端用 application.yml，1.x 的 registry.conf 已弃用）
>    注册到 Nacos（compose 网络内地址 `nacos:8848`，group=SEATA_GROUP）——**TC 注册成功只是为了
>    Nacos 里能看到它，客户端并不依赖这个注册地址**（见第 2 条：默认直连 grouplist）。
>    若下单报「无法连接 TC / 找不到 vgroup」，检查 `seata.tx-service-group` 与 TC 集群名（default）一致。
> 10. **凭据环境化**：各服务 `application.yml` 的密钥/密码均为 `${ENV:默认值}` 占位符，开发用默认值，
>     生产用环境变量覆盖。关键变量见下方「环境变量」表。
> 11. **下单幂等**：`POST /api/order` 支持 `Idempotency-Key` 请求头，相同键只建一单（前端结算页已自动携带）；
>     数据库按 `(idempotency_key, buyer_id)` 联合唯一兜底（跨买家可安全复用客户端幂等键，防并发重复下单）；
>     另有定时扫描兜底超时取消（与 RabbitMQ 消息互补）。
> 13. **生产加固**：Feign 全局超时（connect 3s / read 30s）；下游业务错误经 `R.checkOk` 校验抛出（防静默吞错）；
>     auth 登录/验证码改 **Redis 分布式限流**（验证码亦存 Redis，多实例共享；防爆破/短信轰炸）；
>     图片上传按文件头魔数校验（防伪装文件）；JWT 密钥启动期强制校验（<32 字节拒绝启动）；
>     开店申请已纳入 Seata（**`lingmart_shop` 库需有 `undo_log` 表**）。
> 13.5 **统一日志与链路追踪（v0.3）**：lingmart-common 提供统一 `logback-spring.xml`，
>     日志输出 `[%X{traceId:-},%X{spanId:-}]`（Micrometer Tracing + Brave，采样率 `TRACING_SAMPLING_PROBABILITY`，
>     默认 1.0），跨服务排障可按 traceId 串联。
> 12. **健康检查**：各服务暴露 `/actuator/health`（含优雅停机 `server.shutdown: graceful`），
>     网关已将该路径加入 JWT 白名单。
> 13. **ES 需重建镜像启用 IK 分词（批7）**：ES 镜像从 `elasticsearch:8.15.5` 改为本地构建
>     （`docker compose build elasticsearch`，Dockerfile 见 `docker/elasticsearch/`），内置 IK 中文分词。
>     重建容器后索引清空，商品重新上架即按新 mapping 写入（`title`/`subtitle` 用 `ik_max_word` 索引 +
>     `ik_smart` 检索）。若 GitHub 拉取 IK zip 受限，见 Dockerfile 内 COPY 本地 zip 的替代方案。
> 14. **Sentinel 限流（批7）**：未引入 Dashboard，规则以代码/JSON 方式静态加载
>     （网关阈值在 `lingmart.gateway.sentinel.*` 可改；服务规则在 `classpath:sentinel-flow-rules.json`），
>     被限流统一返回 HTTP 429 + `{"code":429,...}`。生产需动态调规则时升级为 Nacos 数据源。
>     Sentinel 客户端端口各服务唯一（8720~8723），避免同机冲突。
> 15. **lingmart_chat 库（v0.2 新增）**：聊天服务独立库。若 MySQL 数据卷此前已初始化过（非首次启动），
>     `docker-entrypoint-initdb.d` 不会重跑，需手动执行一次：
>     `docker exec -i lingmart-mysql mysql -uroot -proot < sql/lingmart_chat.sql`（Windows 下用 Git Bash 执行）。
> 16. **主键雪花化（2026-09-06）**：业务表主键由 DB 自增改为 MyBatis-Plus 雪花
>     （实体 `@TableId(ASSIGN_ID)`，共享配置 `id-type: assign_id`），不再暴露递增量/可枚举。
>     存量已初始化的库请执行 `sql/upgrade/2026-09-06-snowflake-id.sql` 去掉 `AUTO_INCREMENT`；
>     全新库直接用仓库内新 DDL。**雪花 id 超出 JS 精确整数上限**，已由 lingmart-common
>     `LongJsonAutoConfiguration` 把 JSON 中装箱 `Long`（id 等）统一输出为字符串
>     （基本类型 `long` 如分页 total 仍为数字），前端请按字符串处理 id、回传原样即可；
>     WebSocket(STOMP) 帧同样走该序列化。多实例部署同一服务时需为各实例设不同
>     `mybatis-plus.global-config.worker-id`，避免雪花冲突。

## 一.5 环境变量（生产覆盖）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NACOS_SERVER_ADDR` | `localhost:8848` | Nacos 地址（各服务注册 + Seata 客户端发现 TC） |
| `NACOS_DISCOVERY_IP` | `127.0.0.1` | 服务注册到 Nacos 的 IP。本机开发（服务跑宿主机）无需配置；**容器编排部署必须覆盖为宿主机局域网 IP**，否则服务间 Feign 报「无实例」（注意：这项与 Seata 无关——Seata 客户端已改为直连 TC，见下方两行） |
| `SEATA_REGISTRY_TYPE` | `file` | Seata 客户端如何找 TC：`file`=直连下面的 `SEATA_GROUPLIST`（默认，**不依赖 TC 往 Nacos 注册成什么 IP**）；`nacos`=从 Nacos 的 `SEATA_GROUP` 取 TC 地址（此时才需要 `SEATA_IP` 是对的） |
| `SEATA_GROUPLIST` | `127.0.0.1:8091` | 直连模式下的 TC 地址。宿主机跑服务 → `127.0.0.1:8091`（容器端口映射）；容器编排由 `docker-compose.business.yml` 覆盖为 `seata-server:8091`（compose 服务名） |
| `SEATA_IP` | `127.0.0.1` | TC **往注册中心登记**的 IP，仅 `SEATA_REGISTRY_TYPE=nacos` 时才会被读到。必须是**真实 IPv4**——写 `host.docker.internal` 这类主机名会被 Seata 判为非法并静默回退到容器内网 IP（[官方 issue #3401](https://github.com/apache/incubator-seata/issues/3401)）。不设时容器里自动探测到的是 Docker 内网地址（`172.x`），宿主机服务路由不到 → 报 `can not connect to services-server`，这也是本项目默认改用直连模式的原因 |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | `nacos` / `nacos` | Nacos 鉴权账号（与服务端默认管理员一致） |
| `DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD` | `localhost` / `3307` / `root` / `root` | MySQL 连接（每服务独立库） |
| `ES_URIS` | `http://localhost:9201` | Elasticsearch 地址（product/search） |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `localhost` / `5672` / `guest` / `guest` | RabbitMQ（order） |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | Redis（product 商品缓存 + 检索热点缓存；auth 验证码/登录限流；chat 发消息限流；shop 上传限流；gateway 退出登录黑名单；本机映射 6380） |
| `SPRINGDOC_ENABLED` | `true` | 生产设 `false` 关闭各服务 OpenAPI/Swagger |
| `SENTINEL_ENABLED` | `true` | Sentinel 限流总开关（false 关闭网关与业务服务限流） |
| `SENTINEL_AUTH_QPS` / `SHOP` / `PRODUCT` / `ORDER` / `WARE` / `SEARCH` | `200/100/100/100/100/50` | 网关各路由 QPS 阈值 |
| `lingmart.gateway.cors-allowed-origins` | `http://localhost:5173` | 网关跨域来源白名单（逗号分隔，生产填前端域名）。**注意键名**：代码读的是配置项 `lingmart.gateway.cors-allowed-origins`，不存在 `CORS_ALLOWED_ORIGINS` 环境变量 |
| `JWT_SECRET` | 开发默认值 | JWT 签名密钥（HS256 ≥32 字节）。启动时强制校验长度，不足 32 字节拒绝启动；仍用仓库默认值会输出 ERROR 告警。**容器编排必填**（`docker-compose.business.yml` 用 `${JWT_SECRET:?…}`，不设直接拒绝启动） |
| `JWT_EXPIRE_MINUTES` | `120` | Token 有效期（分钟） |
| `ADMIN_PHONE` / `ADMIN_PASSWORD` | `13800000000` / `admin123` | 启动预置管理员账号 |
| `UPLOAD_DIR` | `./uploads` | 图片上传根目录（本地磁盘；生产接 OSS 时替换实现） |
| `DEV_CODE_ENABLED` | `true`（容器编排为 `false`） | 为 `true` 时 `/api/auth/code/send` 返回验证码明文便于联调（本地默认开启，因无真实短信）；**生产必须设 `false`**。⚠️ 容器编排默认 `false`——用容器联调时若取不到验证码，需显式设 `true` |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Micrometer Tracing 采样率（0~1）；traceId/spanId 输出到日志 MDC，用于跨服务排障 |
| `ZIPKIN_ENABLED` | `true` | 是否上报 Zipkin 全链路（**默认开启**：zipkin 容器随 `docker compose up -d` 一起启动，UI http://localhost:9411）。开关在 Nacos 共享配置 `management.tracing.zipkin.enabled`（**改完要先 `scripts/push-nacos-config.sh` 推送**才生效）。不想上报就设 `ZIPKIN_ENABLED=false`；span 上报地址 `management.tracing.zipkin.endpoint`（默认 `http://localhost:9411/api/v2/spans`，可用 `ZIPKIN_BASE_URL` 覆盖） |
| `GATEWAY_PUBLIC_ADDR` | `http://localhost:88` | 网关 OpenAPI 聚合对外地址（`/v3/api-docs/merged` 的服务 Base URL，Apifox 导入用） |

> 📌 **`.env` 在本地也生效**（v0.11 起）：项目根 `.env` 由 `lingmart-common` 的
> `DotenvEnvironmentPostProcessor` 加载，**9 个服务**（含本地 `java -jar` 与 IDEA 启动）都读它，
> 优先级为 `真实系统环境变量 > .env > application.yml 默认值`。
> 也就是说上面这些变量写进 `.env` 即对本地启动生效（此前只有 lingmart-agent 读 `.env`，
> 其余服务在本地启动时完全读不到，是个容易误判的坑）。
> 想确认是否加载成功：看 `/actuator/env` 里有没有名为 `dotenv-file-<路径>` 的属性源。
> 容器内没有该文件（镜像只拷 jar），自动跳过，仍由 compose 注入的环境变量提供。

## 一.6 Nacos 配置中心与容器化（v0.3 起）

**配置中心**：`mybatis-plus` / `management` 已抽取到 Nacos 共享 DataId `lingmart-common.yaml`，
9 个服务（8 个核心服务 + agent）经 `spring.config.import` 引入（`optional:` 前缀，Nacos 不可用时服务仍可启动，仅影响 SQL 日志/管理端点）。
**Nacos 注册中心（discovery）配置保留在本地** application.yml——避免"注册依赖配置中心"的鸡生蛋问题，
配置中心不可用时服务仍能正常注册。各服务保留差异化配置（datasource/redis/mq/端口/Seata/Sentinel 端口）。

- 配置文件集中在 `nacos-config/`（共享配置 + Sentinel 规则）。
- 推送到 Nacos：`scripts/push-nacos-config.sh`（登录 → 逐文件发布，改配置后推送即热更新）。

**Sentinel 动态规则**：网关与 product/order/search 的限流规则改为 **Nacos 数据源**
（DataId `sentinel-*.json`），本地 classpath 规则作为兜底（Nacos 缺失/解析失败/不可达时沿用），
Nacos 中修改规则即时生效。

**容器化**：业务服务镜像由 `docker/business.Dockerfile` 构建（9 服务共用），编排见
`docker-compose.business.yml`（独立文件，与基础设施 compose 叠加使用，不影响现有 start-infra 流程）：

```bash
mvn -DskipTests clean package                                          # 1. 打包各服务 fat jar
scripts/push-nacos-config.sh                                           # 2. 推送配置到 Nacos（必须先做！）
docker compose -f docker-compose.yml -f docker-compose.business.yml build   # 3. 构建业务镜像
docker compose -f docker-compose.yml -f docker-compose.business.yml up -d   # 4. 全栈启动
```

> ⚠️ 业务容器与 `java -jar` 宿主机启动端口映射一致，**勿同时运行**（会端口冲突）。
> 业务容器内连接基础设施用 compose 服务名（`mysql:3306` / `redis:6379` / `elasticsearch:9200` 等）。

> 📋 **待办与待优化事项**（部署/安全/可观测/测试/遗留问题）：见 [docs/todo-and-optimization.md](docs/todo-and-optimization.md)。

## 二、核心目标

| 维度           | 目标                                                         |
| -------------- | ------------------------------------------------------------ |
| **功能完整性** | 完成用户注册登录、**商家申请开店（带商品清单）→ 人工审核 → 通过后可售**、改内容重新审核、检索、下单支付、订单管理、库存扣减完整闭环 |
| **技术目标**   | 掌握 Spring Cloud Alibaba 微服务全套组件（Nacos、OpenFeign、Sentinel、Seata、Gateway）实战运用 |
| **架构目标**   | 微服务模块按业务边界清晰划分，服务间通过 Feign 声明式调用，实现故障隔离 |
| **数据目标**   | 每服务独立数据库，MySQL 存储核心数据，Redis 缓存热点，ES 提供全文检索 |
| **部署目标**   | 最终可 Docker 容器化运行                                     |

---

## 三、技术栈清单

### 3.1 后端核心框架

> 技术基线已确定为 **2026 年现代组合**（原规划的 Boot 2.7.x 老栈不再使用）：
> 抄谷粒商城的**业务架构与表设计**，底层技术栈全部替换为现代 LTS 版本。

| 组件              | 技术选型             | 版本/备注                              |
| ----------------- | -------------------- | -------------------------------------- |
| 基础语言          | Java                 | 21（LTS，开启虚拟线程）                |
| 核心框架          | Spring Boot          | 3.5.x（3.5.16）                        |
| 微服务框架        | Spring Cloud         | 2025.0.x（2025.0.3）                   |
| Spring Cloud Alibaba | ...               | 2025.0.0.0                             |
| 服务注册/配置中心 | Nacos                | 2.4.x                                  |
| 服务间调用        | OpenFeign            | -                                      |
| 网关              | Spring Cloud Gateway | 4.x（WebFlux / GlobalFilter）          |
| 流量控制/熔断降级 | Sentinel             | 1.8.9（批7 已接入：网关路由级 + 服务注解级，无 Dashboard） |
| 分布式事务        | Seata                | 2.5.0（AT 模式，✅ 已接入）            |
| JWT 库            | JJWT                 | 0.12.x（新链式 API，jakarta 命名空间） |
| 持久层            | MyBatis-Plus         | 3.5.x（spring-boot3-starter）          |

> **适配注意**（对谷粒商城老教程）：包名 `javax.*` → `jakarta.*`；Spring Security 弃用
> `WebSecurityConfigurerAdapter` 继承方式；网关过滤器用 WebFlux 的 `GlobalFilter` 而非 Servlet
> `WebFilter`。**Seata 2.5 起坐标由 `io.seata` 迁移至 `org.apache.seata`**（`org.apache.seata:seata-spring-boot-starter:2.5.0`，
> 与 Spring Cloud Alibaba 2025.0.0.0 官方对齐）；TC 服务器镜像为 `apache/seata-server:2.5.0`。

### 3.2 数据与中间件

| 组件              | 用途                                   |
| ----------------- | -------------------------------------- |
| MySQL 8.0         | 各服务独立数据库                       |
| Redis 7.x         | 商品/目录缓存、检索热点缓存、登录态与 JWT 黑名单、库存扣减限流（各服务自研 ZSET+Lua 滑动窗口，**未引入 Redisson**）、店名共享缓存 |
| Elasticsearch 8.15.x | 商品全文检索与店铺聚合（spring-data-elasticsearch；镜像内置 IK 中文分词插件） |
| RabbitMQ 3.13     | 订单超时取消（TTL+死信）、支付成功 Outbox 投递、异步解耦 ✅ |
| 本地磁盘（`UPLOAD_DIR`） | 商品图片、店铺 logo、用户头像存储；**生产接 OSS 需自行替换实现**（当前无 OSS SDK 依赖） |
| ChromaDB（可选）  | lingmart-agent 的知识库向量存储；仅 AI 组件使用 |

### 3.3 前端

| 组件         | 用途                    |
| ------------ | ----------------------- |
| Vue 3        | 前端框架                |
| Element Plus | UI 组件库               |
| ECharts      | 卖家数据统计看板图表    |
| Axios        | HTTP 请求封装、JWT 拦截 |

### 3.4 开发与运维

| 组件         | 用途                            |
| ------------ | ------------------------------- |
| Maven        | 项目构建与依赖管理              |
| Git + GitHub | 版本控制（仓库名：`ling-mart`） |
| Docker       | 容器化部署                      |

---

## 四、服务模块与端口规划

### 4.1 核心业务服务

| 服务名   | 模块名             | 端口  | 核心职责                                                     |
| -------- | ------------------ | ----- | ------------------------------------------------------------ |
| 认证中心 | `lingmart-auth`    | 8000  | 用户注册/登录、JWT 签发与校验、手机号验证码                  |
| 店铺服务 | `lingmart-shop`    | 8100  | 商家入驻申请、店铺信息管理、店铺状态审核、店铺主页、图片上传 |
| 商品服务 | `lingmart-product` | 10001 | SPU/SKU 管理、商品分类/品牌、商品上下架                      |
| 订单服务 | `lingmart-order`   | 9000  | 下单流程、购物车、订单状态流转、RabbitMQ 超时取消 ✅          |
| 仓储服务 | `lingmart-ware`    | 11000 | 库存锁定/扣减/解锁（Seata AT 参与方）✅                        |
| 检索服务 | `lingmart-search`  | 12000 | Elasticsearch 商品多条件检索 ✅                                |
| 聊天服务 | `lingmart-chat`    | 13000 | 买家↔店铺老板 WebSocket(STOMP) 会话 + 消息持久化（v0.2 新增） |
| API 网关 | `lingmart-gateway` | 88    | 统一路由转发、JWT 鉴权（含登出黑名单）、CORS、路由级限流 |

> 以上 8 个为**核心服务**：`start-services.bat` / `verify-health.sh` / `.run/全部服务` 覆盖的就是这 8 个。

### 4.2 可选 AI 组件（不属于核心链路）

| 服务名   | 模块名             | 端口  | 核心职责                                                     |
| -------- | ------------------ | ----- | ------------------------------------------------------------ |
| Agent 助手 | `lingmart-agent`   | 14000 | 网页浮窗 Agent(Copilot)：知识库问答 + **本人数据查询（只读工具，免确认）** + 页面操作引导 + SSE 流式（Spring AI；RAG 存 ChromaDB，会话记忆存 Redis，操作审计存 `lingmart_agent` 库） |

> 该组件**可选**：**不依赖任何业务库**（不与 auth/shop/product/order/ware/chat 交互，也不建业务表），
> 但需要 `.env` 里的 `DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY` 与 ChromaDB 容器，
> 以及一个**独占的审计库** `lingmart_agent`（首次部署须执行 `sql/lingmart_agent.sql`；
> 该库不可达时 Agent 仍能启动与对话，只是不写审计）。因此**不纳入** `start-services.bat`、
> `verify-health.sh` 与「全部服务」运行配置，要跑请单独
> `java -jar lingmart-agent/target/lingmart-agent-0.0.1-SNAPSHOT.jar`。

> 💡 **网页浮窗 Agent(Copilot)**：完整设计文档见 [docs/agent-copilot.md](docs/agent-copilot.md)（架构分层、SSE 协议、前后端交互、安全模型、部署与落地状态）。
> **两类能力、两套执行模型**：
> - **写操作**（跳转/点击）：模型只能"发起意图"→ 前端渲染确认卡片 → 用户确认后由前端执行 → 回执续跑；
> - **只读查询**：**免用户确认，服务端直接执行**（经网关透传用户 JWT，Agent 不做权限判断），
>   一轮内可连查数次再作答。当前四个工具：`query_my_orders`（我的订单，可按状态筛选）、
>   `query_my_shop`（我的开店状态：入驻申请 + 店铺，**零参数**）、`query_my_products`（我店铺的商品，可按上下架/审核状态筛选）、
>   `search_shops`（搜索公开在售商品/店铺，可按价格区间）。
>
> 这两条边界的判据是「**参数里有没有主体**」与「**会不会改状态**」，由 `ToolRegistry` 机械校验，
> 且在启动期用反射强制只读工具的参数里不含 `userId`/`buyerId` 等主体字段——让模型根本没有"查别人"这个旋钮。
> 四个工具共用 `AbstractReadOnlyTool` 的闸门链路（会话缓存 30s + 会话配额 50 次 + 单轮 ≤5 次 / ≤15s），
> 结果文本一律带「数据截至 …」，避免把过期数据说成实时。
> **填表（`fill`）刻意不开放**：填表会把内容写进一个随后会被提交的表单，风险比跳转/点击高一个量级，敏感字段由用户自己输入。
> LLM：对话默认 **DeepSeek（deepseek-chat）**、知识库向量化默认 **阿里云百炼（text-embedding-v3）**——只需在 `.env` 填 `DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY` 两把 Key（云上模型，无需本地 GPU）；知识库存储依赖 ChromaDB（集合名 `lingmart_kb`）。

> 📌 **原规划的 `lingmart-third-party`（30000，OSS + 短信）未落地**：图片上传改由 shop 服务本地磁盘实现
> （`lingmart.upload.dir`），短信通道留作后续（当前开发模式返回验证码明文）。README 不再列出该模块。

### 4.3 公共模块

| 模块名            | 职责                                             |
| ----------------- | ------------------------------------------------ |
| `lingmart-common` | 通用工具类、统一返回值 R、全局异常处理、JWT 工具、Redis 限流器、`X-User-Id` 取值与角色校验、`.env` 加载器 |

### 4.4 与谷粒商城（gulimall）的差异对照

| 谷粒商城模块                       | 轻营处理方案                                   | 原因                   |
| ---------------------------------- | ---------------------------------------------- | ---------------------- |
| `gulimall-member`（会员积分/等级） | **精简**为 `lingmart-auth`，去掉积分成长值体系 | C2C 不需要复杂会员体系 |
| `gulimall-coupon`（优惠券）        | **暂不开发**                                   | MVP 阶段砍掉营销模块   |
| `gulimall-seckill`（秒杀）         | **暂不开发**                                   | 复杂度高，后续迭代     |
| —                                  | **新增** `lingmart-shop`（店铺服务）           | C2C 核心：人人可开店   |

---

## 五、数据库设计原则

### 5.1 数据库划分（每服务独立库）

| 数据库名           | 所属服务 | 核心表                                          |
| ------------------ | -------- | ----------------------------------------------- |
| `lingmart_auth`    | 认证中心 | `user`（角色在 `user.role` 字段；**验证码存 Redis、无独立表**） |
| `lingmart_shop`    | 店铺服务 | `shop`、`shop_application`、`audit_log` + `undo_log`（Seata） |
| `lingmart_product` | 商品服务 | `category`、`brand`、`spu`、`sku`、`sku_images`、`audit_log`、`es_write_retry`（ES 写补偿）、`sale_rollback_log`（销量回退幂等） |
| `lingmart_order`   | 订单服务 | `order`、`order_item`、`cart`、`order_paid_event`（Outbox） + `undo_log`（Seata） |
| `lingmart_ware`    | 仓储服务 | `ware_sku`、`ware_order_task`、`stock_flow_log`（库存流水/补偿） + `undo_log`（Seata） |
| `lingmart_chat`    | 聊天服务 | `chat_conversation`、`chat_message`（v0.2 新增） |
| `lingmart_agent`   | Agent（可选） | `agent_audit_log`（操作审计，v0.16 新增）。**唯一落库的数据**——会话记忆/挂起态/限流仍在 Redis（可丢失的运行时状态）；该库不可达时 Agent 仍可启动与对话 |

> 各库的 `undo_log` 由 Seata AT 使用（**仅引入 Seata 客户端的 shop / order / ware 三库有**；
> product / search / chat / auth 不参与全局事务，故无此表；**agent 同理也没有**——它的审计表是
> append-only 的单表写入，不参与分布式事务）。完整表结构以 `sql/lingmart_*.sql` 为准。

### 5.2 核心表关键字段速览

**user（用户表）**
- `id`、`phone`（唯一）、`password`（BCrypt 加密）、`nickname`、`avatar`、`role`（`ROLE_USER`/`ROLE_SELLER`/`ROLE_ADMIN`）、`status`

**shop（店铺表）**
- `id`、`user_id`（店主，**唯一键 `uk_user_id`**：一个用户只能有一家店铺）、`shop_name`（唯一 `uk_shop_name`）、`shop_logo`、`description`
- `status`（**营业状态**：0 未启用 / 1 营业中 / 2 已禁用）与 `review_status`（**审核状态**：0 待审核 / 1 已通过 / 2 已拒绝 / 3 待重审）是**两个独立字段**——没有“审核中”这种 status 取值
- `draft_content`：待审核的新店铺信息草稿（JSON）；宽松重审下旧信息照常营业，通过后才生效

**sku（商品 SKU 表）**
- `id`、`spu_id`、`shop_id`（**数据隔离关键字段**）、`title`、`price`、`stock`、`sale_count`、`status`（上架/下架）
- 注意：**`category_id` 在 `spu` 表，不在 `sku`**
- `stock` 语义（v0.9 起）：**NULL = 不限量；0..N = 限量可售（0 = 售罄）**。真实可售/扣减以
  `lingmart_ware.ware_sku` 为准（`stock - locked_stock`），商品表 `stock` 仅声明“是否限量/上限”；`ware_sku` 的 `unlimited` 标记（1=不限量）使锁定/扣减/解锁/回补全部跳过。

**order（订单表）**
- `id`、`order_sn`（唯一订单号）、`buyer_id`、`shop_id`、`total_amount`、`pay_status`、`order_status`、`create_time`
- 退款相关：`refund_status`（0 无 / 1 待审核 / 2 已通过 / 3 已拒绝）、`refund_reason`、`refund_reject_reason`、`refund_time`
- 幂等：`uk_idempotency_buyer(idempotency_key, buyer_id)` 唯一键保证「同一买家同一幂等键只建一单」
- `order_status`：0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消 / 5 已退款

**ware_order_task（库存任务表）**
- `status`：0 已锁定 / 1 已扣减 / 2 已解锁 / 3 已退款回补——退款回补只在「1 已扣减」时才加回库存

**stock_flow_log（库存流水表）**
- `status`：0 待处理 / 1 已完成 / 2 补偿中 / 3 人工介入（补偿任务每 5 分钟重放 status=0；卡在 2 超时会置 3 告警）

### 5.3 索引设计要点
- 订单表：`order_sn`（唯一）、`uk_idempotency_buyer`（唯一，幂等键）、`buyer_id`、`order_status`、`idx_status_create(order_status, create_time)`（超时扫描）
- 商品表：`spu` 上有 `shop_id`、`category_id`、`status`、`review_status`、`application_id`，以及两条列表复合索引
  `idx_status_review_create(status, review_status, create_time)`（首页/检索）、`idx_shop_status_review(shop_id, status, review_status, create_time)`（店铺主页）
- 店铺表：`shop_name`（唯一）、`user_id`（唯一）
- 购物车/会话：`cart` 的 `uk_buyer_sku(buyer_id, sku_id)`（唯一，防重复加购）、`chat_conversation` 的 `uk_buyer_shop(buyer_id, shop_id)`（唯一）
- 审计日志：`audit_log` 的 `action`、`target_id`、`create_time`（管理端按时间倒序翻页）
- 用户表：`phone`（唯一索引）

---

## 六、开发阶段计划（分 5 个里程碑）

### 📌 第一阶段：基础架构搭建（Week 1）✅ 已完成

| 任务                              | 产出物                       | 验收标准                                             | 状态 |
| --------------------------------- | ---------------------------- | ---------------------------------------------------- | ---- |
| 创建父工程 `ling-mart-parent`     | `pom.xml`                    | 统一管理 Spring Boot 3.5.x / Cloud 2025.0.x 版本     | ✅    |
| 创建 `lingmart-common` 公共模块   | R / 全局异常 / JWT 工具      | `common` 模块可被其他服务引用                        | ✅    |
| 搭建 Nacos + MySQL + Redis        | `docker-compose.yml`         | 一键拉起中间件，Nacos 控制台可见                     | ✅    |
| 创建 `lingmart-auth` 认证服务     | 注册/登录/验证码/JWT 接口    | 注册登录成功返回 JWT，管理员账号自动预置             | ✅    |
| 创建 `lingmart-gateway` 网关      | 路由转发 + JWT 白名单过滤器  | 请求经网关正确转发，未登录请求被拦截                 | ✅    |

**关键产出**：项目可运行，注册登录闭环跑通，JWT 签发与网关鉴权验证正常。

**auth 接口一览**（全部经网关 `http://localhost:88` 访问）：

| 方法 | 路径                    | 说明                                     | 是否需要登录 |
| ---- | ----------------------- | ---------------------------------------- | ------------ |
| POST | `/api/auth/code/send`   | 发送验证码（`DEV_CODE_ENABLED=true` 时返回明文 devCode，生产默认关闭） | 否           |
| POST | `/api/auth/register`    | 注册（手机号 + 验证码 + 密码 + 昵称）    | 否           |
| POST | `/api/auth/login`       | 登录，返回 JWT + 用户信息                | 否           |
| GET  | `/api/auth/user/me`     | 查询当前用户信息（网关透传 userId）      | 是           |
| PUT  | `/api/auth/user/me`     | 更新个人资料（昵称/头像）                | 是           |
| POST | `/api/auth/logout`      | 退出登录（v0.4 起服务端失效：token 的 jti 写入 Redis 黑名单，网关后续校验命中即 401） | 是           |

- 公开接口白名单：`/api/auth/register`、`/api/auth/login`、`/api/auth/code/send`
- 默认管理员账号：手机号 `13800000000` / 密码 `admin123`（auth 启动时自动预置，可在 application.yml 修改）
- 请求头约定：登录后请求携带 `Authorization: Bearer <token>`；网关校验通过后向请求头写入 `X-User-Id`、`X-User-Role` 透传给下游服务

---

### 📌 第二阶段：商品与店铺核心域（Week 2-3）✅ 已完成

| 任务                             | 产出物                       | 验收标准                                              | 状态 |
| -------------------------------- | ---------------------------- | ----------------------------------------------------- | ---- |
| 创建 `lingmart-shop` 店铺服务    | 入驻申请、审核、店铺管理     | 用户可提交入驻，管理员可审核通过/拒绝并提升角色       | ✅    |
| 创建 `lingmart-product` 商品服务 | 分类/品牌 + SPU/SKU + 上下架 | 商家可发布商品、编辑、上下架（Feign 取店铺做数据隔离）| ✅    |
| 商品数据同步 ES                  | 上架写入 / 下架删除 ES 文档  | 已上架商品写入 ES（检索服务里程碑四提供）             | ✅    |
| 网关集成 JWT 鉴权                | 网关过滤器 + 白名单          | 未登录请求被拦截，合法 Token 放行                     | ✅    |

**关键产出**：申请开店（带商品清单）→ 审核通过 → 商品可售 → 上架同步 ES；改内容重新审核。

**shop 接口一览**（经网关 `http://localhost:88`；用户接口需登录，店铺主页公开）：

| 方法 | 路径                             | 说明                                                        | 权限   |
| ---- | -------------------------------- | ----------------------------------------------------------- | ------ |
| POST | `/api/shop/application`          | 提交入驻申请（**必须附商品清单**）；审核中/营业中禁再申请，被拒后可复用重来 | 用户   |
| GET  | `/api/shop/application/my`       | 我的申请状态                                                | 用户   |
| GET  | `/api/shop/my`                   | 我的店铺                                                    | 用户   |
| PUT  | `/api/shop/my`                   | 更新店铺信息 → 宽松重审（旧信息照常营业，通过后生效）        | 用户   |
| GET  | `/api/shop/storefront/{shopId}`  | 店铺主页（店铺信息 + 店主头像/昵称 + 在售商品，公开浏览）   | 公开   |
| GET  | `/api/shop/list`                 | 营业中店铺列表（首页以店铺为入口的卡片数据源，**公开**）     | 公开   |
| POST | `/api/shop/upload/image`         | 图片上传（拖拽多图/店铺logo/头像统一入口，本地磁盘；每用户 1 分钟限 20 张） | 用户   |
| GET  | `/api/admin/shop/applications`   | 入驻申请列表（可按状态过滤）                                | 管理员 |
| POST | `/api/admin/shop/applications/{id}/review` | 审核申请（通过→店铺营业+商品通过上架+角色提升；拒绝→整体拒绝） | 管理员 |
| GET  | `/api/admin/shop/shops`          | 店铺列表（按审核状态过滤，用于信息变更重审）                 | 管理员 |
| POST | `/api/admin/shop/shops/{shopId}/review` | 审核店铺信息变更（通过→应用新信息并重建该店 ES 文档；拒绝→丢弃草稿）。ES 重建在事务提交后执行，失败会报错并提示用 product 的管理端重建入口补做 | 管理员 |
| GET  | `/api/admin/shop/audit/list`     | 审核审计日志（分页）                                        | 管理员 |

> **管理员/用户分离（v0.2）**：管理端接口统一 `/api/admin/**`，网关按角色拦截（仅 ROLE_ADMIN，其余 403）；
> 管理后台前端为独立布局，仅「审核中心 + 审计日志」，不含商城功能。

**product 接口一览**（经网关 `http://localhost:88`，均需登录）：

| 方法 | 路径                        | 说明                                              | 权限   |
| ---- | --------------------------- | ------------------------------------------------- | ------ |
| GET  | `/api/product/category/list`| 分类列表                                          | 用户   |
| GET  | `/api/product/brand/list`   | 品牌列表                                          | 用户   |
| POST | `/api/product`              | 发布商品 → **待审核**，未上架                      | 卖家   |
| PUT  | `/api/product/{id}`         | 编辑商品内容 → **待重审**（宽松：旧内容先卖，通过后生效） | 卖家 |
| PUT  | `/api/product/{id}/stock`   | 仅改库存（**不触发重审**）                        | 卖家   |
| POST | `/api/product/{id}/onshelf` | 上架（**仅审核通过的商品**可上架，写 ES）          | 卖家   |
| POST | `/api/product/{id}/offshelf`| 下架（删除 ES 文档）                              | 卖家   |
| GET  | `/api/product/{id}`         | 商品详情（含店铺名与审核状态；**可见性门禁**：公开=上架且审核通过，非公开仅属主可预览、其他人按“商品不存在”） | 用户/卖家 |
| GET  | `/api/product/my`           | 我的商品列表（含审核状态）                         | 卖家   |
| GET  | `/api/product/list`         | 已通过审核且在售的商品列表                         | 用户   |
| GET  | `/api/product/{id}/skus`    | 商品 SKU 分页（详情内嵌 SKU 上限 100 条，超出走此接口） | 用户   |
| GET  | `/api/admin/product/review/list`  | 待审商品列表（status=0 新商品 / 3 内容重审，分页）    | 管理员 |
| POST | `/api/admin/product/review/{id}`  | 审核商品（通过→上架+应用草稿；拒绝→丢弃草稿）         | 管理员 |
| POST | `/api/admin/product/review/batch` | 批量审核商品（spuIds + approve + rejectReason）       | 管理员 |
| GET  | `/api/admin/product/review/by-application/{id}` | 按入驻申请查其携带的商品（审核申请时展示） | 管理员 |
| GET  | `/api/admin/product/audit/list`  | 审核审计日志（分页）                                        | 管理员 |
| POST | `/api/admin/product/shop/{shopId}/reindex-es` | 人工重建某店铺在售商品的 ES 文档（店名/销量修正兜底，返回重建条数） | 管理员 |
| POST | `/api/admin/product/category` | 新增分类                                          | 管理员 |
| POST | `/api/admin/product/brand`   | 新增品牌                                          | 管理员 |

> **审核状态机**（商品 `review_status`）：0待审核 → 1已通过 / 2已拒绝 / 3待重审。内容编辑进草稿（`draft_content`），
> 旧内容照常销售，审核通过才生效；审核被拒 → 丢弃草稿、旧内容继续卖（宽松）。改库存不重审。
> 店铺同理：`review_status` 0/1/2/3，改信息进草稿照常营业。仅审核通过且在售的商品可被搜索/下单（服务端校验）。
> 服务间内部接口（`/internal/**`，**不对公网暴露**：网关只路由 `/api/**`，故外部访问不到）：
> auth `/internal/user/role`、`/internal/user/{id}`、`/internal/user/batch`；
> shop `/internal/shop/{shopId}`、`/internal/shop/by-user/{userId}`、`/internal/shop/batch`（批量取店铺：chat 会话列表、product 审核列表用）；
> product `/internal/product/sku/{skuId}`、`/internal/product/batch`、`/internal/product/application/{id}/approve|reject`、`/internal/product/sale`（支付后**累加**销量）、`/internal/product/sale/rollback`（退款后**回退**销量，`order_sn` 幂等）、`/internal/product/shop/{shopId}/reindex-es`（店铺改名后重建 ES）；
> ware `/internal/ware/lock|deduct|unlock|restock`、`/internal/ware/sku/sync`、`/internal/ware/stock/batch`、`/internal/ware/flow/human`（查人工介入流水）、`/internal/ware/flow/manual`（人工处置）。
> **搜索以店铺为入口**：首页搜索框按商品关键词检索（ES），返回**命中的店铺卡片**（`/api/search/shops`，点击进店查看在售商品），商品不直接展示。搜索「手机」会聚合同类商品的店铺。
> **订单状态流转**：0待支付 → 1已支付 → 2已发货 → 3已完成（买家确认收货）/ 4已取消 / 5已退款。
> **退款状态（refund_status）**：0无退款 → 1退款中（买家已申请）→ 2已退款（卖家通过，订单置 5 + 库存回补）/ 3退款拒绝（可重新申请）。

---

### 📌 第三阶段：订单与库存闭环（Week 4-5）✅ 已完成

| 任务                           | 产出物                    | 验收标准                                       | 状态 |
| ------------------------------ | ------------------------- | ---------------------------------------------- | ---- |
| 创建 `lingmart-order` 订单服务 | 下单、购物车、订单流转    | 用户可下单生成订单，支持支付/取消/发货          | ✅    |
| 创建 `lingmart-ware` 仓储服务  | 库存锁定/扣减/解锁        | 下单时经 Feign 锁库存，库存原子扣减             | ✅    |
| 集成 Seata 分布式事务          | `@GlobalTransactional` AT | 下单→锁库存，任一失败整体回滚（含 undo_log）    | ✅    |
| 集成 RabbitMQ                  | TTL+死信 延迟队列         | 支付超时订单状态自动变为已取消并解锁库存        | ✅    |

**关键产出**：完整下单流程跑通（下单→锁库存→支付→扣库存→发货→超时自动取消）。

**order/ware 接口一览**（经网关 `http://localhost:88`，均需登录）：

| 方法 | 路径                              | 说明                                        |
| ---- | --------------------------------- | ------------------------------------------- |
| POST | `/api/cart`                       | 加入购物车                                  |
| GET  | `/api/cart`                       | 购物车列表                                  |
| PUT  | `/api/cart/{id}`                  | 更新购物车条目（数量/勾选）                  |
| DELETE | `/api/cart/{id}`                | 删除购物车条目                              |
| POST | `/api/order`                      | 下单（Seata：建单+锁库存），返回订单号        |
| POST | `/api/order/{orderSn}/pay`        | 模拟支付（扣库存）                          |
| POST | `/api/order/{orderSn}/cancel`     | 取消订单（解锁库存）                        |
| POST | `/api/order/{orderSn}/ship`       | 卖家发货                                    |
| POST | `/api/order/{orderSn}/confirm`    | 买家确认收货（订单完成）                    |
| POST | `/api/order/{orderSn}/refund`     | 买家申请退款（已支付/已发货/已完成、无退款记录时） |
| POST | `/api/order/{orderSn}/refund/review` | 卖家审核退款（通过=订单置「已退款」+库存回补；拒绝需填原因） |
| GET  | `/api/order/list`                 | 买家订单列表（分页）                        |
| GET  | `/api/order/seller/list`          | 卖家订单列表（按店铺过滤，分页）            |
| GET  | `/api/order/{orderSn}`            | 订单详情（买家或所属卖家可看，其余 403）      |
| GET  | `/api/order/export?days=N`        | 买家订单导出 xlsx（流式，EasyExcel）         |
| GET  | `/api/order/seller/export?days=N` | 卖家订单导出 xlsx                            |
| GET  | `/api/order/seller/stats?range=7d\|30d\|all` | 卖家经营看板（概览/状态分布/趋势/热销 TOP5） |

> 服务间 Feign：product 发布/更新商品时同步库存到 ware（`/internal/ware/sku/sync`）；order 下单调
> ware 锁库存、支付扣减、取消解锁（`/internal/ware/lock|deduct|unlock`），下单时经 `/internal/product/sku/{skuId}`
> 获取服务端商品快照（防客户端改价）。Seata 版本 2.5.0（**注意坐标已是 `org.apache.seata`，非旧 `io.seata`**）。
>
> **支付→库存扣减 异步化 + 流水对账（2026-09-06，重构为事件驱动）**：
> - 正常路径：order `pay()` 在**同一本地事务**内“订单置已付 + 销量同步累加(product) + 支付成功事件写入 Outbox 表 `order_paid_event`(status=0)”→ 提交后 `OrderPaidOutboxRelay`（order 内每 5s）发布到 `order.paid.exchange / order.paid.ware.queue` 并置已发 → ware 的 `OrderPaidListener` 幂等扣减：同一 (order_sn, sku) 已扣过即跳过；成功后在**同一事务**写 `stock_flow_log`（`change_num<0`，**status=1 已完成**一次成型）并把 `ware_order_task` 置“已扣减”。
> - 必达保障：Outbox 与订单状态同库落盘（提交失败事件随之回滚）；队列 durable；ware 消费失败按退避重试、耗尽进 `order.paid.ware.dlq`；Outbox 发布失败下轮重发（eventId 全局唯一 + ware 幂等 → 不会重复扣）。
> - 补偿兜底：`StockFlowCompensationJob`（每 5 分钟）只处理**异常/历史遗留**的 status=0 流水（正常链路已为 1）：0→2 抢占→重放扣减成功 1 / 失败 3；失败或单次扫描超 100 条打 `【STOCK_FLOW_ALERT】`（含 orderSn/skuId/原因），绝不静默跳过。
> - 人工处置（运维）经内部接口：`GET /internal/ware/flow/human`（查 status=3）、`POST /internal/ware/flow/manual?id=&action=reset|done`（重置待处理重试 / 标记已完成）；`/internal/ware/deduct` 保留用于手动补偿触发。**只补不删**：本机制只重放/异步扣减，取消/退款解锁仍走既有同步链路。
> - 说明：销量 DB 累加（product `updateSale`）仍在支付全局事务内同步完成；**ES 销量刷新已异步化**——`updateSale` 只发布 `SaleUpdateEvent`，由 `SalesEsRefreshListener` 在事务提交后（@Async，专用线程池 `config/AsyncConfig`）执行，避免 ES/店名 Feign 网络 IO 拖住支付事务与 SPU 行锁；ES 写失败仍落 `es_write_retry` 补偿表。店铺名另加 5 分钟**Redis 共享缓存**（`lingmart:shop:name:{shopId}`，多实例一致）减少对 shop 的重复 Feign。
> - **`ware_order_task` 状态机**：0 已锁定 → 1 已扣减 → 2 已解锁 / 3 已退款回补；**退款回补只在「1 已扣减」时才加库存**——所以「流水卡在补偿中」会导致退款不回补（v0.11 已加卡死回收与告警）。
> **禁止自买**：下单时校验买家不得购买自己店铺的商品（防自买刷销量/信用）。

---

### 📌 第四阶段：检索与前端联调（Week 6-7）✅ 已完成

| 任务                            | 产出物                    | 验收标准                                        | 状态 |
| ------------------------------- | ------------------------- | ----------------------------------------------- | ---- |
| 完善 `lingmart-search` 检索服务 | 多条件搜索、分页          | 可按关键词、分类、价格区间、排序搜索商品         | ✅    |
| Vue3 前端搭建                   | 首页/详情/购物车/下单/订单/卖家中心 | 前端可调用后端所有核心接口                 | ✅    |
| 前后端联调                      | 全流程打通                | 用户可完成“注册→开店→发布商品→搜索→下单”完整链路 | ✅    |

**关键产出**：完整 C2C 交易链路跑通。

**search 接口一览**（经网关，**免登录**：`/api/search/**` 在网关白名单内，首页未登录也要能逛）：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET  | `/api/search?keyword=&categoryId=&minPrice=&maxPrice=&sort=&page=&size=` | 商品检索（内部/兼容，前端首页不再直接展示商品） |
| GET  | `/api/search/shops?keyword=&categoryId=&minPrice=&maxPrice=&page=&size=` | **店铺检索（首页搜索）**：按命中商品聚合出店铺（shopId/shopName/在售数/起售价/代表图），点击进店查看商品；**空索引返回空结果（不报系统繁忙）** |

**chat 接口一览**（v0.2 新增，经网关，需登录；WebSocket 握手 `/ws` 走白名单，token 经 STOMP CONNECT 头校验）：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/api/chat/conversation` | 发起/进入会话（{shopId}），返回会话详情（对方头像/昵称/手机号，会话内可见） |
| GET  | `/api/chat/conversations` | 我的会话列表（买家侧 + 老板侧），预览仅头像/昵称；店铺与对端用户信息经 `/internal/shop/batch`、`/internal/user/batch` 一次批量获取（避免逐会话 Feign 的 N+1） |
| GET  | `/api/chat/conversations/{id}/messages` | 消息历史（分页，升序）；本页发送者信息批量获取后组装 |
| GET  | `/api/chat/conversations/{id}/messages/since` | **增量补拉**（`sinceId` + `limit`，默认 100、上限 200）：返回 id 大于 `sinceId` 的消息（升序），供 WebSocket 重连后补齐断线期间漏掉的消息；`sinceId` 省略或 ≤0 时返回最新 `limit` 条 |
| WS   | `/app/chat/send` → 广播 `/topic/conversation/{id}` | STOMP 实时发送消息，双方订阅同一 topic 实时收到；请求可带 `clientMsgId`（前端生成）支持**乐观渲染**（先上屏「发送中」气泡，收到广播后按该 ID 替换为正式消息）；发送频控默认 30 条/30 秒（`lingmart.chat.rate-limit.max-per-30s` / `CHAT_SEND_MAX_PER_30S` 可配），且订阅校验会话成员资格 |
| WS   | `/user/queue/errors` | **发送失败回执**（服务端定向投递，前端订阅）：限流/无权等失败不再以 STOMP ERROR 帧断连，而是投递 `{conversationId, clientMsgId, content, code, msg}`，前端把对应气泡标记为「发送失败」并支持一键重发（文本不丢） |

> 前端 `ChatView` 在连接/重连成功后会自动订阅会话 topic 与错误队列，并以本地最后一条消息 id 为游标调用 `/messages/since` 补拉缺口；返回条数达到上限时降级为全量重载，避免中间留空洞。发送失败的气泡标红并可一键重发。

**前端（`lingmart-frontend`）**：Vite + Vue3 + Vue Router + Pinia + Axios + Element Plus。
开发启动 `npm run dev`（端口 5173），API 直连网关 `http://localhost:88/api`（网关已配 CORS，请求拦截器自动带 JWT）。
页面：首页搜索、商品详情、购物车、结算下单、订单列表、申请开店、卖家中心（店铺/发布/上下架/发货）。

---

### 📌 第五阶段：高可用与部署（Week 8）

| 任务                | 产出物                | 验收标准                                   | 状态 |
| ------------------- | --------------------- | ------------------------------------------ | ---- |
| Sentinel 限流配置   | 网关/核心接口限流规则 | 高并发下触发限流返回友好提示（HTTP 429 + R JSON） | ✅ 已接入（批7） |
| 中文检索增强        | IK 分词 + 显式 mapping | 「苹果」能搜到「苹果手机」                   | ✅ 已接入（批7） |
| 订单导出            | 卖家/买家 xlsx 导出   | 中文表头、无乱码、文件名带日期               | ✅ 已接入（批7） |
| 卖家数据统计        | 卖家中心经营看板      | 销售额/趋势/热销与订单列表一致               | ✅ 已接入（批7） |
| Docker Compose 编排 | `docker-compose.yml`  | 一键启动所有服务 + MySQL + Redis + ES + MQ | ✅ 已接入（批7） |
| 部署文档            | README.md             | 新开发者可按文档 5 分钟启动项目              | ✅ 已接入（批7） |

**关键产出**：项目可容器化部署，具备基础高可用能力。

> 📜 **历次迭代的详细变更记录（批7~批15 / v0.3~v0.11）已迁到 [CHANGELOG.md](CHANGELOG.md)**——
> 本文档只描述**当前状态**（怎么用、有什么），不再堆叠变更日志。

---

## 七、服务间调用关系图

┌─────────────────────────────────────────────────────────────┐
│ 前端（Vue3） │
└─────────────────────────┬───────────────────────────────────┘
│ HTTP
▼
┌─────────────────────────────────────────────────────────────┐
│ lingmart-gateway（API网关 :88） │
│ JWT鉴权 + 路由转发 + 跨域 │
└───────┬─────────────────┬─────────────────┬─────────────────┘
│ │ │
▼ ▼ ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│ lingmart-auth │ │lingmart-shop │ │lingmart- │
│ :8000 │ │ :8100 │ │product :10001 │
│ 注册/登录/JWT │ │ 店铺/入驻审核 │ │ 商品CRUD/上架 │
└───────────────┘ └───────────────┘ └───────┬───────┘
│ Feign调用
▼
┌───────────────┐
│lingmart-ware │
│ :11000 │
│ 库存扣减 │
└───────┬───────┘
│
┌────────────────────┼────────────────────┐
│ │ │
▼ ▼ ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│lingmart-order │ │lingmart-search│ │lingmart-third-│
│ :9000 │ │ :12000 │ │party :30000 │
│ 订单/购物车 │ │ ES商品检索 │ │ OSS/短信 │
└───────────────┘ └───────────────┘ └───────────────┘

> **v0.2 新增**：`lingmart-chat`（:13000，WebSocket STOMP）经网关 `/ws` 与 `/api/chat/**` 接入；
> 经 Feign 调用 auth（取用户信息）与 shop（校验店铺）。管理端 `/api/admin/**` 由网关角色拦截（仅 ROLE_ADMIN）。
> 店铺主页 `/api/shop/storefront/{id}` 由 shop 聚合 product（在售商品）与 auth（店主公开信息）。
---

## 八、关键决策记录（避坑指南）

| 决策点           | 决策内容                      | 理由                                              |
| ---------------- | ----------------------------- | ------------------------------------------------- |
| **JDK 版本**     | Java 21 LTS（虚拟线程）       | 虚拟线程显著提升 IO 密集型接口吞吐，且为当前 LTS   |
| **服务拆分粒度** | 8 个核心服务 + 1 个可选 AI 组件 | C2C 场景足够，不过度拆分增加复杂度                |
| **分布式事务**   | 开店申请、下单/支付/取消→库存均用 Seata AT | 建店+建商品、订单状态与库存锁定/扣减/解锁强一致；异步场景（超时取消兜底）仍配 MQ/定时扫描 |
| **鉴权方案**     | 网关统一 JWT 校验             | 无状态、易扩展，下游服务通过 Header 获取用户信息  |
| **数据隔离**     | 商品表通过 `shop_id` 关联店铺 | 每个卖家只能操作自己店铺的商品                    |
| **商品搜索**     | ES 仅存储检索所需字段         | 详情查 MySQL，ES 只存 ID+标题+价格+分类等检索字段 |
| **商品分类**     | v0.9 起分类维度下线（库表保留不删） | 个人 C2C 分类粗粒度价值有限，又遇历史乱码；改为关键词/价格检索，前端表单/详情/首页筛选不再出现分类 |
| **SKU 数量**     | `stock=NULL` = 不限量；限量才展示/扣减 | 满足个人闲置转卖“默认无限供应、可限量”的需求；下单到退款全链路按 `ware_sku.unlimited` 跳过库存操作 |

---

## 九、项目文件结构（均为已实现）

```text
ling-mart/                          # Git 仓库根目录
├── pom.xml                         # 父工程（Maven 多模块，统一版本管理）
├── lingmart-common/                # 公共模块 ✅
│   └── src/main/java/com/lingmart/common/
│       ├── result/                 # 统一返回 R、状态码枚举
│       ├── exception/              # 业务异常、全局异常处理器
│       ├── jwt/                    # JWT 工具（JJWT 0.12）
│       └── constant/               # 认证常量（请求头/角色/白名单）
├── lingmart-gateway/               # API 网关 :88 ✅
│   └── src/main/java/com/lingmart/gateway/
│       ├── filter/                 # JWT 白名单鉴权过滤器（WebFlux GlobalFilter）
│       └── config/                 # CORS 跨域配置
├── lingmart-auth/                  # 认证中心 :8000 ✅
│   └── src/main/java/com/lingmart/auth/
│       ├── controller/             # 注册/登录/验证码/用户信息接口 + 管理端角色修正
│       ├── service/                # 业务逻辑 + 验证码（Redis 存储；明文返回受 DEV_CODE_ENABLED 控制）
│       ├── entity/ mapper/ dto/ vo/  # 实体、Mapper、入参、出参
│       └── config/                 # MyBatis-Plus、BCrypt、管理员预置、限流 Bean（复用 common 的 RedisRateLimiter）
├── lingmart-shop/                  # 店铺服务 :8100 ✅（入驻/审核/店铺 + Feign 提升角色）
├── lingmart-product/               # 商品服务 :10001 ✅（分类/品牌/SPU-SKU + 上下架 + ES 同步）
├── lingmart-order/                 # 订单服务 :9000 ✅（购物车/下单/支付/超时取消 + Seata + RabbitMQ + 导出/统计）
├── lingmart-ware/                  # 仓储服务 :11000 ✅（库存锁定/扣减/解锁 + Seata）
├── lingmart-search/                # 检索服务 :12000 ✅（ES 多条件检索 + IK 中文分词 + Sentinel 降级）
├── lingmart-chat/                  # 聊天服务 :13000 ✅（WebSocket STOMP 实时会话 + 消息持久化，v0.2）
├── lingmart-agent/                 # 网页浮窗 Agent :14000 ✅（Spring AI 问答/引导 + SSE，知识种子 kb/，见 docs/agent-copilot.md）
├── lingmart-frontend/              # Vue3 前端 ✅（Vite+Element Plus+ECharts，npm run dev；含 components/copilot 浮窗组件）
├── sql/                            # 数据库脚本（每服务一个建库脚本）✅
│   ├── lingmart_auth.sql           # 认证中心（user 表；管理员账号由服务启动时预置，非 SQL 种子）
│   ├── lingmart_shop.sql           # 店铺服务（shop / shop_application / audit_log + undo_log）
│   ├── lingmart_product.sql        # 商品服务（category/brand/spu/sku/sku_images/audit_log/es_write_retry/sale_rollback_log + 种子数据）
│   ├── lingmart_order.sql          # 订单服务（order / order_item / cart / order_paid_event + undo_log）
│   ├── lingmart_ware.sql           # 仓储服务（ware_sku / ware_order_task / stock_flow_log + undo_log）
│   ├── lingmart_chat.sql           # 聊天服务（chat_conversation / chat_message）
│   ├── lingmart_agent.sql          # Agent 审计库（agent_audit_log；Agent 唯一落库的数据）
│   └── upgrade/                    # 存量库增量迁移脚本（按日期命名，见 docs/ops-checklist.md 三）
├── scripts/                        # 一键执行脚本 ✅
│   ├── build.bat                   # 编译打包（JDK 21，全部模块）
│   ├── pull-images.bat             # 拉取中间件镜像（绕过失效加速器）
│   ├── start-infra.bat             # 启动基础设施容器（Nacos/MySQL/Redis/ES/MQ/Seata/Zipkin/Chroma）
│   ├── start-services.bat          # 启动 8 个核心服务（各开一个窗口）
│   ├── stop-services.bat           # 停止业务服务
│   ├── dev-start.sh                # Git Bash 下 nohup 启动 7 个服务（不含 chat）
│   ├── verify-health.sh            # 一键健康巡检（基础设施 + 库结构 + 8 个核心服务）
│   ├── migrate.sh / migrate.bat    # 存量库增量迁移（按序执行 sql/upgrade 全部脚本，幂等）
│   ├── push-nacos-config.sh        # 推送共享配置与 Sentinel 规则到 Nacos（改配置后必跑）
│   ├── reset-data.sh / .bat        # 重置数据库与数据卷（慎用：会删光数据）
│   ├── load-test/                  # k6 压测脚本（lingmart-core.k6.js：读/购物车/下单/支付四场景）
│   │   └── seed/                   # 压测数据：播种/清理/校验（seed、teardown、verify 的 sql + bat/sh + post-seed.mjs）
├── docker-compose.yml              # 基础设施编排（Nacos/MySQL/Redis/ES/RabbitMQ/Seata/Zipkin/Chroma；Ollama 走 profile）✅
├── docker-compose.business.yml     # 业务服务容器编排（9 个服务，与上面叠加使用）✅
├── docker/business.Dockerfile      # 业务服务统一镜像（多阶段构建）✅
├── docker/seata/                   # Seata TC 配置（application.yml，注册到 Nacos）
├── docker/elasticsearch/           # ES 镜像 Dockerfile（内置 IK 中文分词插件 v8.15.5）✅
├── nacos-config/                   # Nacos 共享配置与 Sentinel 规则源文件（push-nacos-config.sh 推送）
├── .run/                           # IDEA 运行配置（9 个服务 + 复合配置「全部服务」，含 8 个核心服务）
├── docs/                           # 设计/分析/运维文档 ✅
│   ├── architecture-diagrams.md    # 架构图集（部署拓扑/时序/状态机/ER，Mermaid）
│   ├── ops-checklist.md            # 部署与运维清单（环境变量、迁移、冒烟、故障排查）
│   ├── todo-and-optimization.md    # 待办与待优化事项（含已知边界）
│   ├── production-readiness.md     # 生产级差距分析（P0/P1/P2 清单）
│   ├── agent-copilot.md            # 网页浮窗 Agent(Copilot) 设计文档（含 v1.0 落地说明）
│   ├── load-testing.md             # 性能测试使用说明（k6）
│   ├── load-test-report-2026-09-21.md  # 压测报告（首轮真正跑通的结果与未覆盖范围）
│   └── code-review-2026-08-29.md   # 历史代码评审快照（归档，不代表当前状态）
├── CHANGELOG.md                    # 历次迭代变更记录（批1~批18 / v0.1~v0.14）
└── README.md                       # 项目说明（当前状态）

---

## 十、测试与接口文档

### 10.1 测试体系

| 层级     | 位置                                                         | 说明                                           | 运行方式                                                                                                        |
| -------- | ------------------------------------------------------------ | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| 单元测试 | `lingmart-common` / `lingmart-auth` / `lingmart-shop` / `lingmart-product` / `lingmart-search` / `lingmart-ware` / `lingmart-order` 的 `src/test` | JUnit5 + Mockito，不依赖数据库与中间件 | `mvn test`（默认执行） |
| 服务级集成测试 | `lingmart-order/.../OrderFlowIntegrationTest` | 真实 `WareServiceImpl` + 内存 Mapper，模拟 Seata 流程（建单→锁库存→支付→扣减 / 建单→取消→解锁） | 随 `mvn test` |
| 熔断/降级测试 | product / search / order 的 Sentinel blockHandler 单测 + 规则文件解析校验 | 直接验证降级行为（搜索限流返回空、详情限流抛业务异常、下单限流提示）与规则文件可加载 | 随 `mvn test` |
| 集成测试 | `lingmart-auth/.../AuthApiIT`                                | REST Assured 对运行中 auth(8000) 发起真实请求   | 启动 auth 后，在 IDEA 直接运行，或 `mvn test -Dtest=AuthApiIT -Dsurefire.failIfNoSpecifiedTests=false -pl lingmart-auth -am` |
| 性能测试 | `scripts/load-test/lingmart-core.k6.js`（+ `load-test/seed/` 播种与清理） | 四个场景：只读（检索/店铺主页/详情）、购物车闭环、下单取消闭环、下单支付；每请求带 `flow` 标签 → 报告可看分链路 p95；断言含数据正确性校验 | 见 `docs/load-testing.md`（**先播种再压**） |
| 前端单测 | `lingmart-frontend/src/__tests__`（auth store / request 拦截器 / ImageUploader 组件） | Vitest + @vue/test-utils + jsdom；mock 掉网络与 Element Plus，不依赖后端 | `cd lingmart-frontend && npm test` |

**当前覆盖场景**（v0.2 起含业务服务单测 + Sentinel 降级测试）：
- JWT：签发-解析往返、合法/篡改/过期/错误密钥校验
- 认证：注册/登录/当前用户/全链路（auth 侧 18 项）
- 开店申请：首次创建、营业中/审核中/已禁用禁止、**被拒后复用原店铺行重来**、必须携带商品
- 入驻审核：通过（传店铺名避免回调 + 角色提升 + 审计）、拒绝（不提升角色）、非管理员 403
- 商品：开店批量建商品、按申请审核通过（**不再回调 shop**）、单商品审核拒绝、商品不存在
- 检索：**空索引返回空结果（不报系统繁忙）**、Sentinel 限流降级返回空
- 仓储：库存同步（新增/更新）、锁定库存不足抛异常、解锁幂等（已解锁跳过）、解锁/扣减行数 0 抛异常
- 订单：下单幂等（重复键返回已有单、禁止自买）、支付/取消/确认/发货状态机、超时兜底、流式导出
- 订单↔仓储流程：建单锁库存 → 支付扣减 / 取消解锁（服务级 Seata 流程模拟）
- 认证：logout 后 token 加入黑名单（jti）、非法 token 静默忽略
- 前端（Vitest，`npm test`）：auth 登录态（登录/登出/getters/localStorage 恢复）、request 拦截器（Bearer 注入 / R 解包 / 业务码与 HTTP 401 跳登录 / Blob 直通）、ImageUploader（单图=头像/logo 显示在上传位可更换、多图=封面角标/排序/删除/数量上限、类型与大小校验）—— **单图「点击更换」曾被数量校验误拦（点更换无法替换）**，由前端单测发现并修复
- 通用：`R.checkOk` 下游错误透传（**修复 Integer 比较引用陷阱**：code=200 经 Feign/Jackson 反序列化为新 `Integer` 实例，与 SUCCESS 常量引用不同，用 `!=` 会把成功误判为失败导致 Seata 回滚「店铺不入表」；已改 `equals` 并补回归测试 `checkOk_成功码为独立Integer实例不误判失败`）、Sentinel 规则文件可加载

> 说明：`AuthApiIT` 以 `*IT` 结尾，`mvn test` 不会自动执行；auth 服务未启动时自动跳过，不会导致构建失败。
>
> 说明（构建约定）：父 POM 统一为 surefire 配置 `-javaagent` 加载 `mockito-core`，消除 JDK 21+ 的
> 「Mockito is currently self-attaching」警告；`mockito.version` 在父 POM 显式定义（BOM 的属性不随
> `import` 继承，与 `lombok.version` 同理），**升级 Spring Boot 时需同步核对**，否则 agent 路径会指向不存在的 jar。
> 另：Windows 控制台默认代码页 936(GBK) 而 JVM 输出 UTF-8，`mvn test` 里的中文会乱码——先执行 `chcp 65001` 即可，
> 仅影响可读性、不影响结果。

### 10.2 接口文档（Apifox / Swagger）

各服务均集成 springdoc OpenAPI，并通过网关统一聚合，**推荐使用统一入口**：

- **统一 OpenAPI JSON（Apifox 一键导入）**：`http://localhost:88/v3/api-docs/merged`
  - 网关并行拉取 auth / shop / product / ware / order / search 六份文档合并为一份，路径取并集，
    已过滤 Feign 内部接口（`/internal/**`），服务地址统一指向网关。
  - 单个服务未启动时自动跳过，其余服务仍正常导出。
- **统一 Swagger UI**：`http://localhost:88/swagger-ui.html`（右上角下拉切换 8 个服务：auth/shop/product/ware/order/search/chat/agent）
- 各服务独立文档（调试或单独导入用）：`http://localhost:8000/v3/api-docs`（auth）、`http://localhost:8100/v3/api-docs`（shop）、`http://localhost:10001/v3/api-docs`（product）、`http://localhost:11000/v3/api-docs`（ware）、`http://localhost:9000/v3/api-docs`（order）、`http://localhost:12000/v3/api-docs`（search）

**导入 Apifox**：新建/打开项目 → 「导入数据」→ 选择「OpenAPI (Swagger)」→ **URL 导入**填入 `http://localhost:88/v3/api-docs/merged`，一次导入全部服务接口。文档已声明 JWT Bearer 鉴权，在 Apifox 环境变量中配置 `Authorization: Bearer {{token}}`（token 由 `/api/auth/login` 返回）后可直接调试登录后的接口。

> 网关需先启动（依赖 Nacos）；文档路径已加入网关 JWT 白名单，无需登录即可访问。
```
