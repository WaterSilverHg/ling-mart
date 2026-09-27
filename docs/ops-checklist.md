# 轻营 · 部署 / 运维清单（可执行 checklist）

> 适用对象：本机（Windows + Docker Desktop + JDK21）从零部署、日常运维、生产加固。
> 目标：把「服务起没起、配置对不对、库表新不新」一次说清，减少反复踩坑。
> 快速体检：`bash scripts/verify-health.sh`（一键巡检基础设施 + 8 个核心服务；不含可选的 lingmart-agent）。

---

## 〇、端口与服务速查

| 端口 | 服务 | 类型 | 说明 |
| --- | --- | --- | --- |
| 88 | lingmart-gateway | API 网关 | 所有外部请求入口（前端 `VITE_API_BASE_URL` 指向 `http://localhost:88/api`） |
| 8000 | lingmart-auth | 认证中心 | 注册/登录/JWT/个人资料，依赖 Redis |
| 8100 | lingmart-shop | 店铺服务 | 入驻申请/店铺主页/图片上传 |
| 10001 | lingmart-product | 商品服务 | 商品 CRUD/审核/ES 同步/缓存，依赖 Redis + ES |
| 11000 | lingmart-ware | 仓储服务 | 库存锁/回补，依赖 Seata |
| 9000 | lingmart-order | 订单服务 | 下单/支付/发货/退款/超时取消，依赖 RabbitMQ + Seata |
| 12000 | lingmart-search | 检索服务 | ES 多条件检索 |
| 13000 | lingmart-chat | 聊天服务 | WebSocket(STOMP) + 消息历史 |
| 14000 | lingmart-agent（可选） | 网页浮窗 Agent | **不属于核心链路**：`start-services.bat`/`verify-health.sh`/「全部服务」运行配置都不含它。问答 + 页面操作引导（SSE），依赖 Redis + Chroma；LLM 默认第三方 OpenAI 兼容 API |
| 3307 | MySQL 8 | 基础设施 | 各业务独立库（容器内 3306，宿主映射 3307） |
| 8848 | Nacos 2.4.3 | 基础设施 | 注册中心 + 配置中心（控制台 `http://localhost:8848/nacos`） |
| 6380 | Redis 7 | 基础设施 | 缓存/验证码/限流（容器内 6379，宿主映射 6380） |
| 9201 | Elasticsearch 8.15.5 | 基础设施 | 商品检索（容器内 9200，宿主映射 9201） |
| 5672 / 15672 | RabbitMQ 3.13 | 基础设施 | AMQP / 管理台（guest/guest） |
| 8091 | Seata TC 2.5.0 | 基础设施 | 分布式事务协调器 |
| 9411 | Zipkin 3.4 | 基础设施 | 链路追踪 UI 与上报入口（**随 `docker compose up -d` 默认启动**，各服务 `ZIPKIN_ENABLED` 默认 true；UI `http://localhost:9411`） |
| 18000 | ChromaDB | 基础设施 | Agent 知识库向量库（宿主对外 18000 ↔ 容器内 8000；已避开 auth:8000 与默认 8000。`lingmart-agent` 需其可达，否则启动会因建 collection 失败） |
| 11434 | Ollama（可选） | 基础设施 | Agent 备用本地 LLM（`profiles: ["ai"]` 默认不启动） |

> ⚠️ 注意：Redis 的**宿主端口是 6380**（容器内 6379，docker-compose 做了端口映射），业务服务读取 `REDIS_PORT=6380`。
> （`start-infra.bat` 的回显此前误写 6379，v0.11 已修正。）

---

## 一、环境变量与密钥（首次必做）

### 1. 创建 `.env`
```bash
cd 项目根目录
copy .env.example .env     # Windows 命令行
cp .env.example .env       # Git Bash
```

### 2. 生成密钥并填入 `.env`

