// =============================================================
// 轻营（LingMart）核心场景压测脚本（k6）
//
// 覆盖：
//   read  —— 商品检索（含价格/销量排序）+ 检索店铺 + 店铺主页 + 商品详情
//   cart  —— 上述浏览 + 加购 → 改数量 → 列表校验 → 删除（闭环，无残留）
//   order —— 上述浏览 + 下单 → 取消（闭环，无残留）
//   pay   —— 上述浏览 + 下单 → 支付 → 查详情校验 payStatus（**会留下已支付订单**，见下）
//
// 前置：先播种数据，否则 setup() 会直接失败（不会静默空跑）
//   cmd /c scripts\load-test\seed\seed-loadtest.bat
//   bash scripts/load-test/seed/seed-loadtest.sh
//
// 用法：
//   k6 run scripts/load-test/lingmart-core.k6.js                    # 默认梯度 20→50→0
//   k6 run -e VUS=30 -e DURATION=60s scripts/load-test/lingmart-core.k6.js   # 恒定并发
//   k6 run -e ENABLE_WRITE=0 scripts/load-test/lingmart-core.k6.js  # 只跑只读链路
//   k6 run -e P95_MS=1500 scripts/load-test/lingmart-core.k6.js     # 放宽 p95 门槛
//
// 环境变量：BASE_URL / PASSWORD / BUYER_COUNT / BUYER_PHONE_PREFIX / KEYWORDS /
//           ENABLE_WRITE / VUS / DURATION / P95_MS / FAIL_RATE / COUNT_429_AS_FAILURE /
//           W_CART / W_ORDER / W_PAY / SLEEP
//
// 【压测账号的密码】播种脚本造账号时是**复制管理员那行的 BCrypt 哈希**（纯 SQL 造不出哈希），
//   所以压测账号密码 = 管理员密码，默认 admin123。改过 ADMIN_PASSWORD 就要跟着改这里。
//
// 【为什么登录只在 setup() 里做一次】auth 的登录限流是**无条件计数**的：
//   UserServiceImpl.login 在比对密码**之前**就 hit("login-fail:"+phone)，阈值 5，
//   也就是说同一手机号第 5 次登录**即使密码正确也会被拒**（成功才 reset 计数）。
//   逐轮登录不仅会把压测时间浪费在 BCrypt 上，还会自己把自己限流掉。
//
// 【id 一律按字符串处理】全局 Jackson 把装箱 Long 序列化成字符串（雪花 id 有 19 位，
//   超过 2^53，JS 里 Number() 会**静默丢精度**）。所以 skuId/spuId/shopId 原样透传，
//   比较时用 String(...) 归一化。orderStatus/payStatus 是 Integer，仍是数字。
//
// 【搜索路由有 Sentinel 限流，默认 50 QPS】三处都写着 50：
//   nacos-config/sentinel-gateway.json 的 search-route（网关路由级，客户端看到的 429 来自这里）
//   nacos-config/sentinel-search.json 的 searchProduct 与 /api/search（服务级）
//   算一下就知道默认压测必然撞到它：每轮约 5 个请求、其中约 1.6 个打搜索，
//   50 VU 时总吞吐约 250 req/s、搜索约 80 req/s > 50 → 约三成搜索请求被拒。
//   本脚本对 429 的处置：**单独计数、不做断言、不计入 http_req_failed**（理由见下面 throttled429
//   与 assert() 的注释），所以门槛颜色反映的是"服务稳不稳"，而不是"你配了多少 QPS"。
//   想让它计入失败：-e COUNT_429_AS_FAILURE=1。想真正压搜索：把上面三处 count 调大并
//   scripts/push-nacos-config.sh 推送（见 docs/load-testing.md 第八节）。
//
// 【报告口径：先看哪几个数】（v0.36 起）
//   http_ok_requests...........有效吞吐。rate 即"每秒真正被服务成功的请求数"，**不含 429**
//   http_429_throttled.........被限流的请求量；限流占比 = 429 / (有效 + 429)
//   http_ok_duration{flow}.....成功请求的延迟分布——**报 p95 只看这个**
//   http_429_duration{flow}....429 的延迟分布（通常极低）——用它解释"为什么 p95 反而下降"
//   business_errors{flow,reason}...业务失败（HTTP 200 但 code≠200）按原因归类
//   http_ok_requests{flow} ÷ flow_iterations{flow} = 每个 iteration 实际发了几个请求
//   ⚠️ http_req_duration 仍然包含 429（且 429 被标记为 expected response），
//      不要拿它的 p95 下结论——那正是"并发越高越快"假象的来源。
//
// 【pay 场景会留下已支付订单】已支付的订单不能再取消（业务上就该如此），所以每条
//   pay 迭代会留一行"已完成"数据。它**不影响库存**（优先选不限量 SKU，库存数学全跳过），
//   压测后跑 teardown 清理即可。order/cart 场景是闭环的，不留数据。
// =============================================================
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/**
 * 被 Sentinel 限流（429）的请求数，单独计一笔。
 *
 * <p>为什么要单独计：429 会和其他错误一起计入 `http_req_failed`，报告里只剩一个笼统的失败率，
 * 分不清"系统扛不住"和"撞到配置的限流上限"。首轮压测就被这个坑到过：15.38% 的失败全部是
 * 搜索路由的 429（`search-route` 默认 50 QPS，见 `nacos-config/sentinel-gateway.json`），
 * 但看报告像是服务出了问题。</p>
 */
