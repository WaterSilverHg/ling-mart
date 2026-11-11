# 轻营 · 性能测试使用说明（k6）

> 最近一次实测结果与未覆盖范围见 [load-test-report-2026-09-21.md](load-test-report-2026-09-21.md)
> （24/24 断言通过、0 真实失败、296 次限流；含压测后数据校验 SQL 与后续建议）。

> **压测脚本**：`scripts/load-test/lingmart-core.k6.js`（唯一一份）。
> 早期还有一份 JMeter `.jmx`，因其下单 sampler 请求体为空、参数从未被引用（压不出它声称要压的链路），
> 属误导性残留，v0.11 已删除，统一到 k6 维护。
>
> **数据播种**（v0.14 新增）：`scripts/load-test/seed/`，见第三节。

---

## 一、前置条件

1. **环境已就绪**：`bash scripts/verify-health.sh` 全 PASS（服务与中间件都活着）。
2. **k6 已安装**：`k6 version`（未装见 <https://grafana.com/docs/k6/latest/set-up/install-k6/>）。
3. **已播种压测数据**：见第三节。**没播种就不要跑**——脚本 `setup()` 会先做登录与搜索探针，
   任一失败直接中止并说明原因；旧版的行为是"登录失败就静默跳过"，最后报告全绿却什么都没压。

---

## 二、交付物

| 文件 | 作用 |
| --- | --- |
| `lingmart-core.k6.js` | 压测脚本本体（读 + 购物车 + 下单 + 支付四个场景） |
| `seed/seed-loadtest.sql` | 播种：40 个账号（20 买家 / 20 卖家）+ **20 条买家默认收货地址** + 20 店铺 + 500 SPU + 1500 SKU + 库存行 |
| `seed/seed-loadtest.bat` / `.sh` | 播种包装：执行 SQL → 调 `post-seed.mjs` 写 ES |
| `seed/post-seed.mjs` | 逐店重建 ES 索引 + 搜索冒烟；`--clean` 则反过来清理 ES |
| `seed/teardown-loadtest.sql` | 清理 DB 侧（按固定 ID 区间，不碰真实数据） |
| `seed/teardown-loadtest.bat` / `.sh` | 清理包装：执行 SQL → `post-seed.mjs --clean` |

---

## 三、数据准备

```cmd
:: Windows（cmd 下跑；PowerShell 的管道会把 SQL 里的中文重编码成乱码）
cmd /c scripts\load-test\seed\seed-loadtest.bat
```
```bash
# Git Bash / Linux
bash scripts/load-test/seed/seed-loadtest.sh
```

### 3.1 播了什么

| 实体 | 数量 | 说明 |
| --- | --- | --- |
| 买家账号 | 20 | `ROLE_USER`，手机号 `13900000001..020`，k6 用这些 |
| 卖家账号 | 20 | `ROLE_SELLER`，手机号 `13800000001..020`，即下面 20 家店的店主 |
| 店铺 | 20 | `压测店铺01..20`，直接置「营业中 + 审核通过」 |
| SPU | 500 | 每店 25 个，标题形如「智能手机 九成新 小米 3店第7款」 |
| SKU | 1500 | 每 SPU 3 个（标准版 / 大容量版 / 礼盒装） |
| SKU 图片 | 3000 | 复用仓库里真实存在的 `/uploads/**` 图片，前端能显示真图 |
| 库存行 | 1500 | 与 SKU 一一对应 |

**标题为什么这么造**：品类（手机/耳机/键盘/背包/台灯/保温杯/手表/音箱）、成色（全新/九成新/准新/闲置）、
品牌（小米/华为/苹果/索尼/自用）三类词交叉 —— 关键词检索能真的走到 IK 分词，而不是只测 `match_all`。

**库存策略**：每店第 1 个商品的 SKU 设为**不限量**（`sku.stock=NULL` + `ware_sku.unlimited=1`，共 60 个），
其余给 100000。这样写链路可以长时间反复压而不打空库存，同时 v0.9 的"不限量"分支也被覆盖到。

### 3.2 账号密码 = 管理员密码

密码是 BCrypt，**纯 SQL 造不出哈希**。播种脚本的做法是**复制管理员那行的哈希**，
所以所有压测账号的密码都是**管理员密码**（默认 `admin123`，见 `ADMIN_PASSWORD`）。

- 改过 `ADMIN_PASSWORD` → 跑 k6 时用 `-e PASSWORD=<新密码>`；
- 管理员账号由 `lingmart-auth` 启动时的 `AdminUserInitializer` 保证存在；没启动过 auth 的话，
  播种脚本会直接报 `SEED ABORTED: admin user not found` 并中止（不会插入一批登录不了的账号）。
