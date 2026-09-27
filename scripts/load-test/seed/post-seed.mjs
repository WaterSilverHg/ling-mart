#!/usr/bin/env node
// =============================================================
// 轻营 · 播种后收尾
//
//   默认：把 seed-loadtest.sql 插进库的商品**写进 ES**（逐店调管理端重建入口），
//         顺带查一次搜索，确认 ES 真的通了。
//   --clean：清理 ES 里那批压测店铺的文档（SQL 删库不会带走索引文档）。
//
// 用法（cmd 与 Git Bash 都可，Node 18+）：
//   node scripts/load-test/seed/post-seed.mjs
//   node scripts/load-test/seed/post-seed.mjs --clean
//
// 环境变量：BASE_URL（默认 http://127.0.0.1:88/api，见下方为何不用 localhost）、
//           ES_URL（默认 http://127.0.0.1:9201）、
//           ADMIN_PHONE / ADMIN_PASSWORD（默认 13800000000 / admin123）
//
// 为什么需要这一步：SQL 插库不会进 ES。除了本脚本，还有一条兜底路径 ——
//   什么都不做，等 ProductServiceImpl.reconcileEsIndex 定时任务（默认 5 分钟）
//   自动补索引（它只补缺失、不删多余，所以清理必须走 --clean）。
//
// 为什么输出刻意用 ASCII：Windows 控制台多是 GBK(936)，本项目的 Java 日志中文
//   已经因此乱码过（见 docs/ops-checklist.md §七）。脚本面向终端，就不要再制造一次。
// =============================================================

// 默认用 127.0.0.1 而不是 localhost：Node 18+ 对 localhost 可能先解析到 IPv6(::1)，
// 而 Docker Desktop 的端口映射只监听 IPv4 —— 于是报一个毫无线索的 `fetch failed`。
// 网关与 ES 都是走 Docker 端口映射访问的，所以这里统一写 IPv4 字面量。
const BASE = process.env.BASE_URL || 'http://127.0.0.1:88/api';
const ES = process.env.ES_URL || 'http://127.0.0.1:9201';
const ADMIN_PHONE = process.env.ADMIN_PHONE || '13800000000';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123';

// 与 seed-loadtest.sql 的 ID 规划保持一致
const SHOP_ID_START = 9300001;
const SHOP_COUNT = 20;
const SHOP_ID_END = SHOP_ID_START + SHOP_COUNT - 1;

const CLEAN = process.argv.includes('--clean');

/** 带超时的 fetch（Node 18+ 自带 fetch） */
async function request(url, { method = 'GET', body, token, timeoutMs = 30000 } = {}) {
  const headers = {};
  if (body !== undefined && body !== null) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（例如 ES 的纯文本错误）保留原文 */
  }
  return { status: res.status, json, text };
}

async function adminLogin() {
  const { status, json, text } = await request(`${BASE}/auth/login`, {
    method: 'POST',
    body: { phone: ADMIN_PHONE, password: ADMIN_PASSWORD },
  });
  const token = json?.data?.token;
  if (!token) {
    throw new Error(
      `admin login failed (HTTP ${status}): ${json?.msg || text?.slice(0, 200)}` +
        `\n  hint: check ADMIN_PHONE/ADMIN_PASSWORD; the admin is created by lingmart-auth on startup.`
    );
  }
  return token;
}