| 变量 | 生成方式 | 要求 |
| --- | --- | --- |
| `NACOS_AUTH_TOKEN` | `openssl rand -base64 48` | base64 解码后 ≥ 32 字节；Nacos 鉴权用 |
| `JWT_SECRET` | `openssl rand -hex 32` | ≥ 32 字节；**必需**。容器编排（`docker-compose.business.yml`）已改为 `${JWT_SECRET:?…}`——未设置直接拒绝启动；本地 `java -jar`/IDEA 若未设则退回 yml 内置开发值（启动时输出 ERROR 告警） |
| `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` | 与 `NACOS_AUTH_TOKEN` 配套 | `docker-compose.yml` 从 `.env` 读取，用于 Nacos 鉴权（缺失时 Nacos 的鉴权会拒绝请求） |
| `NACOS_PASSWORD` | 自定强密码 | 生产必改（各服务默认 `nacos/nacos`）；**改的是服务端账号 + 各业务服务 admin 账号**——`nacos-config/lingmart-common.yaml` 里没有 Nacos 凭据（它本身要靠 Nacos 拉取，不可能承载） |
| `DEV_CODE_ENABLED` | — | 生产**必须为 `false`**（关闭验证码明文返回）。**容器编排默认已是 `false`**、本地 yml 默认 `true`。⚠️ 若你**用容器编排做联调**又没接短信通道，验证码取不到 → 此时显式设 `DEV_CODE_ENABLED=true` 才能从接口响应拿到 `devCode` |
| `NACOS_DISCOVERY_IP` | 可选 | 服务注册到 Nacos 的 IP。本地默认 `127.0.0.1`（已写入各服务 yml 与 `docker-compose.business.yml`）；**容器编排部署必须覆盖为宿主机局域网 IP**，否则容器间 Feign 报「无实例」（与 Seata 无关，Seata 已改直连 TC） |
| `SEATA_REGISTRY_TYPE` / `SEATA_GROUPLIST` | 可选 | Seata 客户端找 TC 的方式。默认 `file` 直连 `127.0.0.1:8091`（宿主机跑服务）；容器编排已由 compose 覆盖为 `seata-server:8091`。**通常无需改动**，详见下方「已知环境风险」第 1 条 |
| `SEATA_IP` | 可选 | 仅当 `SEATA_REGISTRY_TYPE=nacos` 时生效：TC 登记到 Nacos 的 IP，**必须填真实 IPv4**（域名无效），否则宿主机服务连不上 TC |

> `.env` 已被 `.gitignore` 忽略，不会提交。`.env.example` 是模板，不要写真实密钥进模板。
>
> 📌 **`.env` 对谁生效**（v0.11 起）：
> - `docker compose` —— 一直读 `.env`（compose 自身行为）；
> - **本地 `java -jar` / IDEA 启动** —— 也读项目根 `.env`（`lingmart-common` 的 `DotenvEnvironmentPostProcessor`，**9 个服务共用**，向上查找 `.env`）；
> - 优先级：**真实系统环境变量 > `.env` > `application.yml` 默认值**。也就是说 `.env` 里写的键会覆盖 yml 默认值，对 9 个服务一视同仁，不要往 `.env` 放「只想影响单个服务」的键；
> - 排查：`.env` 是否真的被加载，看 `/actuator/env` 里有没有名为 `dotenv-file-<路径>` 的属性源。

### 3. Docker 镜像加速器绕过（仅首次/镜像缺失时）
本机阿里云加速器已失效（403）。先执行 `scripts/pull-images.bat`，从可用源 `docker.1panel.live` 拉取并重打标准标签，之后 `docker compose up` 直接使用本地镜像、不再联网。

---

## 二、首次 / 干净部署（从零到可联调）

按顺序执行，每步完成后再进下一步：

```bat
:: ① 拉取中间件镜像（加速器失效时）
scripts\pull-images.bat

:: ② 拉起基础设施（Nacos + MySQL + Redis + ES + RabbitMQ + Seata + Zipkin + ChromaDB）
::    MySQL 首次启动自动执行 sql/ 建库建表；Ollama 需 --profile ai 才启动
scripts\start-infra.bat
:: 等价命令：docker compose up -d
```

```bash
# ③ 推送 Nacos 共享配置 + Sentinel 规则（先推配置再起业务服务！）
scripts/push-nacos-config.sh
```

```bat
:: ④ 编译打包（需 JDK 21）
scripts\build.bat
:: 等价命令：JAVA_HOME=<JDK21路径> mvn -DskipTests clean package
```

```bat
:: ⑤ 启动全部业务服务（各开独立窗口）
scripts\start-services.bat
```

```bash
# ⑥ 一键体检：基础设施 + 8 个核心服务健康状态
bash scripts/verify-health.sh
```

**关键顺序**：**镜像 → 基础设施 → 推 Nacos 配置 → 打包 → 起服务 → 体检**。配置必须先进 Nacos，否则服务启动时共享配置缺失、注册中心配置不生效。