const throttled429 = new Counter('http_429_throttled');

/**
 * 成功请求（HTTP 2xx 且业务 code=200）的延迟与计数。
 *
 * <p><b>为什么不能直接用 http_req_duration</b>：429 是被限流快速拒绝的响应（通常几十毫秒），
 * 混进同一个分布会把 p95 往下拉——出现"并发越高、延迟越低"的假象。而本脚本又把 429 用
 * `http.expectedStatuses` 标成了"预期响应"，所以连 `http_req_duration{expected_response:true}`
 * 也排除不掉它。因此单独记一份**只含成功请求**的分布。</p>
 *
 * <p>"有效吞吐"看 `http_ok_requests` 的 rate，而不是 `http_reqs`——后者把 429 也算进去了。</p>
 */
const httpOkDuration = new Trend('http_ok_duration', true);
const httpOkRequests = new Counter('http_ok_requests');
/** 429 的独立延迟分布：用来证明"平均延迟下降"来自快速拒绝，而不是系统变快。 */
const throttledDuration = new Trend('http_429_duration', true);
/**
 * 业务失败（HTTP 2xx 但 R.code != 200）计数，标签为 flow + code + **归类后**的原因。
 *
 * <p>原因必须先归类再打标签：原始 msg 常带订单号、skuId 等动态值，直接当标签会造成基数爆炸。</p>
 */
const businessErrors = new Counter('business_errors');
/** 各 flow 实际执行的迭代数；与 http_ok_requests 相除即"每 iteration 发了几个请求"。 */
const flowIterations = new Counter('flow_iterations');

/** 业务错误归类表：新增失败类型时在这里补一行。 */
const ERROR_REASONS = [
  [/库存不足/, 'stock_shortage'],
  [/不能购买自己店铺/, 'self_purchase'],
  [/一次下单仅支持同一店铺/, 'multi_shop'],
  [/商品不存在/, 'sku_missing'],
  [/订单状态不允许|状态不允许/, 'invalid_state'],
  [/幂等/, 'idempotency'],
  [/限流|频繁|too many/i, 'throttled'],
  [/超时|timeout|timed out/i, 'timeout'],
];

function classifyReason(msg) {
  if (!msg) return 'unknown';
  for (const [re, label] of ERROR_REASONS) {
    if (re.test(msg)) return label;
  }
  return 'other';
}

/** 响应 JSON 缓存：req() 与各 flow 会多次读同一个响应，避免重复 JSON.parse（压测机自己也会成为瓶颈）。 */
const jsonCache = new WeakMap();