/** 逐店重建 ES（管理端入口，服务端自己查店名，不走缓存） */
async function reindexAllShops() {
  const token = await adminLogin();
  let total = 0;
  let failed = 0;
  for (let i = 0; i < SHOP_COUNT; i++) {
    const shopId = SHOP_ID_START + i;
    const { status, json } = await request(`${BASE}/admin/product/shop/${shopId}/reindex-es`, {
      method: 'POST',
      token,
    });
    if (json?.code !== 200) {
      failed++;
      console.log(`  shop ${shopId} FAILED (HTTP ${status}): ${json?.msg || 'unknown'}`);
      continue;
    }
    total += json.data || 0;
    if ((i + 1) % 5 === 0 || i === SHOP_COUNT - 1) {
      console.log(`  ... ${i + 1}/${SHOP_COUNT} shops done, ${total} docs indexed`);
    }
  }
  console.log(`\nreindex finished: ${total} docs across ${SHOP_COUNT - failed}/${SHOP_COUNT} shops`);
  if (total === 0) {
    console.log('WARNING: 0 docs indexed - did you run seed-loadtest.sql first?');
    console.log('         (or the products are not status=1 & review_status=1)');
  }
  return total;
}

/** 冒烟：搜索接口是否真的有数据（搜索走 ES，是"索引是否生效"的最终证据） */
async function smokeSearch() {
  const kw = encodeURIComponent('手机');
  const { status, json } = await request(`${BASE}/search/shops?keyword=${kw}&page=1&size=12`);
  if (json?.code !== 200) {
    console.log(`search smoke: FAILED (HTTP ${status}) ${json?.msg || ''}`);
    return false;
  }
  const total = json.data?.total ?? 0;
  const list = json.data?.list?.length ?? 0;
  console.log(`search smoke: keyword="手机" -> total shops=${total}, returned=${list}`);
  if (total === 0) {
    console.log('  WARNING: empty result. ES index for seeded products is probably not ready yet.');
    console.log('           retry in a minute, or check that lingmart-product / ES are up.');
    return false;
  }
  return true;
}

/** 清 ES：按 shopId 区间删文档（SQL 删库不会带走索引文档） */
async function cleanEs() {
  const url = `${ES}/lingmart_product/_delete_by_query?refresh=true&conflicts=proceed`;
  const { status, json, text } = await request(url, {
    method: 'POST',
    body: { query: { range: { shopId: { gte: SHOP_ID_START, lte: SHOP_ID_END } } } },
  });
  if (status === 404) {
    console.log('ES cleanup: index lingmart_product not found - nothing to clean.');
    return true;
  }
  if (json?.error) {
    console.log(`ES cleanup FAILED (HTTP ${status}): ${json.error.type || text?.slice(0, 200)}`);
    return false;
  }
  console.log(`ES cleanup: deleted ${json?.deleted ?? 0} docs (shopId ${SHOP_ID_START}..${SHOP_ID_END})`);
  return true;
}

async function main() {
  console.log(`target: ${BASE}${CLEAN ? '  [clean mode]' : ''}\n`);
  if (CLEAN) {
    const ok = await cleanEs();
    process.exit(ok ? 0 : 1);
  }
  const indexed = await reindexAllShops();
  const searchOk = await smokeSearch();
  process.exit(indexed > 0 && searchOk ? 0 : 1);
}

main().catch((e) => {
  // Node 的 fetch 把真实原因放在 cause 里，只打 e.message 永远只有一句 "fetch failed"
  const detail = e.cause ? ` [${e.cause.code || e.cause.name || e.cause.message}]` : '';
  console.log(`ERROR: ${e.message}${detail}`);
  if (e.name === 'TimeoutError') {
    console.log('  hint: 网关无响应。服务起来了吗？bash scripts/verify-health.sh 看 :88 与 product 是否 UP');
  } else if (e.cause?.code === 'ECONNREFUSED' || e.message === 'fetch failed') {
    console.log('  hint: 连不上网关。本步要调 /api/admin/product/shop/{id}/reindex-es，');
    console.log('        所以必须先把服务跑起来（网关 + product 至少要活着）：');
    console.log('        1) 没起服务 -> 启动后重跑本脚本');
    console.log('        2) 服务已起 -> 核对 BASE_URL（默认 http://127.0.0.1:88/api）与端口映射');
    console.log('        3) 也可以什么都不做：等最多 5 分钟，reconcileEsIndex 会自动补缺失的 ES 文档');
  }
  process.exit(1);
});
