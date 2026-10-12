#!/usr/bin/env node
/**
 * Frontend behavior checks using mocked /v1 responses, not a Java integration test.
 * Run with Node 20+ and Playwright available to this project:
 *   node scripts/frontend-smoke.mjs
 * Or set PLAYWRIGHT_MODULE_PATH to the installed playwright package directory and
 * BROWSER_EXECUTABLE to a Chromium/Edge executable. Uses a fresh temporary browser
 * profile, never an existing browser profile. No backend or credentials required.
 * Screenshots and the JSON result are written to gitignored evidence/frontend/.
 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const staticRoot = path.join(root, "src/main/resources/static");
const evidence = path.join(root, "evidence/frontend");
const playwrightPath = process.env.PLAYWRIGHT_MODULE_PATH;
let playwright;
try {
  playwright = await import(playwrightPath
    ? pathToFileURL(path.join(playwrightPath, "index.mjs")).href
    : "playwright");
} catch (error) {
  throw new Error("Playwright is required. Install it for this project or set PLAYWRIGHT_MODULE_PATH to an existing playwright package directory.", { cause: error });
}

await mkdir(evidence, { recursive: true });
const mime = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png" };
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const filename = path.resolve(staticRoot, `.${pathname === "/" ? "/index.html" : pathname}`);
    if (!filename.startsWith(staticRoot + path.sep)) throw new Error("Invalid path");
    const body = await readFile(filename);
    res.writeHead(200, { "Content-Type": mime[path.extname(filename)] || "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end("Not found");
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await playwright.chromium.launch({
    headless: true,
    ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
  });
} catch (error) {
  await new Promise((resolve) => server.close(resolve));
  throw error;
}

const now = Date.now();
const iso = (offset) => new Date(now + offset).toISOString();
const day = 86400000;
const xss = '<img src=x onerror="window.__injected=true">';
const sessions = [1, 2].map((number) => ({
  sessionId: `session-${number}`, state: "OPEN", startAt: iso(day * number),
  bookingOpenAt: iso(-day), bookingCloseAt: iso(day / 2),
}));
const events = [
  { eventId: "music", title: "日落音乐现场", description: "把周末交给喜欢的旋律。", venueText: "北京 · 河岸剧场" },
  { eventId: "future", title: "下一场艺术展", description: "场次尚未公布。", venueText: "上海 · 艺术空间" },
  { eventId: "text-only", title: "城市漫游计划", description: "在熟悉的街角，发现新的故事。", venueText: "北京 · 城市书店" },
];
const paidOrder = {
  orderId: "order-paid", sessionId: "session-1", tierId: "tier-1", eventId: "music",
  eventTitle: "日落音乐现场", tierName: "普通票", startAt: iso(day), quantity: 1,
  orderStatus: "PAID", payableCent: 9900, paidAt: iso(-60000), refundEligible: true,
  refundDeadline: iso(day), currentAttemptId: "payment-1", serverTime: iso(0),
};
const holdOrder = {
  ...paidOrder, orderId: "order-hold", orderStatus: "HOLD", eventTitle: "周末音乐分享会",
  payExpiresAt: iso(600000), currentAttemptId: null, paidAt: null,
};
const wait = { waitlistId: "wait-1", sessionId: "session-2", tierId: "tier-2", status: "WAITING", createdAt: iso(-10000), serverTime: iso(0) };
const user = { id: "smoke-user", nickname: "现场观众", phone: "13800000000", role: "USER" };
const pageData = (items) => ({ items, page: 1, size: 12, hasNext: false });
const results = [];

async function until(check, message, timeout = 5000) {
  const start = Date.now();
  let lastError;
  while (Date.now() - start < timeout) {
    try { if (await check()) return; } catch (error) { lastError = error; }
    await new Promise((resolve) => setTimeout(resolve, 40));
  }
  throw new Error(message, lastError ? { cause: lastError } : undefined);
}
async function hasText(locator, text) {
  await until(async () => (await locator.innerText()).includes(text), `Expected visible text: ${text}`);
}
async function noOverflow(page, label) {
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
    dialog: document.querySelector("dialog[open]")?.getBoundingClientRect().toJSON(),
  }));
  assert.ok(widths.document <= widths.viewport + 1 && widths.body <= widths.viewport + 1, `${label}: horizontal overflow ${JSON.stringify(widths)}`);
  if (widths.dialog) assert.ok(widths.dialog.left >= -1 && widths.dialog.right <= widths.viewport + 1, `${label}: dialog outside viewport`);
}
async function screenshot(page, name) {
  await page.screenshot({ path: path.join(evidence, `${name}.png`), fullPage: !/^(detail|refund)-/.test(name), animations: "disabled" });
}
async function closeDialog(page) {
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  await until(() => page.locator("dialog[open]").count().then((count) => count === 0), "Dialog should close");
}

async function fixture({ width = 1440, authenticated = false, admin = false, malicious = false } = {}) {
  const context = await browser.newContext({ viewport: { width, height: width === 375 ? 812 : 1000 }, locale: "zh-CN", reducedMotion: "reduce" });
  if (authenticated || admin) await context.addInitScript(() => sessionStorage.setItem("live.token", "smoke-token"));
  const page = await context.newPage();
  page.setDefaultTimeout(5000);
  const errors = [];
  const unmatched = [];
  const requests = [];
  let slowResolve;
  let slowRequest;
  let loginAttempts = 0;
  let bookingAttempts = 0;
  let unknownBooking = false;
  let refunded = false;
  let cancelledOrder = false;
  let cancelledWait = false;
  const identity = admin ? { ...user, role: "ADMIN", nickname: "现场管理员" } : user;
  const catalog = events.map((event) => malicious && event.eventId === "text-only"
    ? { ...event, title: `文字安全 ${xss}`, description: `应原样显示：${xss}` }
    : event);
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname;
    const method = request.method();
    requests.push({ path: pathname, method, headers: request.headers(), body: request.postDataJSON() });
    const reply = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/v1/events") return reply(pageData(catalog));
    if (pathname.startsWith("/v1/events/")) {
      if (slowRequest === pathname) await new Promise((resolve) => { slowResolve = resolve; });
      const event = catalog.find((item) => item.eventId === pathname.split("/").at(-1));
      return reply({ ...event, sessions: event?.eventId === "music" ? sessions : [] });
    }
    if (/^\/v1\/sessions\/[^/]+\/ticket-tiers$/.test(pathname)) {
      const session = sessions.find((item) => pathname.includes(`/${item.sessionId}/`));
      const second = session.sessionId === "session-2";
      return reply({ ...session, eventId: "music", items: [{ tierId: second ? "tier-2" : "tier-1", name: second ? "二日票" : "普通票", areaLabel: "自由站席", unitPriceCent: second ? 12900 : 9900, referenceAvailable: 12, capacity: 30 }] });
    }
    if (pathname === "/v1/auth/me") return reply(identity);
    if (pathname === "/v1/auth/code") return reply({ retryAfterSeconds: 60, expiresInSeconds: 120 });
    if (pathname === "/v1/auth/login") {
      loginAttempts++;
      if (request.postDataJSON().code !== "123456") return reply({ code: "CODE_INVALID_OR_EXPIRED" }, 400);
      return reply({ token: "smoke-token", user: identity, tokenType: "Bearer", expiresInSeconds: 3600 });
    }
    if (pathname === "/v1/orders") return reply(pageData([{ ...paidOrder, ...(refunded ? { orderStatus: "REFUNDING", currentRefundId: "refund-1" } : {}) }, { ...holdOrder, ...(cancelledOrder ? { orderStatus: "CANCELLED" } : {}) }]));
    if (pathname === "/v1/orders/order-paid") return reply({ ...paidOrder, ...(refunded ? { orderStatus: "REFUNDING", currentRefundId: "refund-1" } : {}) });
    if (pathname === "/v1/orders/order-hold") return reply(holdOrder);
    if (pathname === "/v1/waitlist-entries" && method === "GET") return reply(pageData([{ ...wait, ...(cancelledWait ? { status: "CANCELLED" } : {}) }]));
    if (pathname === "/v1/orders/order-hold/cancel" && method === "POST") {
      cancelledOrder = true;
      return reply({ ...holdOrder, orderStatus: "CANCELLED" });
    }
    if (pathname === "/v1/waitlist-entries/wait-1/cancel" && method === "POST") {
      cancelledWait = true;
      return reply({ ...wait, status: "CANCELLED" });
    }
    if (pathname === "/v1/booking-requests" && method === "POST") {
      bookingAttempts++;
      if (unknownBooking && bookingAttempts === 1) return reply({ code: "ACCEPTANCE_UNKNOWN" }, 503);
      return reply({ requestId: "booking-1", status: "ACCEPTED", acceptedAt: iso(0) }, 202);
    }
    if (pathname === "/v1/booking-requests/booking-1") return reply({ requestId: "booking-1", status: "ACCEPTED" });
    if (pathname === "/v1/orders/order-paid/refunds") {
      refunded = true;
      return reply({ refundId: "refund-1", status: "PENDING" }, 202);
    }
    if (pathname === "/v1/refunds/refund-1") return reply({ refundId: "refund-1", orderId: "order-paid", status: "PENDING", orderStatus: "REFUNDING", amountCent: 9900, reason: "计划有变", requestedAt: iso(0) });
    if (pathname === "/v1/admin/events") return reply(pageData([{ ...events[0], sessions: [{ ...sessions[0], ticketTiers: [{ tierId: "tier-1", sessionId: "session-1", name: "普通票", areaLabel: "自由站席", capacity: 30, referenceAvailable: 12, unitPriceCent: 9900 }] }] }]));
    if (pathname === "/v1/admin/processing-failures") return reply({ ...pageData([]), backlog: { acceptedRequests: 2, oldestAcceptedSeconds: 5 }, invalidMessageCount: 0 });
    if (pathname === "/v1/admin/refunds") return reply(pageData([]));
    unmatched.push(`${method} ${pathname}`);
    return reply({ code: "UNMOCKED_ENDPOINT" }, 500);
  });
  await page.goto(origin);
  await page.getByRole("link", { name: "查看活动：日落音乐现场", exact: true }).waitFor();
  return {
    page, requests, context, errors, unmatched,
    slowDetail() { slowRequest = "/v1/events/music"; },
    async releaseDetail() { await until(() => !!slowResolve, "Slow detail request not started"); slowResolve(); },
    unknownBooking() { unknownBooking = true; },
    loginAttempts: () => loginAttempts,
    async finish() {
      assert.deepEqual(unmatched, [], "Every API call must have an explicit mock");
      assert.deepEqual(errors, [], "No uncaught browser errors");
      assert.equal(await page.evaluate(() => window.__injected), undefined, "API data must not execute scripts");
    },
  };
}

async function test(name, options, run) {
  let f;
  try {
    f = await fixture(options);
    await run(f);
    await f.finish();
    results.push({ name, passed: true });
    console.log(`PASS ${name}`);
  } catch (error) {
    if (f) await screenshot(f.page, `FAIL-${name}`).catch(() => {});
    results.push({ name, passed: false, error: error.stack || String(error) });
    console.error(`FAIL ${name}: ${error.message}`);
  } finally {
    if (f) await f.context.close();
  }
}

try {
  for (const width of [1440, 375]) {
    const suffix = width === 375 ? "mobile" : "desktop";
    await test(`discover-and-detail-${suffix}`, { width }, async ({ page }) => {
      assert.equal(await page.locator(".event-card").count(), 3);
      await noOverflow(page, "Discover");
      await screenshot(page, `discover-${suffix}`);
      const search = page.locator('input[type="search"]');
      await search.focus();
      await search.pressSequentially("河岸");
      await until(() => page.locator(".event-card").count().then((count) => count === 1), "Search should filter current-page events by venue");
      assert.ok(await search.evaluate((node) => document.activeElement === node), "Typing search should preserve input focus");
      assert.equal(await search.inputValue(), "河岸");
      await search.fill("没有这场活动");
      await until(() => page.locator(".event-card").count().then((count) => count === 0), "Unmatched search should clear cards");
      await hasText(page.locator("#main"), "没有");
      await search.fill("");
      await page.getByRole("link", { name: "查看活动：日落音乐现场", exact: true }).click();
      await hasText(page.locator("dialog"), "普通票");
      await hasText(page.locator("dialog"), "99.00");
      assert.equal(await page.locator('[data-action="session"]').count(), 2);
      await page.locator('[data-action="session"]').nth(1).click();
      await hasText(page.locator("dialog"), "二日票");
      await hasText(page.locator("dialog"), "129.00");
      await noOverflow(page, "Event detail");
      await screenshot(page, `detail-${suffix}`);
      const focusableCount = await page.locator('dialog button:not([disabled]), dialog input, dialog a[href]').count();
      for (let index = 0; index < focusableCount + 2; index++) {
        await page.keyboard.press("Tab");
        const focus = await page.evaluate(() => ({ inside: document.querySelector("dialog").contains(document.activeElement), tag: document.activeElement.tagName, label: document.activeElement.getAttribute("aria-label") || document.activeElement.textContent.slice(0, 60) }));
        assert.ok(focus.inside, `Keyboard focus stays in open dialog after wrapping (Tab ${index + 1}/${focusableCount + 2}: ${JSON.stringify(focus)})`);
      }
      await page.getByRole("button", { name: "关闭弹窗", exact: true }).focus();
      await page.keyboard.press("Shift+Tab");
      assert.ok(await page.evaluate(() => document.querySelector("dialog").contains(document.activeElement)), "Reverse tabbing stays in the modal");
      await page.keyboard.press("Escape");
      await until(() => page.locator("dialog[open]").count().then((count) => count === 0), "Escape should close dialog");
      assert.ok(await page.evaluate(() => document.activeElement?.getAttribute("data-action") === "event"), "Escape returns focus to event trigger");
      await page.getByRole("link", { name: "查看活动：下一场艺术展", exact: true }).click();
      await hasText(page.locator("dialog"), "场次");
      await until(() => page.locator("dialog .loading").count().then((count) => count === 0), "No-session detail must not show an indefinite ticket loading indicator");
      assert.equal(await page.locator('[data-action="book"]').count(), 0);
      await closeDialog(page);
    });
    await test(`wallet-refund-${suffix}`, { width, authenticated: true }, async ({ page, requests }) => {
      await page.getByRole("link", { name: "我的票夹", exact: true }).click();
      await hasText(page.locator("#main"), "日落音乐现场");
      await hasText(page.locator("#main"), "付款剩余");
      await noOverflow(page, "Wallet");
      await screenshot(page, `wallet-${suffix}`);
      await page.getByRole("button", { name: "我的候补", exact: true }).click();
      await hasText(page.locator("#ticket-content"), "候补排队中");
      await page.getByRole("button", { name: "我的订单", exact: true }).click();
      await page.getByRole("button", { name: "申请模拟退款", exact: true }).click();
      await hasText(page.locator("dialog"), "无真实资金收付");
      await noOverflow(page, "Refund dialog");
      await screenshot(page, `refund-${suffix}`);
      await page.getByRole("button", { name: "确认申请模拟退款", exact: true }).click();
      await hasText(page.locator("dialog"), "模拟退款记录");
      await hasText(page.locator("dialog"), "退款处理中");
      assert.equal(requests.filter((request) => request.method === "POST" && request.path.endsWith("/refunds")).length, 1);
      await closeDialog(page);
    });
    await test(`admin-${suffix}`, { width, admin: true }, async ({ page }) => {
      await page.getByRole("link", { name: "活动管理", exact: true }).click();
      await hasText(page.locator("#main"), "日落音乐现场");
      await noOverflow(page, "Admin catalog");
      await screenshot(page, `admin-${suffix}`);
      await page.getByRole("button", { name: /创建活动/ }).click();
      await page.getByLabel("活动名称", { exact: true }).fill("新的现场");
      await noOverflow(page, "Admin form");
      await closeDialog(page);
      await page.getByRole("button", { name: "异常处理", exact: true }).click();
      await hasText(page.locator("#main"), "暂时没有待处理退款");
      await noOverflow(page, "Admin processing");
    });
  }
  await test("login-validation-and-success", {}, async (f) => {
    const { page } = f;
    await page.getByRole("button", { name: "登录 / 注册", exact: true }).click();
    await page.getByLabel("手机号", { exact: true }).fill("123");
    await page.getByLabel("验证码", { exact: true }).fill("000000");
    await page.locator('#login-form button[type="submit"]').click();
    assert.equal(f.loginAttempts(), 0, "Invalid phone should fail browser validation without a login request");
    await page.getByLabel("手机号", { exact: true }).fill("13800000000");
    await page.getByRole("button", { name: "获取验证码", exact: true }).click();
    await hasText(page.locator("#code-help"), "验证码");
    await page.locator('#login-form button[type="submit"]').click();
    await hasText(page.locator("#login-error"), "验证码错误或已过期");
    await page.getByLabel("验证码", { exact: true }).fill("123456");
    await page.locator('#login-form button[type="submit"]').click();
    await hasText(page.locator("#account"), "现场观众");
    assert.equal(await page.locator("dialog[open]").count(), 0);
  });
  await test("slow-detail-cannot-replace-login", {}, async (f) => {
    const { page } = f;
    f.slowDetail();
    await page.getByRole("link", { name: "查看活动：日落音乐现场", exact: true }).click();
    await hasText(page.locator("dialog"), "正在");
    await closeDialog(page);
    await page.getByRole("button", { name: "登录 / 注册", exact: true }).click();
    const response = page.waitForResponse((response) => response.url().endsWith("/v1/events/music"));
    await f.releaseDetail();
    await response;
    await page.waitForTimeout(100);
    assert.ok(await page.locator("#login-form").isVisible(), "Stale detail response must not replace the newly opened login form");
    await closeDialog(page);
  });
  await test("unknown-booking-reuses-request-key", { authenticated: true }, async (f) => {
    const { page, requests } = f;
    f.unknownBooking();
    await page.getByRole("link", { name: "查看活动：日落音乐现场", exact: true }).click();
    await page.getByRole("button", { name: "申请购票", exact: true }).click();
    await hasText(page.locator("#toasts"), "尚未确认");
    await closeDialog(page);
    await page.getByRole("link", { name: "我的票夹", exact: true }).click();
    await page.getByRole("button", { name: /购票申请/ }).click();
    await hasText(page.locator("#ticket-content"), "提交结果待确认");
    await page.getByRole("button", { name: "重试原请求", exact: true }).click();
    await hasText(page.locator("#ticket-content"), "已受理");
    const attempts = requests.filter((request) => request.path === "/v1/booking-requests" && request.method === "POST");
    assert.equal(attempts.length, 2);
    assert.ok(attempts[0].headers["idempotency-key"], "Booking has an idempotency key");
    assert.equal(attempts[0].headers["idempotency-key"], attempts[1].headers["idempotency-key"], "Unknown submission retries the original key");
    assert.deepEqual(attempts[0].body, attempts[1].body, "Retry retains the original booking request");
  });
  await test("cancellation-requires-confirmation", { authenticated: true }, async ({ page, requests }) => {
    await page.getByRole("link", { name: "我的票夹", exact: true }).click();
    const cancellations = () => requests.filter((request) => request.method === "POST" && request.path.endsWith("/cancel"));
    for (const kind of ["order", "wait"]) {
      if (kind === "wait") await page.getByRole("button", { name: "我的候补", exact: true }).click();
      const button = kind === "order" ? "取消订单" : "取消候补";
      const expected = kind === "order" ? 0 : 1;
      await page.getByRole("button", { name: button, exact: true }).click();
      await hasText(page.locator("dialog"), kind === "order" ? "名额会释放" : "失去当前候补顺序");
      assert.equal(cancellations().length, expected, "Opening confirmation must not cancel anything");
      await page.getByRole("button", { name: kind === "order" ? "保留订单" : "继续候补", exact: true }).click();
      assert.equal(cancellations().length, expected, "Keeping the record must not issue cancellation");
      await page.getByRole("button", { name: button, exact: true }).click();
      await page.getByRole("button", { name: `确认${button}`, exact: true }).click();
      await hasText(page.locator("#ticket-content"), "已取消");
      assert.equal(cancellations().length, expected + 1, "Confirming sends exactly one cancellation");
    }
  });
  await test("tier-failure-can-retry", {}, async ({ page }) => {
    let first = true;
    await page.route("**/v1/sessions/session-1/ticket-tiers", async (route) => {
      if (!first) return route.fallback();
      first = false;
      return route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ code: "DEPENDENCY_UNAVAILABLE" }) });
    });
    await page.getByRole("link", { name: "查看活动：日落音乐现场", exact: true }).click();
    await page.getByRole("button", { name: "重新查询票档", exact: true }).waitFor();
    assert.equal(await page.locator("dialog .loading").count(), 0, "Failed ticket query must stop loading");
    await page.getByRole("button", { name: "重新查询票档", exact: true }).click();
    await hasText(page.locator("dialog"), "普通票");
    assert.ok(await page.getByRole("button", { name: "申请购票", exact: true }).isEnabled());
    await closeDialog(page);
  });
  await test("old-route-failure-cannot-replace-wallet", { authenticated: true }, async ({ page }) => {
    let release;
    await page.route("**/v1/events?*", async (route) => {
      await new Promise((resolve) => { release = resolve; });
      return route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ code: "DEPENDENCY_UNAVAILABLE" }) });
    });
    await page.getByRole("button", { name: "刷新活动", exact: true }).click();
    await until(() => !!release, "Refresh request should start");
    await page.getByRole("link", { name: "我的票夹", exact: true }).click();
    await hasText(page.locator("#ticket-content"), "日落音乐现场");
    const response = page.waitForResponse((response) => new URL(response.url()).pathname === "/v1/events");
    release();
    await response;
    await page.waitForTimeout(100);
    await hasText(page.locator("#ticket-content"), "日落音乐现场");
    assert.ok(!(await page.locator("#main").innerText()).includes("暂时没能加载内容"), "An old route error must not replace the active wallet");
  });
  await test("api-markup-is-rendered-as-text", { malicious: true }, async ({ page }) => {
    await hasText(page.locator("#main"), xss);
    await page.getByRole("link", { name: `查看活动：文字安全 ${xss}`, exact: true }).click();
    await hasText(page.locator("dialog"), xss);
    assert.equal(await page.locator("#main img, #modal img").count(), 0, "API HTML payload must render as text");
    await closeDialog(page);
  });
} finally {
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
  await writeFile(path.join(evidence, "results.json"), JSON.stringify({ type: "frontend-mocked-api", generatedAt: new Date().toISOString(), results }, null, 2) + "\n");
}
const failures = results.filter((result) => !result.passed);
console.log(`\n${results.length - failures.length}/${results.length} frontend behavior checks passed (mocked APIs).`);
process.exitCode = failures.length ? 1 : 0;