### 各步验证
- **② 后**：`docker compose ps` 应全部 `Up`；Nacos 控制台可登录；MySQL `root/root@3307` 可连；ES `http://localhost:9201` 返回 JSON。
- **③ 后**：Nacos 控制台「配置管理」应看到 `lingmart-common.yaml` + `sentinel-*.json`（DEFAULT_GROUP）。
- **⑥ 后**：`verify-health.sh` 汇总行显示 `失败 0`（Zipkin 现在也会被正常检查；早期版本的「跳过 1」不会出现）。

---

## 三、数据库迁移（对「改动前已初始化」的库增量补列/补表）

### 何时需要
- MySQL 是**首次建库**（`sql/` 初始化脚本）→ 已包含最新结构，**无需**迁移脚本。
- MySQL 是**旧库**（曾在早期版本初始化过）→ 需跑增量迁移脚本补列/补表。

> 💡 **先跑 `bash scripts/verify-health.sh`**：v0.12 起它内置「库结构」一项，会直接列出缺失的表/唯一键
> （`order_paid_event` / `stock_flow_log` / `es_write_retry` / `sale_rollback_log` / `shop.uk_user_id` / `cart.uk_buyer_sku`）。
> 典型症状：库是早期版本初始化的 → 起 order 服务后报 `Table 'lingmart_order.order_paid_event' doesn't exist`
> （MySQL 容器的 `docker-entrypoint-initdb.d` **只在数据目录为空时执行一次**，老库不会自动补）。

### 判断是否已迁移
```bash
docker exec lingmart-mysql mysql -uroot -proot -e "
SHOW COLUMNS FROM lingmart_product.spu   LIKE 'min_price';
SHOW COLUMNS FROM lingmart_product.sku   LIKE 'stock';
SHOW COLUMNS FROM lingmart_ware.ware_sku LIKE 'unlimited';
SHOW COLUMNS FROM lingmart_order.\`order\` LIKE 'refund_status';
SHOW TABLES FROM lingmart_order  LIKE 'order_paid_event';
SHOW TABLES FROM lingmart_ware   LIKE 'stock_flow_log';
SHOW TABLES FROM lingmart_product LIKE 'sale_rollback_log';"
```
七项**全部有输出** = 已迁移；缺哪项就跑对应的脚本。

### 迁移脚本清单（`sql/upgrade/`，按日期命名）
| 脚本 | 内容 | 起于版本 |
| --- | --- | --- |
| `2026-09-03-stock-unlimited.sql` | `sku.stock` 改可空 + `ware_sku.unlimited` 列 | v0.9 |
| `2026-09-06-order-paid-outbox.sql` | `order_paid_event` 表（支付成功 Outbox） | v0.6 |
| `2026-09-06-snowflake-id.sql` | 业务表主键去掉 `AUTO_INCREMENT`（改雪花 ID） | v0.6 |
| `2026-09-06-stock-flow-log.sql` | `stock_flow_log` 表（库存扣减流水/补偿） | v0.6 |
| `2026-09-20-sale-rollback-log.sql` | `sale_rollback_log` 表（退款回退销量幂等） | v0.10 |
| `2026-09-20-integrity-and-indexes.sql` | **三段式**：唯一键（`shop.uk_user_id`、`cart.uk_buyer_sku`）+ 4 条索引 + Outbox 注释。**先跑第一段自检** | v0.11 |
| `scripts/migrate-v0506.sql` | 历史脚本：退款四列 + `spu.min_price` | v0.5 |

**推荐：一键按序执行全部迁移**（幂等，「已存在」类报错会被识别为跳过）
```bash
bash scripts/migrate.sh          # Git Bash（需 docker 客户端能连上守护进程，脚本会先自查）
```
```bat
cmd /c scripts\migrate.bat       # cmd（纯 ASCII 脚本，从 cmd 跑最可靠）
```