- **管理员本身不参与压测**：它是 `ROLE_ADMIN`，语义上只能管理、不能下单，
  所以买家另造了 20 个 `ROLE_USER`。管理员只在 `post-seed.mjs` 里用来调重建索引入口。

### 3.2.1 收货地址（v0.39 起必须有）

下单现在会解析收货地址：`addressId` 不传时取默认地址，一条都没有则**拒绝下单**
（`请先添加收货地址`）。因此播种脚本已为 20 个买家各插一条默认地址，
`order` 场景的请求体**不需要改**（沿用原协议即可）。

> 若你用的是**升级前**的旧库且没跑 `sql/upgrade/2026-11-10-address-and-receiver.sql`，
> 播种会因 `user_address` 表不存在而失败——请先执行迁移。

### 3.3 商品要能被搜到，必须进 ES

SQL 插库**不会**进 ES。两条路，`seed-loadtest.bat` 会自动走第一条：

1. **立刻生效**：`post-seed.mjs` 逐店调 `POST /api/admin/product/shop/{shopId}/reindex-es`（20 次）。
2. **兜底**：什么都不做，等 `reconcileEsIndex` 定时任务（`lingmart.es.reconcile-ms` 默认 **5 分钟**）
   自动补上缺失的 ES 文档。注意它**只补不删**。

> ⚠️ **第 1 步需要服务在运行**（网关 + product 至少要活着），因为它走的是管理端 HTTP 接口。
> 播种的第一步只连 MySQL 容器，所以"SQL 成功、第二步失败"是正常组合——那说明数据已经进库了，
> 只是还没进 ES。此时别重跑播种，直接启动服务后跑 `node scripts/load-test/seed/post-seed.mjs`，
> 或者什么都不做等 5 分钟。
>
> `post-seed.mjs` 的 `BASE_URL` 默认写 **`http://127.0.0.1:88/api`**（不是 `localhost`）：
> Node 18+ 对 `localhost` 可能先解析到 IPv6 `::1`，而 Docker Desktop 的端口映射只监听 IPv4，
> 于是只报一句毫无线索的 `fetch failed`。k6 不受影响（Go 会做 happy-eyeballs），
> 所以两边的默认值看着不一致是有意的。

### 3.4 幂等与清理

- 播种脚本开头会按**固定 ID 区间**删除旧数据再插入，可反复重跑：
  买家 `9100001~9100020`、卖家 `9200001~9200020`、店铺 `9300001~9300020`、
  SPU `9400001~9400500`、SKU `9500001~9501500`、图片 `9600001~9603000`、库存 `9700001~9701500`。
  这些区间刻意避开雪花 ID，**不会碰到真实数据**。
- 清理：
  ```cmd
  cmd /c scripts\load-test\seed\teardown-loadtest.bat
  ```
  ⚠️ SQL 删库**不会**带走 ES 文档（对账任务只补不删），残留文档会让已删商品仍被搜到（点进去 404），
  所以 teardown 的第二步必须跑 `post-seed.mjs --clean`（等价于 ES 的 `_delete_by_query`，按 `shopId` 区间删）。

---

## 四、运行

```bash
# 默认：梯度 20 → 50 → 0，共 50 秒
k6 run scripts/load-test/lingmart-core.k6.js

# 恒定并发（对比调优前后请用这个）：30 VU 跑 60 秒
k6 run -e VUS=30 -e DURATION=60s scripts/load-test/lingmart-core.k6.js

# 只跑只读链路（不产生任何数据）
k6 run -e ENABLE_WRITE=0 scripts/load-test/lingmart-core.k6.js
```