/**
 * 默认把 **429 视为"预期内的限流响应"**，不计入 `http_req_failed`。
 *
 * <p>理由：429 是系统**按配置主动泄流**的结果，不是故障。若把它计入失败率，那 `FAIL_RATE` 这个门槛
 * 就不再衡量"服务稳不稳"，而变成了"你给搜索链路配了多少 QPS"——换台机器、改个 Sentinel 阈值，
 * 门槛就红了，毫无信号价值。真实的失败（500/503/超时/断连）仍然照常计入。</p>
 *
 * <p>但限流必须**看得见**：所以有独立的 `http_429_throttled` 计数（带 flow 标签）出现在报告里。
 * 想让 429 重新计入失败，加 `-e COUNT_429_AS_FAILURE=1`。</p>
 */
const COUNT_429_AS_FAILURE = (__ENV.COUNT_429_AS_FAILURE || '0') === '1';
if (!COUNT_429_AS_FAILURE) {
  http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));
}

/** 统一的请求出口：只为把 429 单独计一笔；其余行为与直接调 http.* 完全一致 */
function req(method, url, body, params) {
  const res = http.request(method, url, body, params);
  const flow = (params && params.tags && params.tags.flow) || 'unknown';

  // 429：单独计数 + 单独记录延迟，不进入成功请求的分布
  if (res.status === 429) {
    throttled429.add(1, { flow });
    throttledDuration.add(res.timings.duration, { flow });
    return res;
  }

  const j = toJson(res); // 结果缓存在 WeakMap，后续 flow 复用，不重复解析
  const httpOk = res.status >= 200 && res.status < 300;
  const bizOk = !j || typeof j.code !== 'number' || j.code === 200;

  if (httpOk && bizOk) {
    httpOkDuration.add(res.timings.duration, { flow });
    httpOkRequests.add(1, { flow });
  } else {
    // HTTP 非 2xx，或 HTTP 200 但业务码失败——两类都算"没服务成功"
    businessErrors.add(1, {
      flow,
      code: j && typeof j.code === 'number' ? String(j.code) : String(res.status),
      reason: classifyReason((j && j.msg) || (httpOk ? 'unknown' : `http_${res.status}`)),
    });
  }
  return res;
}

function httpGet(url, params) {
  return req('GET', url, null, params);
}
function httpPost(url, body, params) {
  return req('POST', url, body, params);
}
function httpPut(url, body, params) {
  return req('PUT', url, body, params);
}
function httpDelete(url, body, params) {
  return req('DELETE', url, body, params);
}

// ---------------- 配置 ----------------
const BASE = __ENV.BASE_URL || 'http://localhost:88/api';
const PASSWORD = __ENV.PASSWORD || 'admin123';
const BUYER_COUNT = Number(__ENV.BUYER_COUNT || 20);
const BUYER_PHONE_PREFIX = __ENV.BUYER_PHONE_PREFIX || '139'; // 13900000001..13900000020
const KEYWORDS = (__ENV.KEYWORDS || '手机,小米,耳机,九成新,键盘')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const ENABLE_WRITE = (__ENV.ENABLE_WRITE || '1') !== '0';
const VUS = __ENV.VUS ? Number(__ENV.VUS) : null;
const DURATION = __ENV.DURATION || '60s';
const P95_MS = __ENV.P95_MS || '800';
const FAIL_RATE = __ENV.FAIL_RATE || '0.05';
const SLEEP = Number(__ENV.SLEEP || 1);
// 写链路权重（百分比，读链路吃剩下的）；ENABLE_WRITE=0 时全部按读处理
const W_CART = Number(__ENV.W_CART || 15);
const W_ORDER = Number(__ENV.W_ORDER || 15);
const W_PAY = Number(__ENV.W_PAY || 10);