> ⚠️ **两个 Windows 专属坑（都踩过了）**：
> 1. **`.bat` 不能含中文**：cmd 按系统 ANSI 代码页（中文 Windows 是 GBK）解析 `.bat`，UTF-8 的中文会
>    把后面的 ASCII 字符吃掉，症状是满屏 `'xxx' 不是内部或外部命令` 加乱码。
>    因此 `scripts/migrate.bat` **刻意写成纯 ASCII**（说明放在本文档里，Markdown 由编辑器按 UTF-8 读）。
> 2. **Git Bash 里的 `docker` 可能是个空壳（WindowsApps 应用执行别名）**：`C:\Users\<你>\AppData\Local\
>    Microsoft\WindowsApps\` 下有一批与真程序同名的 0 字节占位程序（`docker`、`python` 等都在里面），
>    被 Git Bash 这类非交互 shell 调用时**静默退出**（非零码 + 空输出）。症状很有辨识度：凡是用
>    `docker exec` 的检查全 ✗、而 `curl` 访问发布端口的检查全 ✓（`verify-health.sh` 里
>    MySQL/Redis/Seata/库结构 ✗、Nacos/ES/RabbitMQ/Chroma ✓）。
>    - `verify-health.sh` / `migrate.sh` 现在会先探一次并**打印解析到的 docker 路径**；
>      若识别到 WindowsApps，会**自动尝试** `C:\Program Files\Docker\Docker\resources\bin` 下的真实 CLI。
>    - 若自动修正没命中：**改用 cmd / PowerShell 跑 docker 相关脚本**（cmd 里解析到的是真 CLI）。
>    相关：Git Bash 还会把参数里 `/seata-server/...` 这类**绝对路径改写成 Windows 路径**，
>    故脚本已改用 `docker inspect`/`docker port` 判断 Seata，并把 SQL 改为走 stdin 而非 `-e "长SQL"`。

**手工单条执行**（Git Bash 或 cmd；**不要用 PowerShell 的 `Get-Content | docker exec` 管道**，见下方警告）
```bash
docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 < sql/upgrade/2026-09-06-order-paid-outbox.sql
```

> ⚠️ **PowerShell 管道会把 SQL 里的中文注释转成乱码**，mysql 报
> `ERROR 1064 (42000) ... near '?????,'` —— 这不是脚本有语法错误，而是 `Get-Content` 按系统代码页
> （GBK）重新编码了 UTF-8 内容。三种可用替代：
> ```powershell
> # ① 用 cmd 做重定向（字节直通，最省事）
> cmd /c "docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 < sql\upgrade\2026-09-06-order-paid-outbox.sql"
> # ② 用项目脚本
> cmd /c scripts\migrate.bat
> # ③ 拷进容器再执行（完全绕开宿主编码）
> docker cp sql\upgrade\2026-09-06-order-paid-outbox.sql lingmart-mysql:/tmp/m.sql
> docker exec lingmart-mysql sh -c "mysql -uroot -proot --default-character-set=utf8mb4 < /tmp/m.sql"
> ```

> ⚠️ 迁移脚本**只执行一次**，沿用「只补不重置」约定；命名与约定以 `sql/upgrade/` 为准
> （早期文档写的 `scripts/migrate-v*.sql` 目录/命名已废弃，仅保留历史那一个文件）。
> `2026-09-20-integrity-and-indexes.sql` 是**三段式**：一键脚本会把三段都跑一遍，若第二段因重复数据失败，
> 请按该文件第三段的指引人工处理（`shop` 重复行需人工判断，勿自动删）。

---

## 四、端到端验证路径（冒烟清单）

体检脚本覆盖「服务是否活着」，下面覆盖「业务链路是否通」。建议新环境部署后完整走一遍：

### 4.1 注册 → 登录
1. 打开前端 `http://localhost:5173`（或直连网关）。
2. 注册新账号（开发模式下验证码在响应里明文返回，见下方取码说明）。
3. 登录成功 → 右上角显示昵称/头像。

> 开发模式取验证码：`POST /api/auth/code/send?phone=手机号`，返回 `{ data: { devCode: "123456" } }`
> （字段名是 **`devCode`**；仅 `DEV_CODE_ENABLED=true` 时返回）。

### 4.2 申请开店 → 审核
1. 用户菜单「申请开店」→ 填店铺信息 + 至少 1 个商品（多图上传）。
2. 提交后跳「我的店铺」应显示「审核中」。
3. 管理员登录（**手机号 `13800000000` / 密码 `admin123`**，由 `ADMIN_PHONE`/`ADMIN_PASSWORD` 配置、auth 服务启动时经 `AdminUserInitializer` 自动预置——**`sql/lingmart_auth.sql` 里没有种子数据**）→「管理后台」→「审核中心」→ 通过店铺与商品。
4. 回用户侧「我的店铺」应显示「已开店」；用户菜单出现「卖家中心」。
5. 首页默认显示**店铺卡片**，点击进店看到在售商品。
6. **并发回归**：快速双击「提交申请」→ 只会有一家店铺（`uk_user_id` 兜底），不出现「该用户永久无法开店」的 `TooManyResultsException`。