### 环境变量一览

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:88/api` | 网关地址（压测请直连网关，不要打单个服务） |
| `PASSWORD` | `admin123` | 压测账号密码 = 管理员密码（见 3.2） |
| `BUYER_COUNT` | `20` | `setup()` 登录几个买家账号（多账号轮换，避免单账号热点） |
| `BUYER_PHONE_PREFIX` | `139` | 买家手机号前缀，后 8 位是账号序号 |
| `KEYWORDS` | `手机,小米,耳机,九成新,键盘` | 检索关键词池（逗号分隔，随机取） |
| `ENABLE_WRITE` | `1` | 置 `0` 只跑只读链路 |
| `W_CART` / `W_ORDER` / `W_PAY` | `15` / `15` / `10` | 写场景权重（百分比），读场景吃剩下的 |
| `VUS` | 空 | 传了走**恒定并发**模式（配合 `DURATION`）；不传走默认梯度 |
| `DURATION` | `60s` | 恒定并发模式的持续时间 |
| `P95_MS` | `800` | 成功请求的 p95 门槛（毫秒；**不含 429**，见第六节） |
| `FAIL_RATE` | `0.05` | 失败率门槛（0.05 = 5%） |
| `SLEEP` | `1` | 每次迭代后的思考时间（秒） |

---

## 五、压测链路

`setup()` 只做一次：登录 20 个买家（任一失败即告警，**全失败则中止**）+ 搜索探针（无数据则中止）。

每次迭代按权重随机挑一个场景，每个请求都带 `flow` 标签（见第六节）：

| 场景 | 步骤 | 是否留数据 |
| --- | --- | --- |
| `read` | 商品检索（随机 `sort`）→ 检索店铺 → 店铺主页 → 商品详情 | 否 |
| `cart` | 上述浏览 → 加购 → 改数量 → 列表校验 → **删除** | 否（闭环） |
| `order` | 上述浏览 → 下单 → **取消** | 否（闭环） |
| `pay` | 上述浏览 → 下单 → 支付 → 查订单详情校验 `payStatus` | **是** |

**关于 `pay` 场景的残留**：已支付的订单不能再取消（业务上就该如此），所以每条 `pay` 迭代会留一行
"已支付"订单。它**不影响库存**（优先挑不限量 SKU，ware 的锁定/扣减/回补对它全部跳过），
压测后跑 teardown 清理即可。这一点如实写出，而不是假装"零残留"。

---

## 六、怎么读报告

断言不只校验协议层，还校验数据（`detail` 返回的 `spuId` 与请求一致、`skus` 非空、价格为正、
支付后 `payStatus=1`、购物车改数量后 `num` 真的变了），所以「绿」代表链路真的通。

**分链路 p95 只看 `http_ok_duration`**：每个请求都带 `tags: {flow: 'read'|'cart'|'order'|'pay'}`，
脚本给这些子指标都定义了门槛（否则 k6 不会在汇总里打印它们），所以结束时会看到：

```
http_ok_duration{flow:read}........: avg=... p(95)=... p(99)=...
http_ok_duration{flow:cart}........: ...
http_ok_duration{flow:order}.......: ...
http_ok_duration{flow:pay}.........: ...
http_req_failed{flow:read}.........: 0.00%
http_ok_requests{flow:read}........: ...
flow_iterations{flow:read}.........: ...
...
```

默认门槛：`read<500ms`；`cart/order/pay<1500ms`；`checks>95%`。

> ⚠️ **不要用 `http_req_duration` 的 p95 下结论**。它包含 429（被限流的请求通常几十毫秒就返回），
> 会把分布整体拉低，于是出现"并发越高、p95 越低"的假象。而且 429 被 `http.expectedStatuses`
> 标成了预期响应，连 `http_req_duration{expected_response:true}` 也排除不掉它。
> 所以延迟一律看 `http_ok_duration`——只含 **HTTP 2xx 且业务 code=200** 的请求。

**先看这四个数：**

| 指标 | 含义 | 怎么用 |
| --- | --- | --- |
| `http_ok_requests` 的 rate | **有效吞吐**（成功请求/秒，不含 429） | 判断"系统真正服务了多少"，而不是发了多少 |
| `http_429_throttled` 的 rate | 被限流的请求/秒 | 与有效吞吐相加 = 总请求速率 |
| 限流占比 = 429 ÷ (有效 + 429) | 多少请求被主动泄流 | 占比高说明**撞到配置上限**，不是系统扛不住 |
| `http_ok_duration{flow}` 的 p95/p99 | 成功请求的延迟 | 报性能只报这个 |

**业务失败按原因看 `business_errors`**：HTTP 200 但 `code≠200` 的响应会被归类成
`{flow, code, reason}`，`reason` 是归一化标签：`stock_shortage` / `self_purchase` /
`multi_shop` / `sku_missing` / `invalid_state` / `idempotency` / `throttled` / `timeout` / `other`。
"100 VU 下单失败 8 个"到底是哪 8 个，看这里。

**每个 iteration 发了几个请求**：`http_ok_requests{flow} ÷ flow_iterations{flow}`。
四类场景分别是 3 / 8 / 5 / 6 个请求，所以 **VU 数 ≠ 请求速率**。

**限流（429）本身的口径**：它被**排除在 `http_req_failed` 与所有断言之外**，
理由是 429 是系统按配置主动泄流、请求根本没被服务——把它算进业务失败率，门槛就变成
"你给搜索配了多少 QPS"，换台机器/改个阈值就红，毫无信号价值。真实的失败（500/503/超时/断连）
照常计入。想让 429 也计入失败：`-e COUNT_429_AS_FAILURE=1`。

**想真正压搜索：先把限流阈值抬上去**

搜索链路**三处**都配了 50 QPS（都是有意调低的，注释写着"检索较贵"）：

| 文件 | 规则 | 作用点 |
| --- | --- | --- |
| `nacos-config/sentinel-gateway.json` | `search-route` = 50 | 网关路由级，**客户端看到的 429 就是它** |
| `nacos-config/sentinel-search.json` | `searchProduct` = 50 | search 服务 `@SentinelResource` |
| `nacos-config/sentinel-search.json` | `/api/search` = 50 | search 服务 URL 级 |

算一下为什么默认压测必然撞到它：每轮迭代约 **5** 个请求、其中约 **1.6** 个打搜索 ——
50 VU 时总吞吐约 250 req/s、搜索约 80 req/s，**超过 50**，于是约三成搜索请求被拒
（首轮实测 15.38% 的失败全部来自这两个搜索接口）。

所以要真正压搜索，按需把三处 `count` 调大（例如 300）后：

```bash
bash scripts/push-nacos-config.sh    # 改完必须推送，网关与服务会重新加载规则
```

⚠️ 环境变量 `SENTINEL_*_QPS`（`lingmart-gateway/application.yml` 里那些）
**在正常部署下不生效**——它们是本地兜底默认值，Nacos 有规则时会被覆盖。要改就改 Nacos 里的规则文件。

只想跑一次"全绿"的回归、不想动配置：把并发压到限流之下即可，例如
`k6 run -e VUS=15 -e DURATION=60s scripts/load-test/lingmart-core.k6.js`
（15 VU ≈ 75 req/s 总吞吐、搜索约 24 req/s < 50）。

**重点观察支付/下单链路**：Seata 全局事务的全局锁会让同一 SKU 串行化，吞吐不会随 VU 线性增长
（TC 与 MySQL 行锁是瓶颈）；此时看 p95/p99 与 TC 日志，而不是单看 RPS。

---

## 七、两个容易踩的坑（脚本已处理，但你排查时需要知道）

1. **id 在 JSON 里是字符串**。全局 Jackson 把装箱 `Long` 序列化成字符串（`LongJsonAutoConfiguration`），
   因为雪花 id 有 19 位、超过 JS 的 `2^53`。所以脚本里 `skuId`/`spuId`/`shopId` **原样透传字符串**，
   比较时用 `String(...)` 归一化 —— 写成 `Number(skuId)` 会**静默丢精度**（旧脚本正是这么写的）。
   注意 `orderStatus`/`payStatus` 是 `Integer`，仍是数字，可以直接 `=== 1`。
2. **登录限流是无条件计数的**。`UserServiceImpl.login` 在比对密码**之前**就 `hit("login-fail:"+phone)`，
   阈值 5 → **同一手机号第 5 次登录即使密码正确也会被拒**（成功才 `reset`计数）。
   这正是脚本只在 `setup()` 里登录一次的原因：逐轮登录会自己把自己限流掉。
   被限流后要等 5 分钟，或删掉 Redis 键 `lingmart:ratelimit:login-fail:{手机号}`。

---

## 八、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `setup` 报"全部登录失败" | 没播种，或密码不是管理员密码 | 跑 `seed-loadtest.bat`；改过 `ADMIN_PASSWORD` 就用 `-e PASSWORD=` 传入 |
| `setup` 报"搜索命中 0 家店铺" | 数据没进 ES | 跑 `node scripts/load-test/seed/post-seed.mjs`（需要服务在运行），或等最多 5 分钟（`reconcileEsIndex`） |
| `post-seed.mjs` 报 `ERROR: fetch failed` | 网关没起（该步走管理端 HTTP 接口）。Node 对 `localhost` 可能先试 IPv6 `::1` 而 Docker 只映射 IPv4 | 先启动服务再重跑；脚本默认已用 `127.0.0.1`，也可 `-e BASE_URL=` 覆盖。**播种的数据已在 MySQL 里，不用重播** |
| 大量 429（看 `http_429_throttled`） | Sentinel 限流：搜索链路**三处**都配了 50 QPS，**属预期保护不是性能问题** | 见下方"想真正压搜索"一节 |
| 下单报库存不足 | 选到了库存已被打空的有限量 SKU | `pay` 场景会优先挑不限量 SKU；若仍出现，重跑播种脚本重置库存 |
| 中文关键词 400 | 请求行含裸中文 | 脚本已 `encodeURIComponent`，不要改回裸中文 |
| 搜索 `total` 与商品数对不上 | `total` 是**聚合出的店铺数**，不是文档数；`size` 也会被夹到 1..100 | 属正常语义，别按"文档数"写断言 |
| 报告里 `checks` 门槛没过但接口都 200 | 数据类断言失败（如详情缺 `skus`、支付后状态没流转） | 看具体是哪条 check 飘红，那是真问题，不是门槛太严 |

---

## 九、与 CI 的关系

k6 回归尚未接入流水线（见 [todo-and-optimization.md](todo-and-optimization.md)）。
届时建议：单独的环境 + 固定的 `VUS`/`DURATION`/`P95_MS` + **先播种再压**（本节的脚本可直接复用），
否则压测结果不可比。数据准备曾经是这块最大的缺口 —— v0.14 起有了播种与清理脚本。