// 分链路门槛：子指标被 thresholds 引用后才会出现在默认汇总里，这就是"分链路 p95"的来源
const thresholds = {
  http_req_failed: [`rate<${FAIL_RATE}`],
  // 延迟门槛只看**成功请求**（http_ok_duration）。刻意不用 http_req_duration：
  // 它混入了 429 的快速拒绝，会把 p95 拉低，制造"并发越高越快"的假象。
  'http_ok_duration{flow:read}': ['p(95)<500'],
  'http_ok_duration{flow:cart}': [`p(95)<${P95_MS}`],
  'http_ok_duration{flow:order}': ['p(95)<1500'],
  'http_ok_duration{flow:pay}': ['p(95)<1500'],
  'http_req_failed{flow:read}': [`rate<${FAIL_RATE}`],
  'http_req_failed{flow:cart}': [`rate<${FAIL_RATE}`],
  'http_req_failed{flow:order}': [`rate<${FAIL_RATE}`],
  'http_req_failed{flow:pay}': [`rate<${FAIL_RATE}`],
  checks: ['rate>0.95'],
  // 以下门槛恒真（count>=0），唯一作用是把这些分链路计数带进默认汇总：
  //   http_ok_requests{flow} / flow_iterations{flow} = 每个 iteration 实际发了几个请求
  //   http_ok_requests 的 rate = 有效吞吐（不含 429）
  'http_ok_requests{flow:read}': ['count>=0'],
  'http_ok_requests{flow:cart}': ['count>=0'],
  'http_ok_requests{flow:order}': ['count>=0'],
  'http_ok_requests{flow:pay}': ['count>=0'],
  'flow_iterations{flow:read}': ['count>=0'],
  'flow_iterations{flow:cart}': ['count>=0'],
  'flow_iterations{flow:order}': ['count>=0'],
  'flow_iterations{flow:pay}': ['count>=0'],
};

export const options = {
  // 分链路趋势统计都打出来，便于横向对比
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  ...(VUS
    ? { vus: VUS, duration: DURATION } // 恒定并发：k6 run -e VUS=30 -e DURATION=60s ...
    : {
        // 默认梯度（20 → 50 → 0，共 50 秒）
        stages: [
          { duration: '10s', target: 20 },
          { duration: '30s', target: 50 },
          { duration: '10s', target: 0 },
        ],
      }),
  thresholds,
};

// ---------------- setup：一次性登录 + 数据探针 ----------------
export function setup() {
  const tokens = [];
  const failures = [];

  for (let i = 1; i <= BUYER_COUNT; i++) {
    const phone = BUYER_PHONE_PREFIX + String(i).padStart(8, '0');
    const res = httpPost(`${BASE}/auth/login`, JSON.stringify({ phone, password: PASSWORD }), {
      headers: { 'Content-Type': 'application/json' },
      tags: { flow: 'setup' },
    });
    const json = toJson(res);
    const token = json && json.code === 200 && json.data ? json.data.token : null;
    if (token) {
      tokens.push(token);
    } else {
      failures.push(`${phone}: HTTP ${res.status} ${(json && json.msg) || ''}`);
    }
  }

  if (failures.length > 0) {
    console.warn(`WARN setup: ${failures.length}/${BUYER_COUNT} logins failed -> ${failures.slice(0, 3).join(' | ')}`);
  }
  if (tokens.length === 0) {
    // 关键：这里必须炸掉。旧版脚本登录失败后每轮直接 return，
    // 报告照样"0 错误、P95 5ms"，看着全绿其实什么都没压。
    fail(
      'setup 全部登录失败：压测账号不存在或密码不对。\n' +
        '  1) 先播种：cmd /c scripts\\load-test\\seed\\seed-loadtest.bat\n' +
        `  2) 账号密码 = 管理员密码（默认 admin123），改了 ADMIN_PASSWORD 就要传 -e PASSWORD=...\n` +
        `  3) 首个失败：${failures[0]}`
    );
  }

  // 数据探针：搜索走 ES，没数据就没必要往下压（否则每条 check 都在报假失败）
  const kw = encodeURIComponent(KEYWORDS[0]);
  const probe = httpGet(`${BASE}/search/shops?keyword=${kw}&page=1&size=1`, { tags: { flow: 'setup' } });
  const pj = toJson(probe);
  const total = pj && pj.code === 200 && pj.data ? pj.data.total : 0;
  if (!total) {
    fail(
      `setup 数据探针失败：搜索 "${KEYWORDS[0]}" 命中 0 家店铺。\n` +
        '  1) 跑过播种脚本了吗？（seed-loadtest.bat）\n' +
        '  2) 商品进 ES 了吗？跑 node scripts/load-test/seed/post-seed.mjs，或等最多 5 分钟（reconcileEsIndex）\n' +
        '  3) 确认 lingmart-product 与 ES 都健康：bash scripts/verify-health.sh'
    );
  }

  console.log(`setup ok: ${tokens.length} buyers, probe "${KEYWORDS[0]}" -> ${total} shops`);
  return { tokens };
}