### 4.3 发布商品 → 搜索（以店铺为入口）
1. 卖家中心发布新商品（含多图，第一张为封面）。
2. 首页搜索框搜商品关键词 → 显示**命中的店铺卡片**（点击进店查看在售商品；商品不直接展示）→ 能搜到且进店能看到该商品（ES 写入成功）。
3. 商品详情页图集完整、SKU 价格正确。

### 4.4 下单 → 支付 → 发货 → 确认 → 退款
1. 买家加购物车 → 结算下单 → 「我的订单」支付（模拟支付）。
   **重复支付回归**：对同一订单再点一次支付 → 应报「订单状态不允许支付」，且商品销量**只加一次**。
2. 卖家中心订单列表发货 → 买家确认收货。
3. 买家申请退款 → 卖家审核通过 → 订单置「已退款」、库存回补、**销量回退（SPU/SKU 一并减回，ES `saleCount` 秒级刷新）**、不计入销售统计；对同一订单再点一次「通过」应报「该订单没有待审核的退款申请」且销量不再变化。
4. 超时未支付订单自动取消：**超时时长默认 2 分钟**（`lingmart.order.timeout-ms: 120000`，演示用，可按需调大）——RabbitMQ 延迟队列 TTL 到期触发 + 每 60s 兜底扫描双保险，`order.timeout.dlq` 有告警日志。

### 4.5 聊天
1. 买家在店铺主页「联系老板」→ 会话建立，历史消息持久化。
2. 卖家在「消息」页可回复；双方实时（WebSocket）；自己发出的消息**立即上屏**（乐观渲染），随后被服务端确认替换。
3. **断线补拉**：停掉 chat 服务 10 秒、期间对方发送若干条 → 恢复后这些消息自动补齐在底部且不重复（重连自动调 `/messages/since`）。
4. **发送失败回执**：把 `CHAT_SEND_MAX_PER_30S` 调小（如 2）后重启 chat，连续发送 → 超出部分显示「发送失败」气泡 + 重发按钮，连接**不再断开**；点「重发」可成功。
5. 深链 `/chat?cid=<真实会话 id>` 能直接打开会话。

### 4.6 网页浮窗 Agent（Copilot，可选组件）
1. **前置（顺序很重要）**：
   - `docker compose up -d`（含 Chroma），并**确认 Chroma 已就绪**：`bash scripts/verify-health.sh` 里 `ChromaDB` 一项为 ✓，
     或直接 `curl -sf http://localhost:18000/api/v2/heartbeat`。
     ⚠️ **agent 启动时必定会向 Chroma 发一次请求**（Spring AI 1.1.8 的 `ChromaVectorStore.afterPropertiesSet`
     会先 `getCollection`，与 `initialize-schema` 开不开无关——已反编译确认），**Chroma 没就绪 agent 就启动失败**。
     容器编排下 `restart: unless-stopped` 会自动重试自愈；`java -jar` / IDEA 启动没有自动重启，请先确认心跳。
   - `.env` 填两把 Key：**`DEEPSEEK_API_KEY`**（对话）+ **`DASHSCOPE_API_KEY`**（知识库向量化，DeepSeek 无 embedding，用百炼 text-embedding-v3）；
   - 再启动 `lingmart-agent`（IDEA .run 或容器）。
   - 知识库现在**首次启动会自动后台重建**（`AGENT_KB_REBUILD_ON_START` 默认 `true`，空库时才跑、失败只告警），
     所以新环境第一次提问就能基于知识库回答；也可手工 `POST /api/agent/admin/kb/rebuild`。
2. 管理员重建知识库：`curl -X POST http://localhost:88/api/agent/admin/kb/rebuild -H "Authorization: Bearer <admin token>"`；
   查看状态用 **GET** `GET /api/agent/admin/kb/status`（确认 `ready=true`；知识种子见 `lingmart-agent/src/main/resources/kb/`，向量集合名 `lingmart_kb`）。
3. 前端打开任意非登录页 → 右下角 🤖 浮窗 → 输入“什么是幂等键？”应得到基于知识库的回答。
4. 输入“我想申请开店”→ 出现「跳转页面 /shop/apply」确认卡片 → 点【确认】→ 自动跳转申请开店页。
5. 全程设计说明见 [docs/agent-copilot.md](agent-copilot.md)。若暂不想起 Chroma，纯问答调试可设 `AI_VECTORSTORE_TYPE=none` + `AGENT_KB_ENABLED=false`。

---

## 五、日常运维

| 操作 | 命令 / 位置 |
| --- | --- |
| 重启单个服务 | 关掉对应窗口 / IDEA Services 重启；或 `docker compose -f docker-compose.yml -f docker-compose.business.yml restart lingmart-xxx` |
| 停止全部业务服务 | `scripts\stop-services.bat` |
| 看服务日志 | 本地启动：`./logs/{服务名}.log`（logback 文件 appender，含 traceId/spanId）；容器：`docker logs -f lingmart-xxx` |
| 看中间件日志 | `docker logs -f lingmart-mysql` / `lingmart-nacos` / `lingmart-rabbitmq` / `lingmart-elasticsearch` |
| 巡检 | `bash scripts/verify-health.sh`（8 个核心服务；agent 为可选项不纳入） |
| **重置环境（危险！）** | `scripts\reset-data.bat` / `scripts\reset-data.sh`：**删除 MySQL 全部业务库 / Redis / Nacos 配置 / Seata 会话 / ES 数据 / 上传图片**，重建后再按「二、首次部署」步骤走。仅当数据脏到无法排障时使用。 |
| 数据备份（建议定期） | MySQL：`docker exec lingmart-mysql mysqldump -uroot -proot --all-databases > backup.sql`；ES：快照或 `curl -X GET 'localhost:9201/_cat/indices?v'` 确认索引存在（业务数据以 MySQL 为准，ES 可重建） |