// ---------------- 主循环 ----------------
export default function (data) {
  // 按 VU 轮换账号：避免所有压力集中在同一个买家（也是"多账号"该有的样子）
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const headers = { Authorization: `Bearer ${token}` };

  const flow = pickFlow();
  flowIterations.add(1, { flow });
  if (flow === 'read') {
    readFlow(headers);
  } else if (flow === 'cart') {
    cartFlow(headers);
  } else if (flow === 'order') {
    orderFlow(headers);
  } else {
    payFlow(headers);
  }

  sleep(SLEEP);
}

function pickFlow() {
  if (!ENABLE_WRITE) {
    return 'read';
  }
  const r = Math.random() * 100;
  if (r < W_CART) return 'cart';
  if (r < W_CART + W_ORDER) return 'order';
  if (r < W_CART + W_ORDER + W_PAY) return 'pay';
  return 'read';
}

// ---------------- 场景 1：只读 ----------------
function readFlow(headers) {
  const tag = { flow: 'read' };
  const keyword = pickKeyword();

  // 商品检索：压 ES 的 match 查询 + 高亮 + 排序（旧脚本这个词是空串，等于只测 match_all）
  const sort = ['', 'price_asc', 'price_desc', 'sale_desc'][Math.floor(Math.random() * 4)];
  const res = httpGet(
    `${BASE}/search?keyword=${encodeURIComponent(keyword)}&sort=${sort}&page=1&size=12`,
    { headers, tags: tag }
  );
  const j = toJson(res);
  const list = listOf(res);
  assert(
    res,
    {
      'search 业务成功': () => j !== null && j.code === 200,
      'search 列表结构正确': () => list !== null,
      'search 有命中（数据已播种）': () => list !== null && list.length > 0,
      'search 商品字段完整': () => {
        const it = list !== null && list.length > 0 ? list[0] : null;
        return !!it && !!it.product && !!it.product.spuId && Number(it.product.price) > 0;
      },
    },
    tag
  );

  browse(headers, 'read', keyword);
}

// ---------------- 场景 2：购物车（闭环） ----------------
function cartFlow(headers) {
  const b = browse(headers, 'cart');
  if (!b) return;
  const skuId = pickSkuId(b.skus);
  if (!skuId) return;

  const tag = { flow: 'cart' };
  const jsonHeaders = withJson(headers);

  // 加购：skuId 原样传字符串（见文件头"id 一律按字符串处理"）
  const addRes = httpPost(`${BASE}/cart`, JSON.stringify({ skuId: skuId, num: 1 }), {
    headers: jsonHeaders,
    tags: tag,
  });
  assert(addRes, { 'cart 加购成功': (r) => isOk(r) }, tag);

  const listRes = httpGet(`${BASE}/cart`, { headers, tags: tag });
  const items = dataOf(listRes);
  assert(listRes, { 'cart 列表结构正确': () => Array.isArray(items) }, tag);
  if (!Array.isArray(items)) return;

  const mine = items.filter((it) => String(it.skuId) === String(skuId));
  assert(listRes, { 'cart 列表含刚加购的 SKU': () => mine.length > 0 }, tag);
  if (mine.length === 0) return;
  const cartId = mine[0].id;

  // 改数量（CartServiceImpl.update 是**赋值**语义，不是累加 → 断言 num 应为 2）
  const updRes = httpPut(`${BASE}/cart/${cartId}?num=2&checked=1`, null, { headers, tags: tag });
  assert(updRes, { 'cart 改数量成功': (r) => isOk(r) }, tag);

  const afterRes = httpGet(`${BASE}/cart`, { headers, tags: tag });
  const after = dataOf(afterRes);
  assert(
    afterRes,
    {
      'cart 改数量后 num=2 真的生效': () =>
        Array.isArray(after) && after.some((it) => String(it.id) === String(cartId) && it.num === 2),
    },
    tag
  );

  // 删除收尾：购物车场景不留残留
  const delRes = httpDelete(`${BASE}/cart/${cartId}`, null, { headers, tags: tag });
  assert(delRes, { 'cart 删除成功（闭环，无残留）': (r) => isOk(r) }, tag);
}