### 已知环境风险（部署/换机时逐项检查）
0. **JDK 必须是 21**：全部服务必须在 JDK 21 上运行。若用 JDK 26 启动，会出现：product/search 端口占用但 `/actuator/health` 不响应（verify-health 报 FAIL）、Seata/Feign 异常导致**开店申请静默回滚（申请后看不到店铺）**。启动前确认 `JAVA_HOME` / IDEA Project SDK = JDK 21。
0. **Nacos 注册 IP 抖动（169.254.x.x）**：Windows 虚拟网卡会被 Nacos 误识别为注册 IP，导致网关/服务间首连间歇超时（「系统繁忙」偶发）。已强制各服务注册 `127.0.0.1`（`NACOS_DISCOVERY_IP` 可覆盖）；**换机后需重启全部服务让新 IP 生效，旧 169.254 实例自动过期**。
0. **ware 未注册会导致开店/审核回滚**：`product→ware`、`order→ware` Feign 依赖 Nacos 里的 ware 实例。ware 未注册时：申请开店 → product 建商品时调 ware 报「Load balancer does not contain an instance for lingmart-ware」→ Seata 全局回滚 → 店铺表无记录。用 `scripts/verify-health.sh` 全 PASS 后再联调，可避免此类「看不见」问题。
1. **Seata 报 `can not connect to services-server` 的根因与修法**：TC 跑在容器里时，它会把自己注册成 **Docker 内网 IP**（如 `172.18.0.9`），而宿主机上的 order/shop/ware 根本路由不到这个地址。
   **v0.12 起客户端默认改用「直连模式」**（`seata.registry.type=file` + `seata.service.grouplist.default`），完全绕开「TC 注册成什么 IP」这件事：
   - 服务跑宿主机 → `SEATA_GROUPLIST=127.0.0.1:8091`（默认值，走端口映射）；
   - 服务跑容器 → `docker-compose.business.yml` 已覆盖为 `SEATA_GROUPLIST=seata-server:8091`（compose 服务名）。
   验证方式：启动后日志出现 `RM register success`（或不再出现该报错）。仍想用 Nacos 注册方式：设 `SEATA_REGISTRY_TYPE=nacos` 并**同时**把 `SEATA_IP` 设成本机局域网 IP（`ipconfig` 查，换网络要改）——但注意 `SEATA_IP` 只接受真实 IPv4，写域名无效（[issue #3401](https://github.com/apache/incubator-seata/issues/3401)）。
2. **Nacos 默认密码**：`nacos/nacos`，生产必改（改服务端密码 + **各业务服务 admin 账号**，即各 `application.yml` 的 `spring.cloud.nacos.*.username/password`；`nacos-config/lingmart-common.yaml` 里**没有** Nacos 凭据）。
3. **Redis 宿主端口 6380**：脚本与文档若写 6379 均以 6380 为准。
4. **ES 镜像本地构建**：`docker/elasticsearch/Dockerfile` 基于 `elasticsearch:8.15.5` + IK 插件，镜像源失效时需先 `pull-images.bat` 准备基础镜像。
5. **Agent 强依赖 ChromaDB（启动期）**：`ChromaVectorStore` 在 bean 构造期就会 `getCollection` 打一次 Chroma，**Chroma 未就绪 → agent 启动必然失败**（已反编译 Spring AI 1.1.8 确认，与 `CHROMA_INITIALIZE_SCHEMA` 无关）。先跑 `verify-health.sh` 看 `ChromaDB` 是否 ✓。纯聊天调试可设 `AI_VECTORSTORE_TYPE=none` + `AGENT_KB_ENABLED=false` 跳过向量库。LLM 需两把 Key：**`DEEPSEEK_API_KEY`**（对话 deepseek-chat）+ **`DASHSCOPE_API_KEY`**（嵌入 text-embedding-v3，百炼；DeepSeek 无 embedding）。
   ⚠️ 另注：`chroma` 镜像用的是 `chromadb/chroma:latest`（未固定版本）。Spring AI 1.1.8 已改用 `/api/v2` 协议，若将来 Chroma 大版本变更 API 需同步升级 Spring AI 或回退镜像版本。

---

## 六、生产加固清单（上线前逐项打勾）

| # | 事项 | 现状 | 处置 |
| --- | --- | --- | --- |
| 1 | JWT 密钥 | 容器编排已强制 `.env` 提供（未设拒绝启动）；本地 yml 仍有内置开发值 | `.env` 设 `JWT_SECRET`（`openssl rand -hex 32`）；本地若未设，启动日志会出现「检测到 JWT 签名密钥仍为仓库内置的默认开发值…」的 ERROR 告警 |
| 2 | 验证码明文 | 容器编排默认 `false`；本地 yml 默认 `true` | 生产（含容器）确认 `DEV_CODE_ENABLED=false` |
| 3 | Nacos 密码 | 默认 `nacos/nacos` | 改服务端密码 + 各服务 `spring.cloud.nacos.*` 账号（`lingmart-common.yaml` 不含凭据） |
| 4 | HTTPS | 未启用 | 网关前置 Nginx / 云 LB 终结 TLS |
| 5 | SQL 日志 | 共享配置 `lingmart-common.yaml` 里 `log-impl: StdOutImpl` | 生产覆盖为空关闭 SQL 日志；日志脱敏手机号 |
| 6 | `/internal` 接口 | 仅靠「网关不路由 `/api/**` 之外路径 + 内网」的约定，**无共享密钥鉴权** | 对 `/internal/**` 做网络隔离（仅内网可达），或加服务间签名（见待办） |
| 7 | 数据备份 | 无 | MySQL 定时 dump + 恢复演练；ES 索引可重建 |
| 8 | 上传目录 | 本地磁盘 + shop-uploads 卷 | 挂独立数据卷，配置防盗链；图片内容安全检测可选 |
| 9 | 告警 | 仅 `/actuator/prometheus` 暴露 | 接 Prometheus + Alertmanager（QPS/延迟/错误率） |
| 10 | 链路追踪 | ✅ 已启用：compose 内置 zipkin 随 `docker compose up -d` 启动，`ZIPKIN_ENABLED` 默认 true | UI `http://localhost:9411`；不想上报设 `ZIPKIN_ENABLED=false` 并推送共享配置 |
| 11 | 依赖漏洞 | 未扫描 | 接入 OWASP Dependency-Check / Dependabot |
| 12 | RabbitMQ 账号 | `guest/guest`（容器默认，`docker-compose.business.yml` 可用 `RABBITMQ_USERNAME/PASSWORD` 覆盖） | 生产换强口令并同步业务服务配置 |
| 13 | 定时任务多实例 | 5 个 `@Scheduled` 无分布式锁（当前单实例部署） | 水平扩容前加任务租约/分布式锁（见待办） |

---

## 七、故障排查速查表

| 症状 | 最可能原因 | 处置 |
| --- | --- | --- |
| 登录/接口 503 | 对应服务没启动（网关转发失败） | `verify-health.sh` 定位；启动缺失服务 |
| 前端「系统繁忙」+ 后端 500 | 该服务依赖的中间件未起 / DB 缺列 | 查 `./logs/{服务}.log`；跑 `verify-health.sh`；执行 §三 迁移 |
| 商品分类/详情 500 | Redis 缓存序列化（旧镜像缺 JavaTimeModule） | **重新打包** + 重启 product（v0.6 已修复） |
| 上传图片失败 | 路径为相对路径 / 磁盘无写权限 | v0.6 已改绝对路径 + `Files.copy`；重启 shop 生效 |
| 订单超时未自动取消 | RabbitMQ 队列缺/消费失败 | 看 `order.log` 与 `order.timeout.dlq` 告警日志；兜底扫描任务仍在 |
| 应用启动失败「PRECONDITION_FAILED」 | 旧队列带死信参数 | v0.6 已改为消费侧重发死信（`RepublishMessageRecoverer`），**无需删队列**；确认 `RabbitMqConfig` 为新版 |
| 验证码登录总是失败 | Redis 未起 / `REDIS_PORT` 错 | `docker compose ps` 确认 Redis；`REDIS_PORT=6380` |
| 申请开店后看不到店铺 / 审核不通 | **`R.checkOk` Integer 比较 bug（已修复，重新打包生效）** / ware 未注册 Nacos / 服务跑在 JDK26 | 看 `shop.log` 有 `will be rollback` + `code=60000,msg=操作成功`；`verify-health.sh` 全 PASS + 用 JDK 21 重新打包重启后重试 |
| 图片裂图（灰色「暂无图片」） | dev 下 `/uploads` 未走代理 | `vite.config.js` 已配 `/uploads → localhost:88` 代理，重启 Vite 生效 |
| 服务起来立刻退出 | Nacos 不可达 / Nacos 密码错 / JDK 版本不对 | 看启动日志首 30 行；`scripts/verify-health.sh`；确认 JDK 21 |
| `Table 'xxx' doesn't exist` | 存量库缺表：initdb 只在空数据目录跑一次 | `bash scripts/verify-health.sh` 看「库结构」→ 跑 §三 迁移（`scripts/migrate.sh`） |
| `ERROR 1064 ... near '?????,'` | PowerShell 的 `Get-Content \| docker exec` 把 UTF-8 中文转成了乱码 | 改用 `cmd /c "docker exec ... < 文件"`、`scripts/migrate.bat`，或 `docker cp` 进容器执行 |
| `.bat` 满屏「不是内部或外部命令」+ 乱码 | `.bat` 里含中文（cmd 按 GBK 解析 UTF-8 文件） | 用纯 ASCII 的 `scripts/migrate.bat`；自己写脚本时不要放中文 |
| `verify-health.sh` 里 MySQL/Redis/Seata 报错，但服务其实是好的 | Git Bash 的 PATH 里 `docker` 是 WindowsApps 占位程序（静默退出）/ 连不上守护进程 | 脚本会打印解析到的 docker 路径并尝试自动修正；仍不行就用 cmd/PowerShell 跑 docker 相关脚本 |
| `mvn test` **运行期**报 `java.lang.Error: Unresolved compilation problem: The constructor XxxServiceImpl(...) is undefined` | 被测类用 Lombok `@RequiredArgsConstructor`（构造参数 = 字段声明顺序），新增依赖字段后测试类没同步。注意：报在**运行期**而不是构建期，是因为 `target/test-classes` 里躺着 IDE(ecj) 编译出的「运行期必抛错的桩类」，Maven 认为 `all classes are up to date` 就跳过了重编译 | 补 `@Mock` 与构造参数（**顺序与字段声明一致**），然后 `mvn clean test`（不清 `target` 可能又跳过重编译）。另注意它会让 reactor 跳过后续模块——看着像"只有 X 模块有问题"，实际后面全没跑 |
| `mvn test` 输出中文乱码（如 `鏍￠獙搴楅摵...`） | 控制台代码页 936(GBK)，而 JVM 日志按 UTF-8 输出 | 跑之前先 `chcp 65001`（cmd/PowerShell 同一窗口生效）；IDEA 用默认 UTF-8 控制台即可。**不要**指望在 POM 里加编码参数修——那只影响 fork 出的测试 JVM，javac 的提示跟随控制台代码页 |
| `Mockito is currently self-attaching ...` 警告 | JDK 21+ 下 Mockito 5.x 自附加 agent | v0.13 起父 POM 已用 `-javaagent` 加载 mockito-core 消除；若仍出现，检查 `mockito.version` 是否与 `spring-boot-dependencies` 管理的一致（jar 路径由它拼接） |