// ---------------- 场景 3：下单 → 取消（闭环） ----------------
function orderFlow(headers) {
  const b = browse(headers, 'order');
  if (!b) return;

  const sn = createOrder(headers, pickSkuId(b.skus), 'order');
  if (!sn) return;

  const tag = { flow: 'order' };
  const res = httpPost(`${BASE}/order/${sn}/cancel`, null, { headers, tags: tag });
  assert(res, { 'order 取消成功（闭环，无残留）': (r) => isOk(r) }, tag);
}

// ---------------- 场景 4：下单 → 支付（会留下已支付订单） ----------------
function payFlow(headers) {
  const b = browse(headers, 'pay');
  if (!b) return;

  // 优先挑不限量 SKU：ware 的锁定/扣减/回补对它全部跳过，
  // 反复压也不会把库存打空（没挑到就退回普通 SKU，库存给了 10 万，短时压测足够）
  const unlimited = b.skus.filter((s) => s.unlimited === true);
  const pool = unlimited.length > 0 ? unlimited : b.skus;
  const sn = createOrder(headers, pickSkuId(pool), 'pay');
  if (!sn) return;

  const tag = { flow: 'pay' };
  const payRes = httpPost(`${BASE}/order/${sn}/pay`, null, { headers, tags: tag });
  assert(payRes, { 'order 支付成功': (r) => isOk(r) }, tag);

  // 数据正确性断言：不能只看"接口没报错"，要确认状态真的流转了
  const detailRes = httpGet(`${BASE}/order/${sn}`, { headers, tags: tag });
  const d = dataOf(detailRes);
  assert(
    detailRes,
    {
      'order 支付后 payStatus=1（payStatus 是 Integer，比较用数字）': () => !!d && d.payStatus === 1,
    },
    tag
  );
}

// ---------------- 复用：浏览（检索店铺 → 进店 → 商品详情） ----------------
/** @returns {{shopId:string, spuId:string, skus:Array}|null} */
function browse(headers, flow, keyword) {
  const tag = { flow };
  const kw = keyword || pickKeyword();

  const shopRes = httpGet(`${BASE}/search/shops?keyword=${encodeURIComponent(kw)}&page=1&size=12`, {
    headers,
    tags: tag,
  });
  const shops = listOf(shopRes);
  assert(
    shopRes,
    {
      'search/shops 业务成功': (r) => isOk(r),
      'search/shops 列表结构正确': () => shops !== null,
      'search/shops 有命中': () => shops !== null && shops.length > 0,
    },
    tag
  );
  if (shops === null || shops.length === 0) return null;
  // 随机取一家（不要固定 list[0]，否则压力全压在同一家店上）
  const shopId = shops[Math.floor(Math.random() * shops.length)].shopId;

  // 店铺主页（白名单路径，无需登录也能看，这里仍带 token —— 与真实前端一致）
  const storeRes = httpGet(`${BASE}/shop/storefront/${shopId}`, { headers, tags: tag });
  const store = dataOf(storeRes);
  assert(
    storeRes,
    {
      'storefront 业务成功': (r) => isOk(r),
      'storefront 返回店铺本身': () => !!store && String(store.id) === String(shopId),
      'storefront 商品列表是数组': () => !!store && Array.isArray(store.products),
    },
    tag
  );
  if (!store || !Array.isArray(store.products) || store.products.length === 0) return null;
  const spuId = store.products[Math.floor(Math.random() * store.products.length)].spuId;

  // 商品详情
  const detailRes = httpGet(`${BASE}/product/${spuId}`, { headers, tags: tag });
  const detail = dataOf(detailRes);
  const skus = detail ? detail.skus : null;
  assert(
    detailRes,
    {
      'detail 业务成功': (r) => isOk(r),
      // Long 被序列化成字符串：只能按字符串比，用 === 比数字会永远失败
      'detail 返回的 spuId 与请求一致': () => !!detail && String(detail.spuId) === String(spuId),
      'detail skus 非空且是数组': () => Array.isArray(skus) && skus.length > 0,
      'detail 价格为正': () => !!detail && Number(detail.price) > 0,
    },
    tag
  );
  if (!Array.isArray(skus) || skus.length === 0) return null;
  return { shopId: shopId, spuId: spuId, skus: skus };
}

// ---------------- 复用：下单 ----------------
/** @returns {string|null} orderSn */
function createOrder(headers, skuId, flow) {
  if (!skuId) return null;
  const tag = { flow };
  const res = httpPost(
    `${BASE}/order`,
    JSON.stringify({ items: [{ skuId: skuId, num: 1 }], buyerRemark: 'k6-load-test' }),
    {
      headers: withJson(headers, { 'Idempotency-Key': randomString(20) }),
      tags: tag,
    }
  );
  const j = toJson(res);
  const sn = j && j.code === 200 && j.data ? j.data : null;
  assert(res, { 'order 下单成功并返回 orderSn': () => !!sn }, tag);
  return sn ? String(sn) : null;
}

// ---------------- 小工具 ----------------
/**
 * 断言出口：**被限流（429）的响应不做断言**。
 *
 * <p>429 是系统按配置主动泄流，这次请求根本没被服务——断言它的载荷没有意义，
 * 只会把一个"配置了多少 QPS"的事实伪装成"业务出错了"。限流本身由
 * `http_429_throttled` 计数呈现，不会因此被藏起来。</p>
 */
function assert(res, sets, tags) {
  if (res.status === 429) {
    return;
  }
  check(res, sets, tags);
}

function toJson(res) {
  if (!res) return null;
  if (jsonCache.has(res)) return jsonCache.get(res);
  let parsed = null;
  if (res.body && res.body.length > 0) {
    try {
      parsed = res.json();
    } catch (e) {
      console.warn(`响应不是合法 JSON（HTTP ${res.status}）：${String(res.body).slice(0, 120)}`);
    }
  }
  jsonCache.set(res, parsed);
  return parsed;
}

function dataOf(res) {
  const j = toJson(res);
  return j && j.code === 200 ? j.data : null;
}

/**
 * 取分页接口的 `data.list`。
 *
 * <p>⚠️ 分页响应的 `data` 是 `{list,total,page,size}` 这个包裹对象，**不是数组本身**。
 * 首轮压测就栽在这里：把 `dataOf()` 的结果当数组去 `Array.isArray` 检查，
 * 于是 `browse()` 每次都提前 return，整场压测只打了两个搜索接口，
 * 店铺主页/详情/购物车/下单/支付**一次都没执行**（报告里连这些 check 都不出现）。</p>
 *
 * @returns {Array|null} 数组本身；响应不是成功分页结构时返回 null
 */
function listOf(res) {
  const d = dataOf(res);
  return d && Array.isArray(d.list) ? d.list : null;
}

function isOk(res) {
  const j = toJson(res);
  return j !== null && j.code === 200;
}

function withJson(headers, extra) {
  return Object.assign({}, headers, { 'Content-Type': 'application/json' }, extra || {});
}

function pickKeyword() {
  return KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
}

function pickSkuId(skus) {
  if (!Array.isArray(skus) || skus.length === 0) return null;
  const s = skus[Math.floor(Math.random() * skus.length)];
  return s && s.id ? s.id : null;
}

/** 内联的随机串（原来 import 自 https://jslib.k6.io —— 压测不该依赖外网模块） */
function randomString(len) {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < len; i++) {
    s += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return s;
}
