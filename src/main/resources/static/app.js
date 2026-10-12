/* Same-origin, dependency-free local demo. Prices and business states always come from the API. */
"use strict";
const $ = (s, root = document) => root.querySelector(s);
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const idPath = (value) => encodeURIComponent(String(value));
const cash = (value) =>
  (Number(value || 0) / 100).toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
const date = (value) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        month: "long",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "时间待定";
const uid = () =>
  "web-" +
  (crypto.randomUUID
    ? crypto.randomUUID()
    : Array.from(crypto.getRandomValues(new Uint8Array(20)), (x) =>
        x.toString(16).padStart(2, "0"),
      ).join(""));
const state = {
  token: sessionStorage.getItem("live.token") || "",
  user: null,
  route: "discover",
  epoch: 0,
  events: [],
  eventPage: 1,
  eventNext: false,
  eventQuery: "",
  routeSequence: 0,
  ticketSequence: 0,
  adminSequence: 0,
  ticketRenderPending: false,
  orderPage: 1,
  orderNext: false,
  waitPage: 1,
  waitNext: false,
  orders: [],
  waits: [],
  tab: "orders",
  adminTab: "events",
  adminPage: 1,
  adminNext: false,
  adminEvents: [],
  failures: null,
  detail: null,
  busy: new Set(),
  polling: false,
  refreshing: false,
  loadSequence: 0,
  clockOffset: 0,
};
const labels = {
  HOLD: "待付款",
  PAID: "已付款 · 模拟",
  CANCELLED: "已取消",
  EXPIRED: "已过期",
  ACCEPTED: "已受理 · 处理中",
  SUCCEEDED: "已生成订单",
  REJECTED: "未能购票",
  PROCESSING_FAILED: "处理异常",
  WAITING: "候补排队中",
  ALLOCATED: "已补位 · 请查看订单",
  OPEN: "已发布",
  PAUSED: "暂停销售",
  DRAFT: "草稿",
  UNKNOWN: "提交结果待确认",
  PENDING: "模拟付款处理中",
  UNRESOLVED: "付款结果待入账",
  APPLIED: "已完成",
  COMPLETED: "已完成",
  REVIEW: "待处理",
  FAILED: "失败",
  NO_CHARGE: "未扣款",
  COMPENSATED: "已模拟退回",
};
const errors = {
  INVALID_PHONE: "请输入有效的 11 位中国大陆手机号。",
  INVALID_ARGUMENT: "请检查填写的内容和时间范围。",
  UNAUTHENTICATED: "登录已过期，请重新登录。",
  ADMIN_REQUIRED: "此功能需要管理员账号。",
  CODE_INVALID_OR_EXPIRED: "验证码错误或已过期，请重新获取。",
  CODE_ATTEMPTS_EXCEEDED: "验证码尝试次数过多，请稍后重新获取。",
  CODE_SEND_TOO_FREQUENT: "验证码刚刚发送过，请稍后再试。",
  CODE_SEND_RATE_LIMITED: "验证码请求较多，请稍后再试。",
  RESOURCE_NOT_FOUND: "没有找到这条记录，或你无权查看。",
  SESSION_NOT_OPEN: "当前场次尚未开售、已停售或暂停销售。",
  QUOTA_ALREADY_HELD: "你已有本场申请、订单或候补，请在我的票夹查看。",
  TICKETS_AVAILABLE: "还有可购余票，请尝试直接购票。",
  SOLD_OUT: "本票档已售罄，可以加入候补。",
  NO_INVENTORY: "本票档已售罄，可以加入候补。",
  WAITLIST_HAS_PRIORITY: "已有观众候补排队，请加入候补。",
  WAITLIST_PRIORITY: "已有观众候补排队，请加入候补。",
  WAITLIST_ALREADY_ALLOCATED: "已补位成功，请到订单中付款或取消订单。",
  ORDER_NOT_PAYABLE: "订单目前无法付款，请刷新查看状态。",
  ORDER_ALREADY_PAID: "订单已付款，不能取消。",
  PAYMENT_DEADLINE_EXCEEDED: "付款时间已结束，请刷新订单。",
  PAYMENT_IN_PROGRESS: "已有模拟付款正在处理，请等待结果。",
  IDEMPOTENCY_CONFLICT: "这次操作与原记录不一致，请刷新后查看已有结果。",
  ENTRY_RATE_LIMITED: "操作较快，请稍后使用原请求重试。",
  RATE_LIMITED: "操作较快，请稍后重试。",
  ACCEPTANCE_UNKNOWN: "提交结果尚未确认，请使用原请求重试。",
  PAYMENT_ACCEPTANCE_UNKNOWN: "付款受理结果尚未确认，请使用原请求重试。",
  CLOSE_RESULT_UNKNOWN: "取消结果尚未确认，请刷新订单或再次取消。",
  DEPENDENCY_UNAVAILABLE: "服务暂时不可用，请稍后刷新。",
  NOT_RECOVERABLE: "该记录当前不能恢复，请刷新查看。",
  PROCESS_DEADLINE_EXCEEDED: "已超过处理期限，不能重投。",
  PUBLISHED_FIELDS_FROZEN: "发布后不能更改场次时间、票价和容量。",
  TICKET_TIER_REQUIRED: "请先为草稿场次添加票档。",
  SESSION_STATE_CONFLICT: "场次状态已经变化，请刷新后重试。",
  SESSION_ALREADY_PUBLISHED: "场次已经发布，请刷新查看。",
  SIMULATOR_DISABLED: "当前环境没有开启模拟付款。",
  NETWORK: "连接中断或请求超时，结果尚未确认。请恢复连接后沿原操作重试。",
};
Object.assign(errors, {
  NO_TICKETS: "本票档已售罄，可以加入候补。",
  WAITLIST_REQUIRED: "已有观众候补排队，请加入候补。",
  PAID_CANCEL_DISABLED: "已付款订单不能直接取消。",
  BOOKING_BACKLOG_BUSY: "当前购票申请较多，请稍后使用原请求重试。",
  PROCESSING_FAILED: "这份申请正在等待异常处理，请保留原记录并稍后刷新。",
});
Object.assign(labels, {
  SUCCESS: "模拟付款成功",
  REVERSED: "已模拟退回",
  REFUNDING: "退款处理中",
  REFUNDED: "已退款 · 模拟",
});
Object.assign(errors, {
  REFUND_ALREADY_REQUESTED: "本订单已有退款申请，正在查询已有记录。",
  REFUND_WINDOW_CLOSED: "活动已经开始，不能再申请退款。",
  ORDER_NOT_REFUNDABLE: "当前订单不能申请退款，请刷新订单状态。",
  REFUND_IN_PROGRESS: "退款正在处理中，不能取消订单，请查看退款记录。",
  REFUND_ACCEPTANCE_UNKNOWN: "退款受理结果尚未确认，请沿原申请继续查询或重试。",
  REFUND_NOT_RECOVERABLE: "这笔退款当前不能恢复，请刷新查看最新结果。",
});
const pill = (status) =>
  `<span class="pill ${esc(status)}">${esc(labels[status] || status)}</span>`;
const button = (action, text, attrs = "", style = "") =>
  `<button type="button" class="button ${style}" data-action="${action}" ${attrs}>${text}</button>`;
const empty = (title, copy, action = "") =>
  `<div class="empty"><span class="empty-icon" aria-hidden="true">◎</span><h3>${esc(title)}</h3><p>${esc(copy)}</p>${action}</div>`;
function toast(message, bad = false) {
  const node = document.createElement("div");
  node.className = "toast" + (bad ? " error" : "");
  node.textContent = message;
  $("#toasts").append(node);
  setTimeout(() => node.remove(), bad ? 8500 : 5000);
}
function errorMessage(error) {
  return (
    errors[error.code] ||
    (error.status
      ? `操作未完成（${error.code || error.status}），请刷新后重试。`
      : error.message || "操作未完成，请重试。")
  );
}
function scopeKey() {
  return "live.operations." + state.user.id;
}
function readOps() {
  if (!state.user) return {};
  try {
    const data = JSON.parse(localStorage.getItem(scopeKey()) || "{}");
    return data && typeof data === "object" && !Array.isArray(data) ? data : {};
  } catch {
    return {};
  }
}
function saveOp(scope, value) {
  if (!state.user) return;
  const ops = readOps();
  if (value === null) delete ops[scope];
  else ops[scope] = value;
  localStorage.setItem(scopeKey(), JSON.stringify(ops));
}
function pendingOps() {
  return Object.entries(readOps()).filter(
    ([, op]) => op && op.kind === "booking" && !op.dismissed,
  );
}
function syncTime(data) {
  if (data && data.serverTime)
    state.clockOffset = new Date(data.serverTime).getTime() - Date.now();
}
async function api(path, { method = "GET", body, key } = {}) {
  const token = state.token;
  const headers = { Accept: "application/json" };
  if (token) headers.Authorization = "Bearer " + token;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (key) headers["Idempotency-Key"] = key;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: "no-store",
    });
    let data;
    try {
      data = await response.json();
    } catch {
      data = { code: "DEPENDENCY_UNAVAILABLE" };
    }
    if (!response.ok) {
      const error = Object.assign(new Error(data.message || data.code), {
        code: data.code,
        status: response.status,
        retryAfter: Number(response.headers.get("Retry-After") || 0),
      });
      if (response.status === 401 && token && state.token === token) {
        state.token = "";
        state.user = null;
        state.epoch++;
        sessionStorage.removeItem("live.token");
        renderAccount();
      }
      throw error;
    }
    if (token !== state.token)
      throw Object.assign(new Error("账号已切换，请重新操作。"), {
        code: "SESSION_CHANGED",
        status: 409,
      });
    syncTime(data);
    return data;
  } catch (error) {
    if (error.status) throw error;
    throw Object.assign(new Error(errors.NETWORK), { code: "NETWORK" });
  } finally {
    clearTimeout(timeout);
  }
}
async function locked(scope, element, fn) {
  if (state.busy.has(scope)) return;
  state.busy.add(scope);
  const original = element
    ? { html: element.innerHTML, disabled: element.disabled }
    : null;
  if (element) {
    element.disabled = true;
    element.setAttribute("aria-busy", "true");
    element.textContent = "处理中…";
  }
  try {
    await fn();
  } catch (error) {
    toast(errorMessage(error), true);
  } finally {
    state.busy.delete(scope);
    if (element?.isConnected) {
      element.disabled = original.disabled;
      element.removeAttribute("aria-busy");
      element.innerHTML = original.html;
    }
    if (scope === "send-code") tickCode();
  }
}
function signedIn() {
  if (state.user) return true;
  openLogin();
  return false;
}
function renderAccount() {
  $("#account").innerHTML = state.user
    ? `<div class="account"><span class="account-name">${esc(state.user.nickname)}${state.user.role === "ADMIN" ? " · 管理员" : ""}</span><button type="button" class="text-btn" data-action="logout">退出</button></div>`
    : button("login", "登录 / 注册", "", "small");
  $("#manage-link").hidden = state.user?.role !== "ADMIN";
}
// Every dialog owner gets a version; late responses cannot replace a newer dialog.
let modalVersion = 0;
let modalReturnFocus = null;
function modalIsCurrent(version) {
  return version === modalVersion && $("#modal").open;
}
function focusModal() {
  const target =
    $(
      "#modal-content input:not([type=hidden]):not([disabled]), #modal-content textarea:not([disabled])",
    ) || $("#modal-title");
  if (target) {
    if (target.id === "modal-title") target.tabIndex = -1;
    target.focus({ preventScroll: true });
  }
}
function showModal(html) {
  const modal = $("#modal");
  if (!modal.open) modalReturnFocus = document.activeElement;
  const version = ++modalVersion;
  state.detail = null;
  paymentViewId = null;
  refundView = null;
  $("#modal-content").innerHTML = html;
  if (!modal.open) modal.showModal();
  requestAnimationFrame(() => {
    if (modalIsCurrent(version)) focusModal();
  });
  return version;
}
function cleanModal() {
  modalVersion++;
  state.detail = null;
  paymentViewId = null;
  refundView = null;
}
function closeModal() {
  cleanModal();
  $("#modal").close();
  const target = modalReturnFocus?.isConnected ? modalReturnFocus : $("#main");
  target?.focus({ preventScroll: true });
  modalReturnFocus = null;
}
const loading = (copy) =>
  `<div class="loading loading-state" role="status"><span class="loading-dot" aria-hidden="true"></span>${esc(copy)}</div>`;
function modalError(version, title, error, action) {
  if (!modalIsCurrent(version)) return;
  $("#modal-content").innerHTML =
    `<h2 id="modal-title">${esc(title)}</h2><div class="error-state" role="alert"><p>${esc(errorMessage(error))}</p><div class="form-actions">${action}${button("close-modal", "关闭", "", "secondary")}</div></div>`;
  focusModal();
}
function formField(name, label, type = "text", value = "", extra = "") {
  return `<div class="field"><label for="field-${name}">${label}</label><input id="field-${name}" name="${name}" type="${type}" value="${esc(value)}" ${extra}></div>`;
}
function openLogin() {
  state.detail = null;
  showModal(
    `<h2 id="modal-title">登录，准备好出发</h2><p class="dialog-intro">手机号首次登录将自动创建账号。</p><form id="login-form">${formField("phone", "手机号", "tel", "", 'required pattern="1[3-9][0-9]{9}" inputmode="tel" autocomplete="tel" maxlength="11" placeholder="请输入手机号"')}<div class="field"><label for="field-code">验证码</label><div class="code-row"><input id="field-code" name="code" required pattern="[0-9]{6}" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6 位验证码">${button("send-code", "获取验证码", "", "secondary")}</div></div><div class="notice" id="code-help">本地体验不会发送真实短信。获取验证码后：<br><b>Mac：</b>在运行 Java 服务的终端中找到 <code>LOCAL_SIM_SMS</code>，核对手机号并读取验证码。<br><b>Windows：</b>双击项目目录中的“查看验证码.cmd”，输入同一手机号。<br>管理员演示手机号：13900000000；其他有效手机号为普通观众。</div><p class="error-text" id="login-error" role="alert"></p><div class="form-actions"><button class="button" type="submit">登录并继续</button></div></form>`,
  );
}
let codeUntil = 0;
let codePhone = "";
async function sendCode(el) {
  const input = $("#field-phone");
  if (!input || !input.reportValidity()) return;
  const phone = input.value;
  await locked("send-code", el, async () => {
    const r = await api("/v1/auth/code", { method: "POST", body: { phone } });
    codeUntil = Date.now() + Number(r.retryAfterSeconds || 60) * 1000;
    codePhone = phone;
    const help = $("#code-help");
    if (help && $("#field-phone")?.value === phone)
      help.textContent = `验证码已生成（${phone.slice(0, 3)}****${phone.slice(7)}），${r.expiresInSeconds || 120} 秒内有效。Mac 请在运行 Java 的终端中查找 LOCAL_SIM_SMS；Windows 可打开“查看验证码.cmd”，输入同一手机号取码。没有发送真实短信。`;
    tickCode();
  });
}
function tickCode() {
  const el = $('[data-action="send-code"]');
  if (!el) return;
  const seconds = Math.ceil((codeUntil - Date.now()) / 1000);
  const same = $("#field-phone")?.value === codePhone;
  el.disabled = (same && seconds > 0) || state.busy.has("send-code");
  el.textContent = same && seconds > 0 ? `${seconds} 秒后重发` : "获取验证码";
}
async function login(form) {
  const version = modalVersion;
  const submit = $('button[type="submit"]', form);
  await locked("login", submit, async () => {
    const input = new FormData(form);
    try {
      const result = await api("/v1/auth/login", {
        method: "POST",
        body: {
          phone: String(input.get("phone")),
          code: String(input.get("code")),
        },
      });
      state.token = result.token;
      state.user = result.user;
      state.epoch++;
      sessionStorage.setItem("live.token", state.token);
      state.orderPage = 1;
      state.waitPage = 1;
      renderAccount();
      if (modalIsCurrent(version)) closeModal();
      toast("登录成功，欢迎来到现场。");
      await route();
    } catch (error) {
      const errorNode = $("#login-error");
      if (form.isConnected && errorNode)
        errorNode.textContent = errorMessage(error);
      throw error;
    }
  });
}
async function logout(el) {
  await locked("logout", el, async () => {
    await api("/v1/auth/logout", { method: "POST" });
    state.token = "";
    state.user = null;
    state.epoch++;
    sessionStorage.removeItem("live.token");
    state.orders = [];
    state.waits = [];
    renderAccount();
    closeModal();
    location.hash = "discover";
    await route();
    toast("已退出登录。");
  });
}
function hero() {
  return `<section class="hero" aria-labelledby="hero-title"><div class="hero-copy"><p class="eyebrow">一张票，一次亲临现场</p><h1 id="hero-title">把喜欢，<br>留给现场。</h1><p>总有一场相遇，值得亲自赴约。<br>找到喜欢的活动，把下一个期待写进行程。</p><div class="hero-actions">${button("browse-events", "发现下一场", "", "hero-primary")}<a class="button hero-secondary" href="#tickets">打开我的票夹</a></div><div class="hero-bottom"><span><b></b> 每人每场一张</span><span><b></b> 售罄可候补</span><span><b></b> 票夹管理行程</span></div></div><div class="hero-art" aria-hidden="true"><div class="ticket-art hero-ticket"><span class="ticket-caption">来现场 · 入场凭期待</span><strong>我们<br>现场见</strong><span class="ticket-perforation"></span><span class="ticket-footnote">把这段时间，留给喜欢的事。</span></div></div></section>`;
}
function filteredEvents() {
  const query = state.eventQuery.trim().toLocaleLowerCase("zh-CN");
  return query
    ? state.events.filter((event) =>
        `${event.title || ""} ${event.venueText || ""}`
          .toLocaleLowerCase("zh-CN")
          .includes(query),
      )
    : state.events;
}
function eventCards(events) {
  return events
    .map((event) => {
      const tone = state.events.indexOf(event) % 4;
      const titleMark = Array.from(event.title || "现场")
        .slice(0, 2)
        .join("");
      return `<article class="event-card"><a class="event-card-link" href="#event-${esc(event.eventId)}" data-action="event" data-id="${esc(event.eventId)}" aria-label="查看活动：${esc(event.title)}"><div class="event-art tone-${tone}" aria-hidden="true"><span class="art-label">来现场</span><span class="art-title">${esc(titleMark)}</span><span class="art-perforation"></span><span class="art-stamp">期待与你相遇</span></div><div class="event-body"><h3>${esc(event.title)}</h3><p>${esc(event.description || "查看场次和票档，为喜欢的活动留一个位置。")}</p><div class="event-footer"><span class="venue">${esc(event.venueText || "地点详见活动详情")}</span><span class="circle-arrow" aria-hidden="true">↗</span></div></div></a></article>`;
    })
    .join("");
}
function renderEventResults() {
  const results = $("#event-results");
  if (!results) return;
  const events = filteredEvents();
  const query = state.eventQuery.trim();
  $("#event-count").textContent = query
    ? `本页匹配 ${events.length} 场，共 ${state.events.length} 场活动`
    : `本页 ${state.events.length} 场活动`;
  const clear = $('[data-action="clear-search"]');
  if (clear) clear.hidden = !state.eventQuery;
  results.innerHTML = events.length
    ? `<div class="cards">${eventCards(events)}</div>`
    : query
      ? empty(
          "本页没有匹配的活动",
          "试试其他活动名称或场馆；也可以清除搜索，查看本页全部活动。",
          button("clear-search", "清除搜索", "", "secondary"),
        )
      : empty(
          "下一场活动，正在准备",
          "目前没有已发布的活动，稍后再来看看。",
          state.user?.role === "ADMIN"
            ? '<a class="button" href="#manage">去发布活动</a>'
            : button("refresh", "刷新活动", "", "secondary"),
        );
}
function renderDiscover() {
  $("#main").innerHTML =
    hero() +
    `<section id="discover-section" class="discover-section" aria-labelledby="discover-title"><div class="section-head"><div><h2 id="discover-title" tabindex="-1">值得赴约</h2><p>浏览已发布的活动，选择想去的现场。</p></div>${button("refresh", "刷新活动", "", "secondary small")}</div><div class="discover-tools"><div class="search-field"><label for="event-search">搜索本页活动</label><div class="search-input-wrap"><input id="event-search" class="search-input" type="search" value="${esc(state.eventQuery)}" placeholder="活动名称或场馆" aria-describedby="search-help" autocomplete="off">${button("clear-search", "清除", state.eventQuery ? "" : "hidden", "text-btn search-clear")}</div><p id="search-help" class="tiny muted">仅搜索当前第 ${state.eventPage} 页已加载的活动。</p></div><p id="event-count" class="results-summary" role="status" aria-live="polite"></p></div><div id="event-results"></div>${pager("events", state.eventPage, state.eventNext)}</section><aside class="wallet-guide"><div><h2>行程，都在票夹里</h2><p>购票申请、待付款订单和候补进度，随时回来查看。</p></div><a class="button secondary" href="#tickets">查看我的票夹</a></aside>`;
  renderEventResults();
}
function pager(kind, page, next) {
  if (page === 1 && !next) return "";
  return `<div class="pager">${button("page", "← 上一页", `data-kind="${kind}" data-page="${page - 1}" ${page === 1 ? "disabled" : ""}`, "secondary small")}<span>第 ${page} 页</span>${button("page", "下一页 →", `data-kind="${kind}" data-page="${page + 1}" ${!next ? "disabled" : ""}`, "secondary small")}</div>`;
}
async function loadDiscover() {
  const seq = ++state.loadSequence;
  const result = await api(`/v1/events?page=${state.eventPage}&size=12`);
  if (state.route !== "discover" || seq !== state.loadSequence) return;
  state.events = result.items;
  state.eventNext = result.hasNext;
  renderDiscover();
}
async function openEvent(eventId) {
  const version = showModal(
    `<h2 id="modal-title">活动详情</h2>${loading("正在查看活动…")}`,
  );
  try {
    const event = await api("/v1/events/" + idPath(eventId));
    if (!modalIsCurrent(version)) return;
    state.detail = {
      event,
      session: null,
      tiers: null,
      error: null,
      seq: 0,
      version,
    };
    renderDetail();
    if (event.sessions?.length)
      await selectSession(event.sessions[0].sessionId);
  } catch (error) {
    modalError(
      version,
      "活动暂时无法加载",
      error,
      button("event", "重新加载", `data-id="${esc(eventId)}"`),
    );
  }
}
function renderDetail() {
  const detail = state.detail;
  if (!detail || !modalIsCurrent(detail.version)) return;
  const event = detail.event;
  const sessions = event.sessions || [];
  const titleHadFocus = document.activeElement?.id === "modal-title";
  const activeSession = document.activeElement?.matches(
    '[data-action="session"]',
  )
    ? document.activeElement.dataset.id
    : null;
  let tierHtml;
  if (!sessions.length)
    tierHtml = empty(
      "场次尚未公布",
      "这场活动还没有可查看的场次，请稍后再来。",
    );
  else if (detail.error)
    tierHtml = `<div class="error-state" role="alert"><p>${esc(errorMessage(detail.error))}</p>${button("session", "重新查询票档", `data-id="${esc(detail.session)}"`, "secondary")}</div>`;
  else if (!detail.tiers) tierHtml = loading("正在查询票档…");
  else if (!detail.tiers.items.length)
    tierHtml = empty("还没有票档", "该场次暂未公布票档，请稍后再来。");
  else
    tierHtml = detail.tiers.items
      .map((tier) => {
        const session = detail.tiers;
        const now = Date.now() + state.clockOffset;
        const available =
          session.state === "OPEN" &&
          new Date(session.bookingOpenAt).getTime() <= now &&
          new Date(session.bookingCloseAt).getTime() > now;
        return `<div class="tier"><div class="row"><div><h4>${esc(tier.name)}</h4><span class="tiny muted">${esc(tier.areaLabel || "不分区域")} · 参考余量 ${esc(tier.referenceAvailable)}</span></div><div class="tier-price"><small>¥</small> ${cash(tier.unitPriceCent)}</div></div><div class="order-actions">${button("book", "申请购票", `data-session="${esc(session.sessionId)}" data-tier="${esc(tier.tierId)}" ${available ? "" : "disabled"}`)}${button("join-wait", "加入候补", `data-session="${esc(session.sessionId)}" data-tier="${esc(tier.tierId)}" ${available ? "" : "disabled"}`, "secondary")}${!available ? `<span class="tiny muted">${session.state === "PAUSED" ? "暂停销售" : now < new Date(session.bookingOpenAt).getTime() ? "尚未开售" : "已停售"}</span>` : ""}</div></div>`;
      })
      .join("");
  $("#modal-content").innerHTML =
    `<h2 id="modal-title" tabindex="-1">${esc(event.title)}</h2><p class="dialog-intro">${esc(event.venueText || "地点待公布")}</p><div class="dialog-description">${esc(event.description || "选一个时间，我们现场见。")}</div>${sessions.length ? `<h3 class="dialog-section-title">选择场次</h3><div class="session-options" role="group" aria-label="选择活动场次">${sessions.map((session) => `<button type="button" class="session-option ${detail.session === session.sessionId ? "active" : ""}" data-action="session" data-id="${esc(session.sessionId)}" aria-pressed="${detail.session === session.sessionId}">${esc(date(session.startAt))} ${pill(session.state)}<small>售票 ${esc(date(session.bookingOpenAt))} — ${esc(date(session.bookingCloseAt))}</small></button>`).join("")}</div><h3 class="dialog-section-title">选择票档 <span class="tiny muted">每人每场限一张</span></h3>` : ""}<div class="stack">${tierHtml}</div>${sessions.length ? '<div class="notice">余量仅供参考。申请受理后，请到票夹查看出票结果，并在付款期限内完成模拟付款。<br>售罄或已有候补队列时可加入候补，按加入先后补位；候补成功不会自动付款。</div>' : ""}`;
  if (activeSession) {
    const target = Array.from(
      document.querySelectorAll('[data-action="session"]'),
    ).find((node) => node.dataset.id === activeSession);
    target?.focus({ preventScroll: true });
  } else if (titleHadFocus) {
    $("#modal-title")?.focus({ preventScroll: true });
  }
}
async function selectSession(sessionId) {
  const detail = state.detail;
  if (!detail) return;
  detail.session = sessionId;
  detail.tiers = null;
  detail.error = null;
  const sequence = ++detail.seq;
  renderDetail();
  try {
    const tiers = await api(
      "/v1/sessions/" + idPath(sessionId) + "/ticket-tiers",
    );
    if (
      state.detail !== detail ||
      sequence !== detail.seq ||
      !modalIsCurrent(detail.version)
    )
      return;
    detail.tiers = tiers;
    renderDetail();
  } catch (error) {
    if (
      state.detail !== detail ||
      sequence !== detail.seq ||
      !modalIsCurrent(detail.version)
    )
      return;
    detail.error = error;
    renderDetail();
  }
}
function purchaseContext(sessionId, tierId) {
  const d = state.detail;
  return {
    sessionId,
    tierId,
    title: d?.event.title || `场次 ${sessionId}`,
    tierName:
      d?.tiers?.items.find((t) => t.tierId === tierId)?.name ||
      `票档 ${tierId}`,
  };
}
async function purchase(kind, sessionId, tierId, el, existingScope) {
  if (!signedIn()) return;
  const epoch = state.epoch;
  const scope = existingScope || `${kind}:${sessionId}:${tierId}`;
  await locked(scope, el, async () => {
    let op = readOps()[scope];
    if (op?.requestId && kind === "booking") {
      const current = await api("/v1/booking-requests/" + idPath(op.requestId));
      if (state.epoch !== epoch) return;
      if (current.status === "ACCEPTED") {
        closeModal();
        state.tab = "requests";
        location.hash = "tickets";
        await route();
        return;
      }
      if (
        current.order &&
        !["CANCELLED", "EXPIRED", "REFUNDED"].includes(
          current.order.orderStatus,
        )
      ) {
        closeModal();
        state.tab = "orders";
        location.hash = "tickets";
        await route();
        return;
      }
      if (current.status === "PROCESSING_FAILED")
        throw Object.assign(new Error(), { code: "PROCESSING_FAILED" });
      op = null;
    }
    if (op?.waitlistId && kind === "wait") {
      const current = await api(
        "/v1/waitlist-entries/" + idPath(op.waitlistId),
      );
      if (state.epoch !== epoch) return;
      let active = current.status === "WAITING";
      if (current.status === "ALLOCATED" && current.orderId) {
        const order = await api("/v1/orders/" + idPath(current.orderId));
        if (state.epoch !== epoch) return;
        active = !["CANCELLED", "EXPIRED", "REFUNDED"].includes(
          order.orderStatus,
        );
      }
      if (active) {
        closeModal();
        state.tab = "waits";
        location.hash = "tickets";
        await route();
        return;
      }
      op = null;
    }
    if (!op) {
      op = {
        ...purchaseContext(sessionId, tierId),
        kind,
        key: uid(),
        status: "UNKNOWN",
        createdAt: new Date().toISOString(),
      };
      saveOp(scope, op);
    }
    try {
      const result = await api(
        kind === "booking" ? "/v1/booking-requests" : "/v1/waitlist-entries",
        {
          method: "POST",
          key: op.key,
          body: { sessionId: op.sessionId, tierId: op.tierId, quantity: 1 },
        },
      );
      if (state.epoch !== epoch) return;
      Object.assign(op, result, { status: result.status || "ACCEPTED" });
      saveOp(scope, op);
      closeModal();
      state.tab = kind === "booking" ? "requests" : "waits";
      location.hash = "tickets";
      await route();
      toast(
        kind === "booking"
          ? "购票申请已受理，请等待出票结果。"
          : "已加入候补，补位后请及时付款。",
      );
    } catch (error) {
      if (
        state.epoch === epoch &&
        error.status &&
        error.status < 500 &&
        error.status !== 429
      ) {
        saveOp(scope, null);
      }
      throw error;
    }
  });
}
function renderTickets() {
  if (!state.user) {
    $("#main").innerHTML =
      `<div class="page-title"><h1>我的票夹</h1></div>${empty("登录后，查看你的现场计划", "订单、购票申请和候补记录，都在这里。", button("login", "登录 / 注册"))}`;
    return;
  }
  const ops = pendingOps();
  const pending = ops.filter(([, o]) =>
    ["UNKNOWN", "ACCEPTED"].includes(o.status),
  ).length;
  $("#main").innerHTML =
    `<div class="section-head page-heading"><div class="page-title"><h1>我的票夹</h1><p>查看订单、购票申请与候补进度。</p></div>${button("refresh", "刷新状态", "", "secondary small")}</div><div class="tabs-line" role="group" aria-label="票夹分类">${[
      ["orders", "我的订单"],
      ["requests", `购票申请${pending ? " · " + pending : ""}`],
      ["waits", "我的候补"],
    ]
      .map(
        ([k, t]) =>
          `<button type="button" class="filter-btn ${state.tab === k ? "active" : ""}" data-action="ticket-tab" data-tab="${k}" aria-pressed="${state.tab === k}">${t}</button>`,
      )
      .join(
        "",
      )}</div><div id="ticket-content">${state.tab === "orders" ? renderOrders() : state.tab === "waits" ? renderWaits() : renderRequests(ops)}</div><p class="transition-note" id="refresh-status">页面每 4 秒同步当前账号的状态；隐藏页面时暂停。</p>`;
  tickDeadlines();
}
function refundEligible(order) {
  return (
    order.orderStatus === "PAID" &&
    order.refundEligible !== false &&
    new Date(order.refundDeadline || order.startAt).getTime() >
      Date.now() + state.clockOffset
  );
}
function orderStatusNote(order) {
  if (order.orderStatus === "HOLD")
    return `<p class="deadline" data-deadline="${esc(order.payExpiresAt)}">请于 ${esc(date(order.payExpiresAt))} 前付款</p><p class="status-note">付款到期后系统会释放名额。取消或付款后，以刷新状态为准。</p>`;
  if (order.orderStatus === "PAID")
    return `<p class="status-note">模拟付款已完成 · ${esc(date(order.paidAt))} · 无真实资金交易<br>${refundEligible(order) ? `活动开始前可申请全额模拟退款 · 截止 ${esc(date(order.refundDeadline || order.startAt))}` : "活动已开始或此订单当前不支持退款。"}</p>`;
  if (order.orderStatus === "REFUNDING")
    return '<p class="status-note">退款申请正在处理，名额仍为此订单保留。退款成功后才会释放名额；遇到异常由管理员继续处理。</p>';
  if (order.orderStatus === "REFUNDED")
    return `<p class="status-note">全额模拟退款已完成${order.refundedAt ? " · " + esc(date(order.refundedAt)) : ""}，名额已释放。开售期间可重新购票或加入候补。原付款记录仍保留，无真实资金交易。</p>`;
  if (order.orderStatus === "EXPIRED")
    return '<p class="status-note">付款期限已过，名额已释放。</p>';
  if (order.orderStatus === "CANCELLED")
    return '<p class="status-note">订单已取消，名额已释放。</p>';
  return '<p class="status-note">请以最新订单状态为准。</p>';
}
function renderOrders() {
  return state.orders.length
    ? state.orders
        .map((order) => {
          const orderId = esc(order.orderId);
          const pay = order.currentAttemptId;
          const savedRefund = readOps()["refund:" + order.orderId];
          return `<article class="panel order-card"><div class="row"><div><p class="inline-label">订单 ${orderId}</p><h3>${esc(order.eventTitle || `场次 ${order.sessionId}`)}</h3><div class="order-meta"><span>${esc(date(order.startAt))}</span><span>${esc(order.tierName || `票档 ${order.tierId}`)} · ${esc(order.quantity || 1)} 张</span>${order.waitlistId ? "<span>候补补位</span>" : ""}</div></div>${pill(order.orderStatus)}</div><div class="order-divider"></div><div class="row wrap"><div class="money"><small>¥</small>${cash(order.payableCent)}</div><div class="order-actions">${order.orderStatus === "HOLD" ? `${button("pay", pay ? "查看 / 继续模拟付款" : "模拟付款", `data-id="${orderId}"`)}${button("cancel-order", "取消订单", `data-id="${orderId}"`, "secondary")}` : ""}${order.currentRefundId ? button("refund-detail", "退款记录", `data-id="${esc(order.currentRefundId)}"`, "secondary") : refundEligible(order) || savedRefund?.kind === "refund" ? button("refund", savedRefund ? "继续退款申请" : "申请模拟退款", `data-id="${orderId}"`, "secondary") : ""}${pay ? button("payment-detail", "付款记录", `data-id="${esc(pay)}"`, "secondary small") : ""}</div></div>${orderStatusNote(order)}</article>`;
        })
        .join("") + pager("orders", state.orderPage, state.orderNext)
    : empty(
        "这里将收好你的门票",
        "去发现活动，选择喜欢的场次。申请成功后，待付款订单会出现在这里。",
        '<a class="button" href="#discover">去发现活动</a>',
      );
}
function renderRequests(ops) {
  return ops.length
    ? ops
        .slice()
        .reverse()
        .map(
          ([scope, o]) =>
            `<article class="panel"><div class="row"><div><p class="inline-label">${o.requestId ? "申请 " + esc(o.requestId) : "提交结果待确认"}</p><h3 style="margin:0 0 8px">${esc(o.title)}</h3><span class="tiny muted">${esc(o.tierName)} · ${esc(date(o.acceptedAt || o.createdAt))}</span></div>${pill(o.status)}</div><p class="status-note">${o.status === "UNKNOWN" ? "上次提交的响应尚未确认。请点击原请求重试，页面会继续使用同一份申请标识。" : o.status === "ACCEPTED" ? "申请已受理，正在分配名额。受理不代表出票或付款成功。" : o.status === "SUCCEEDED" ? "已生成订单，请到我的订单查看最新付款或关闭状态。" : esc(errors[o.reasonCode] || o.reasonCode || "该申请尚未成功生成订单，请刷新查看。")}</p><div class="order-actions" style="margin-top:14px">${o.status === "UNKNOWN" ? button("retry-book", "重试原请求", `data-scope="${esc(scope)}"`) : o.status === "SUCCEEDED" ? button("ticket-tab", "查看我的订单", 'data-tab="orders"', "secondary small") : ""}${!["UNKNOWN", "ACCEPTED"].includes(o.status) ? button("dismiss-request", "收起这条记录", `data-scope="${esc(scope)}"`, "secondary small") : ""}</div></article>`,
        )
        .join("")
    : empty(
        "还没有购票申请",
        "这里记录本浏览器提交的申请。登录其他设备时，已生成订单仍可在我的订单中找到。",
      );
}
function renderKnownWaits() {
  return state.waits.length
    ? `<div class="notice">候补按加入时间先后补位。补位后生成待付款订单，需手动完成模拟付款；订单逾期会释放名额。</div>` +
        state.waits
          .map(
            (w) =>
              `<article class="panel"><div class="row"><div><p class="inline-label">候补 ${esc(w.waitlistId)}</p><h3 style="margin:0 0 8px">${esc(waitName(w))}</h3><div class="order-meta"><span>场次 ${esc(w.sessionId)} · 票档 ${esc(w.tierId)}</span><span>加入于 ${esc(date(w.createdAt))}</span></div></div>${pill(w.status)}</div><p class="status-note">${w.status === "WAITING" ? "有名额释放时将按候补顺序处理，无需重复加入。" : w.status === "ALLOCATED" ? "补位记录已关联订单。实际是否待付款、已付款或已过期，请查看订单。" : w.status === "EXPIRED" ? "售票已结束，本次候补已过期。" : "你已取消本次候补。"}</p><div class="order-actions" style="margin-top:14px">${w.status === "WAITING" ? button("cancel-wait", "取消候补", `data-id="${esc(w.waitlistId)}"`, "secondary small") : ""}${w.orderId ? button("show-order", "查看补位订单", `data-id="${esc(w.orderId)}"`, "secondary") : ""}</div></article>`,
          )
          .join("") +
        pager("waits", state.waitPage, state.waitNext)
    : empty(
        "还没有候补记录",
        "心仪的票档售罄时，可以加入候补。有名额释放后，系统会按先后顺序补位。",
      );
}
function waitName(w) {
  return (
    Object.values(readOps()).find((o) => o.waitlistId === w.waitlistId)
      ?.title || "活动候补"
  );
}
function renderWaits() {
  const unresolved = Object.entries(readOps()).filter(
    ([, o]) => o.kind === "wait" && !o.waitlistId,
  );
  return (
    unresolved
      .map(
        ([scope, o]) =>
          `<article class="panel"><div class="row"><strong>${esc(o.title)}</strong>${pill("UNKNOWN")}</div><p class="status-note">上次加入候补的结果尚未确认。请沿原请求重试，系统不会为同一请求重复加入。</p><div class="form-actions">${button("retry-wait", "重试原候补请求", `data-scope="${esc(scope)}"`, "secondary")}</div></article>`,
      )
      .join("") + renderKnownWaits()
  );
}
async function loadTickets(silent = false) {
  if (!state.user) {
    if (state.route === "tickets") renderTickets();
    return;
  }
  if (silent && state.refreshing) return;
  const sequence = ++state.ticketSequence;
  const epoch = state.epoch;
  const routeSequence = state.routeSequence;
  state.refreshing = true;
  try {
    const [orders, waits] = await Promise.all([
      api(`/v1/orders?page=${state.orderPage}&size=12`),
      api(`/v1/waitlist-entries?page=${state.waitPage}&size=12`),
    ]);
    if (
      epoch !== state.epoch ||
      sequence !== state.ticketSequence ||
      routeSequence !== state.routeSequence
    )
      return;
    state.orders = orders.items;
    state.orderNext = orders.hasNext;
    state.waits = waits.items;
    state.waitNext = waits.hasNext;
    if (state.route === "tickets") {
      if (!silent) renderTickets();
      else renderTicketContent(true);
    }
  } finally {
    if (sequence === state.ticketSequence) state.refreshing = false;
  }
}
function renderTicketContent(background = false) {
  const content = $("#ticket-content");
  if (state.route !== "tickets" || !content) return;
  // Background refreshes wait until the user leaves a focused control or closes a dialog.
  if (
    background &&
    (content.contains(document.activeElement) || $("#modal").open)
  ) {
    state.ticketRenderPending = true;
    return;
  }
  state.ticketRenderPending = false;
  content.innerHTML =
    state.tab === "orders"
      ? renderOrders()
      : state.tab === "waits"
        ? renderWaits()
        : renderRequests(pendingOps());
  const badge = $('[data-action="ticket-tab"][data-tab="requests"]');
  if (badge) {
    const count = pendingOps().filter(([, op]) =>
      ["UNKNOWN", "ACCEPTED"].includes(op.status),
    ).length;
    badge.textContent = "购票申请" + (count ? " · " + count : "");
  }
  tickDeadlines();
}
function flushTicketRender() {
  if (state.ticketRenderPending) renderTicketContent(true);
}
function tickDeadlines() {
  document.querySelectorAll("[data-deadline]").forEach((el) => {
    const seconds = Math.ceil(
      (new Date(el.dataset.deadline).getTime() -
        Date.now() -
        state.clockOffset) /
        1000,
    );
    el.textContent =
      seconds > 0
        ? `付款剩余 ${Math.floor(seconds / 60)} 分 ${String(seconds % 60).padStart(2, "0")} 秒 · 截止 ${date(el.dataset.deadline)}`
        : "付款时间已到，等待服务器确认订单状态…";
  });
}
async function poll() {
  if (state.polling || !state.user || document.hidden || state.busy.size)
    return;
  state.polling = true;
  const epoch = state.epoch;
  try {
    const ops = pendingOps().filter(
      ([, o]) =>
        o.requestId && ["ACCEPTED", "PROCESSING_FAILED"].includes(o.status),
    );
    for (const [scope, op] of ops.slice(0, 20)) {
      try {
        const r = await api("/v1/booking-requests/" + idPath(op.requestId));
        if (epoch !== state.epoch) return;
        saveOp(scope, { ...op, ...r });
        if (op.status === "ACCEPTED" && r.status === "SUCCEEDED")
          toast("出票结果已更新，请到我的订单付款。");
      } catch (e) {
        if (e.status === 404 && epoch === state.epoch)
          saveOp(scope, {
            ...op,
            status: "PROCESSING_FAILED",
            reasonCode: "RESOURCE_NOT_FOUND",
          });
        else throw e;
      }
    }
    if (state.route === "tickets") await loadTickets(true);
    await pollRefund();
    if (state.route === "tickets" && $("#refresh-status"))
      $("#refresh-status").textContent =
        "状态已同步于 " +
        new Date().toLocaleTimeString("zh-CN", { hour12: false }) +
        (state.ticketRenderPending
          ? " · 操作结束后更新列表"
          : " · 每 4 秒自动刷新");
  } catch (e) {
    if ($("#refresh-status"))
      $("#refresh-status").textContent = "自动刷新暂未成功：" + errorMessage(e);
    if (!state.user && state.route === "tickets") renderTickets();
  } finally {
    state.polling = false;
  }
}
async function pay(orderId, el) {
  if (!signedIn()) return;
  await locked("pay:" + orderId, el, async () => {
    const epoch = state.epoch;
    const order = await api("/v1/orders/" + idPath(orderId));
    if (order.orderStatus !== "HOLD") {
      toast("订单状态已更新，请查看。");
      await loadTickets();
      return;
    }
    let scope = "payment:" + orderId;
    let op = readOps()[scope];
    if (order.currentAttemptId) {
      const current = await api(
        "/v1/payment-attempts/" + idPath(order.currentAttemptId),
      );
      if (
        !(
          current.resultStatus === "FAILED" &&
          current.settlementStatus === "NO_CHARGE"
        )
      ) {
        await paymentDetail(order.currentAttemptId);
        return;
      }
      if (op?.attemptId === current.attemptId) {
        saveOp(scope, null);
        op = null;
      }
    }
    if (!op) {
      op = { kind: "payment", key: uid(), orderId };
      saveOp(scope, op);
    }
    try {
      const r = await api(
        "/v1/orders/" + idPath(orderId) + "/payment-attempts",
        { method: "POST", body: {}, key: op.key },
      );
      if (epoch !== state.epoch) return;
      saveOp(scope, { ...op, ...r });
      toast("模拟付款已受理，正在确认结果。");
      await paymentDetail(r.attemptId);
      await loadTickets();
    } catch (e) {
      if (
        epoch === state.epoch &&
        e.status &&
        e.status < 500 &&
        e.status !== 429
      )
        saveOp(scope, null);
      throw e;
    }
  });
}
let paymentViewId = null;
let refundView = null;
const refundPill = (status) =>
  `<span class="pill ${status === "PENDING" ? "REFUNDING" : status === "SUCCEEDED" ? "REFUNDED" : esc(status)}">${esc({ PENDING: "退款处理中", SUCCEEDED: "已退款 · 模拟", REVIEW: "退款待处理" }[status] || status)}</span>`;
async function openRefund(orderId) {
  if (!signedIn()) return;
  const version = showModal(
    `<h2 id="modal-title">申请模拟退款</h2>${loading("正在查询订单…")}`,
  );
  try {
    const order = await api("/v1/orders/" + idPath(orderId));
    if (!modalIsCurrent(version)) return;
    if (order.currentRefundId) {
      await refundDetail(order.currentRefundId);
      return;
    }
    const op = readOps()["refund:" + orderId];
    if (!op && !refundEligible(order)) {
      toast(
        order.orderStatus === "PAID"
          ? errors.REFUND_WINDOW_CLOSED
          : errors.ORDER_NOT_REFUNDABLE,
        true,
      );
      closeModal();
      await loadTickets();
      return;
    }
    showModal(
      `<h2 id="modal-title">申请全额模拟退款</h2><p class="dialog-intro">${esc(order.eventTitle || "活动门票")} · 订单 ${esc(orderId)}</p><div class="panel"><div class="row"><span class="small-copy">本次全额退款</span><strong class="money"><small>¥</small>${cash(order.payableCent)}</strong></div></div><div class="notice warn">退款成功后，这张票将失效并释放名额，排队中的观众可能取得该名额。提交后请等待处理完成。<br>这是本地模拟退款，无真实资金收付。</div>${op ? '<div class="notice">上次申请的结果尚未确认。请继续查询或沿原申请重试；退款原因保持不变。</div>' : ""}<form id="refund-form" data-order-id="${esc(orderId)}"><div class="field"><label for="field-refund-reason">退款原因</label><textarea id="field-refund-reason" name="reason" required maxlength="200" ${op ? "readonly" : ""}>${esc(op?.reason || "计划有变")}</textarea><small>仅活动开始前可申请全额退款，最多 200 字。</small></div><p class="error-text" id="refund-error" role="alert"></p><div class="form-actions">${button("close-modal", "暂不退款", "", "secondary")}<button class="button danger" type="submit">${op ? "查询 / 重试原申请" : "确认申请模拟退款"}</button></div></form>`,
    );
  } catch (error) {
    modalError(
      version,
      "订单暂时无法加载",
      error,
      button("refund", "重新查询", `data-id="${esc(orderId)}"`),
    );
  }
}
async function submitRefund(form) {
  const orderId = form.dataset.orderId;
  const epoch = state.epoch;
  const scope = "refund:" + orderId;
  const reason = String(new FormData(form).get("reason") || "").trim();
  if (!reason) {
    $("#refund-error").textContent = "请填写退款原因。";
    return;
  }
  await locked(scope, $('button[type="submit"]', form), async () => {
    let op = readOps()[scope];
    if (op && op.reason !== reason)
      throw Object.assign(new Error(), { code: "IDEMPOTENCY_CONFLICT" });
    if (!op) {
      op = {
        kind: "refund",
        orderId,
        reason,
        key: uid(),
        status: "UNKNOWN",
        createdAt: new Date().toISOString(),
      };
      saveOp(scope, op);
    }
    try {
      // Query before a repeat submission: an interrupted response may already have created a refund.
      const order = await api("/v1/orders/" + idPath(orderId));
      if (epoch !== state.epoch) return;
      if (order.currentRefundId) {
        saveOp(scope, { ...op, refundId: order.currentRefundId });
        await refundDetail(order.currentRefundId);
        await loadTickets();
        return;
      }
      const result = await api("/v1/orders/" + idPath(orderId) + "/refunds", {
        method: "POST",
        body: { reason: op.reason },
        key: op.key,
      });
      if (epoch !== state.epoch) return;
      saveOp(scope, { ...op, ...result });
      toast("退款申请已受理，请等待处理结果。");
      await refundDetail(result.refundId);
      await loadTickets();
    } catch (error) {
      if (epoch !== state.epoch) return;
      if (error.code === "REFUND_ALREADY_REQUESTED") {
        const order = await api("/v1/orders/" + idPath(orderId));
        if (order.currentRefundId) {
          saveOp(scope, { ...op, refundId: order.currentRefundId });
          await refundDetail(order.currentRefundId);
          await loadTickets();
          return;
        }
      } else if (
        !readOps()[scope]?.refundId &&
        error.status &&
        error.status < 500 &&
        error.status !== 429 &&
        error.code !== "IDEMPOTENCY_CONFLICT"
      )
        saveOp(scope, null);
      const errorNode = $("#refund-error");
      if (errorNode) errorNode.textContent = errorMessage(error);
      throw error;
    }
  });
}
function refundDetailHtml(refund, admin) {
  const note =
    refund.status === "SUCCEEDED"
      ? "全额模拟退款已完成，门票已失效，名额已释放。原付款和本次退款记录均会保留。"
      : refund.status === "REVIEW"
        ? "退款尚未完成，名额仍由原订单占用。管理员处理后会继续更新结果，无需重复申请。"
        : "退款申请正在处理中。当前名额仍为原订单保留，只有退款成功后才会释放。";
  return `<h2 id="modal-title">模拟退款记录</h2><p class="dialog-intro break">退款 ${esc(refund.refundId)} · 订单 ${esc(refund.orderId)}</p><div class="notice">本地模拟退款，无真实资金收付。提交受理不代表退款完成。</div><div class="panel"><div class="row"><span class="small-copy">退款状态</span>${refundPill(refund.status)}</div><div class="row" style="margin-top:18px"><span class="small-copy">订单状态</span>${pill(refund.orderStatus)}</div><div class="order-divider"></div><div class="row"><span class="small-copy">全额退款金额</span><strong class="money"><small>¥</small>${cash(refund.amountCent)}</strong></div><div class="order-divider"></div><div class="small-copy break">退款原因：${esc(refund.reason)}<br>申请时间：${esc(date(refund.requestedAt))}${refund.completedAt ? "<br>完成时间：" + esc(date(refund.completedAt)) : ""}</div></div><p class="status-note">${note}</p>${refund.status === "REVIEW" && admin ? `<div class="notice warn break">处理原因：${esc(refund.reviewReason || "请检查异常记录后继续处理。")}</div>` : ""}<div class="form-actions">${button("refund-detail", "刷新退款状态", `data-id="${esc(refund.refundId)}" ${admin ? 'data-admin="true"' : ""}`, "secondary")}${button("close-modal", admin ? "返回异常处理" : "返回票夹")}</div><p class="transition-note">此窗口每 4 秒同步退款状态。</p>`;
}
async function refundDetail(refundId, admin = false) {
  if (admin && state.user?.role !== "ADMIN")
    throw Object.assign(new Error(), { code: "ADMIN_REQUIRED" });
  const version = showModal(
    `<h2 id="modal-title">模拟退款记录</h2>${loading("正在查询退款记录…")}`,
  );
  try {
    const result = await api(
      (admin ? "/v1/admin/refunds/" : "/v1/refunds/") + idPath(refundId),
    );
    if (!modalIsCurrent(version)) return;
    $("#modal-content").innerHTML = refundDetailHtml(result, admin);
    refundView = { refundId, admin, signature: JSON.stringify(result) };
    focusModal();
  } catch (error) {
    modalError(
      version,
      "退款记录暂时无法加载",
      error,
      button(
        "refund-detail",
        "重新查询",
        `data-id="${esc(refundId)}" ${admin ? 'data-admin="true"' : ""}`,
      ),
    );
  }
}
async function pollRefund() {
  const view = refundView;
  if (!view || !$("#modal").open) return;
  const result = await api(
    (view.admin ? "/v1/admin/refunds/" : "/v1/refunds/") +
      idPath(view.refundId),
  );
  if (refundView !== view || !$("#modal").open) return;
  const signature = JSON.stringify(result);
  if (signature !== view.signature) {
    const focused = $("#modal-content").contains(document.activeElement)
      ? document.activeElement
      : null;
    const action = focused?.dataset.action;
    $("#modal-content").innerHTML = refundDetailHtml(result, view.admin);
    view.signature = signature;
    if (action)
      Array.from($("#modal-content").querySelectorAll("[data-action]"))
        .find((node) => node.dataset.action === action)
        ?.focus({ preventScroll: true });
  }
}
async function paymentDetail(attemptId) {
  const version = showModal(
    `<h2 id="modal-title">模拟付款记录</h2>${loading("正在查询付款记录…")}`,
  );
  try {
    const r = await api("/v1/payment-attempts/" + idPath(attemptId));
    if (!modalIsCurrent(version)) return;
    paymentViewId = attemptId;
    $("#modal-content").innerHTML =
      `<h2 id="modal-title">模拟付款记录</h2><p class="dialog-intro">付款 ${esc(r.attemptId)} · 订单 ${esc(r.orderId)}</p><div class="notice">本地模拟支付，无真实扣款、无真实退款。</div><div class="panel"><div class="row"><span class="small-copy">订单状态</span>${pill(r.orderStatus)}</div><div class="row" style="margin-top:18px"><span class="small-copy">付款结果</span>${pill(r.resultStatus)}</div><div class="row" style="margin-top:18px"><span class="small-copy">入账结果</span>${pill(r.settlementStatus)}</div><div class="order-divider"></div><div class="row"><span class="small-copy">模拟金额</span><strong class="money"><small>¥</small>${cash(r.amountCent)}</strong></div></div><p class="status-note">${r.orderStatus === "REFUNDED" ? "这次原付款已完成，之后已全额模拟退款。请查看订单的退款记录。" : r.orderStatus === "REFUNDING" ? "这次原付款已完成，订单的退款正在处理中。请查看退款记录确认最终结果。" : r.orderStatus === "PAID" ? "订单已确认模拟付款成功。" : r.resultStatus === "FAILED" && r.settlementStatus === "NO_CHARGE" ? "这次模拟付款未成功。订单仍在付款期内时，可关闭此窗口并再次模拟付款。" : ["COMPENSATED", "REVERSED"].includes(r.settlementStatus) ? "付款结果到达时订单已关闭，系统已完成模拟退回。" : r.settlementStatus === "REVIEW" ? "此笔记录需要管理员处理，请稍后刷新。" : "请刷新确认最终结果。付款受理或结果生成都不等于订单已付款。"}</p><div class="form-actions">${button("payment-detail", "刷新付款状态", `data-id="${esc(attemptId)}"`, "secondary")}${button("close-modal", "返回票夹")}</div>`;
    focusModal();
  } catch (error) {
    modalError(
      version,
      "付款记录暂时无法加载",
      error,
      button("payment-detail", "重新查询", `data-id="${esc(attemptId)}"`),
    );
  }
}
function confirmCancellation(kind, id) {
  const order = kind === "order";
  const title = order ? "确定取消这张订单？" : "确定退出候补？";
  const copy = order
    ? "取消后，本订单保留的名额会释放，排队中的观众可能取得该名额。想再次参加时，需要重新申请购票。"
    : "取消后，你将失去当前候补顺序。再次加入时，会从新的加入时间开始排队。";
  showModal(
    `<h2 id="modal-title">${title}</h2><p class="dialog-intro">${copy}</p><div class="form-actions">${button("close-modal", order ? "保留订单" : "继续候补", "", "secondary")}${button(order ? "cancel-order-confirm" : "cancel-wait-confirm", order ? "确认取消订单" : "确认取消候补", `data-id="${esc(id)}"`, "danger")}</div>`,
  );
}
async function cancelOrder(orderId, el) {
  const version = modalVersion;
  await locked("cancel:" + orderId, el, async () => {
    const r = await api("/v1/orders/" + idPath(orderId) + "/cancel", {
      method: "POST",
      body: {},
    });
    toast(
      r.orderStatus === "CANCELLED"
        ? "订单已取消，名额已释放。"
        : "订单状态：" + (labels[r.orderStatus] || r.orderStatus),
    );
    if (modalIsCurrent(version)) closeModal();
    await loadTickets();
  });
}
async function cancelWait(waitlistId, el) {
  const version = modalVersion;
  await locked("cancel-wait:" + waitlistId, el, async () => {
    const r = await api(
      "/v1/waitlist-entries/" + idPath(waitlistId) + "/cancel",
      { method: "POST", body: {} },
    );
    toast(
      r.status === "CANCELLED"
        ? "已取消候补。"
        : "候补状态：" + (labels[r.status] || r.status),
    );
    if (modalIsCurrent(version)) closeModal();
    await loadTickets();
  });
}
function renderManage() {
  if (state.user?.role !== "ADMIN") {
    $("#main").innerHTML =
      '<div class="page-title"><h1>活动管理</h1></div>' +
      empty(
        "需要管理员账号",
        "请使用已配置的管理员手机号登录。",
        state.user ? "" : button("login", "管理员登录"),
      );
    return;
  }
  $("#main").innerHTML =
    `<div class="section-head page-heading"><div class="page-title"><h1>活动管理</h1><p>管理活动场次、票档与需要处理的记录。</p></div><div class="page-toolbar">${button("refresh", "刷新", "", "secondary small")}${state.adminTab === "events" ? button("create-event", "＋ 创建活动", "", "small") : ""}</div></div><div class="tabs-line" role="group" aria-label="管理分类"><button type="button" class="filter-btn ${state.adminTab === "events" ? "active" : ""}" data-action="admin-tab" data-tab="events" aria-pressed="${state.adminTab === "events"}">活动与场次</button><button type="button" class="filter-btn ${state.adminTab === "failures" ? "active" : ""}" data-action="admin-tab" data-tab="failures" aria-pressed="${state.adminTab === "failures"}">异常处理</button></div>${state.adminTab === "events" ? renderAdminEvents() : renderFailures() + renderRefundFailures()}`;
}
function renderAdminEvents() {
  return state.adminEvents.length
    ? state.adminEvents
        .map(
          (e) =>
            `<article class="panel admin-event"><div class="row wrap"><div><p class="inline-label">活动 ${esc(e.eventId)}</p><h3 style="margin:0 0 8px">${esc(e.title)}</h3><span class="tiny muted">${esc(e.venueText || "未填写地点")}</span></div><div class="order-actions">${button("edit-event", "编辑活动", `data-id="${esc(e.eventId)}"`, "secondary small")}${button("create-session", "＋ 添加场次", `data-id="${esc(e.eventId)}"`, "small")}</div></div>${e.sessions.length ? e.sessions.map((s) => `<section class="admin-session"><div class="row wrap"><div><b class="small-copy">${esc(date(s.startAt))}</b> ${pill(s.state)}<p class="tiny muted">场次 ${esc(s.sessionId)} · 售票 ${esc(date(s.bookingOpenAt))} 至 ${esc(date(s.bookingCloseAt))}</p></div><div class="order-actions">${s.state === "DRAFT" ? `${button("edit-session", "编辑时间", `data-id="${esc(s.sessionId)}"`, "secondary small")}${button("create-tier", "添加票档", `data-id="${esc(s.sessionId)}"`, "secondary small")}${button("session-state", "发布场次", `data-id="${esc(s.sessionId)}" data-state="publish"`, "small")}` : button("session-state", s.state === "OPEN" ? "暂停销售" : "恢复销售", `data-id="${esc(s.sessionId)}" data-state="${s.state === "OPEN" ? "pause" : "resume"}"`, "secondary small")}</div></div><div class="admin-tiers">${s.ticketTiers.length ? s.ticketTiers.map((t) => `<div class="admin-tier"><span>${esc(t.name)} · ${esc(t.areaLabel || "不分区")} <span class="muted">/ 参考 ${esc(t.referenceAvailable)} / ${esc(t.capacity)} 张</span></span><span>¥ ${cash(t.unitPriceCent)} ${button("edit-tier", "编辑", `data-id="${esc(t.tierId)}"`, "secondary small")}</span></div>`).join("") : '<p class="tiny muted">还没有票档。添加票档后即可发布。</p>'}</div></section>`).join("") : '<p class="status-note">活动尚无场次，添加场次和票档后发布，观众即可在发现活动中看到。</p>'}</article>`,
        )
        .join("") + pager("admin", state.adminPage, state.adminNext)
    : empty(
        "第一场活动，从这里开始",
        "创建活动，补充场次和票档，然后发布。",
        button("create-event", "＋ 创建活动"),
      );
}
function renderFailures() {
  const data = state.failures;
  if (!data) return '<div class="loading">正在查看待处理记录…</div>';
  const b = data.backlog || {};
  return `<div class="metric-grid"><div class="metric"><strong>${esc(b.acceptedRequests || 0)}</strong><span>正在处理的购票申请</span></div><div class="metric"><strong>${esc(b.oldestAcceptedSeconds || 0)}<small class="tiny"> 秒</small></strong><span>最早申请等待时长</span></div><div class="metric"><strong>${esc(data.invalidMessageCount || 0)}</strong><span>已隔离异常消息</span></div></div><div class="notice">恢复将按原记录继续处理。请填写实际原因，每次操作会留存记录。是否完成以刷新结果为准。</div>${data.items.length ? data.items.map((e) => `<article class="panel"><div class="row"><div><b class="small-copy">${esc({ BOOKING_REQUESTED: "购票处理", PAYMENT_RESULT_RECORDED: "模拟付款入账", CATALOG_INVALIDATE: "活动展示更新" }[e.eventType] || e.eventType)}</b><p class="tiny muted">记录 ${esc(e.eventId)} · 已尝试 ${esc(e.attempts)} 次</p></div>${pill("PROCESSING_FAILED")}</div><p class="small-copy break">失败原因：${esc(e.lastError || "未提供")}</p><form class="retry-form" data-event-id="${esc(e.eventId)}"><label class="skip" for="reason-${esc(e.eventId)}">恢复原因</label><input id="reason-${esc(e.eventId)}" name="reason" required maxlength="200" placeholder="填写处理原因（必填）"><button class="button small" type="submit">恢复处理</button></form></article>`).join("") + pager("failures", state.adminPage, data.hasNext) : empty("暂时没有待恢复记录", "系统持续处理申请和付款结果，异常记录会显示在这里。")}`;
}
function renderRefundFailures() {
  const refunds = state.refundReviews || [];
  return `<div class="section-head"><div><h2>退款待处理</h2><p>遇到异常的退款会保留原订单名额，继续处理成功后才释放。</p></div></div>${
    refunds.length
      ? refunds
          .map((refund) => {
            const op = readOps()["refund-retry:" + refund.refundId];
            return `<article class="panel"><div class="row wrap"><div><b class="small-copy">全额模拟退款 · ¥ ${cash(refund.amountCent)}</b><p class="tiny muted break">退款 ${esc(refund.refundId)} · 订单 ${esc(refund.orderId)}</p></div>${refundPill(refund.status)}</div><p class="small-copy break">退款原因：${esc(refund.reason)}<br>处理原因：${esc(refund.reviewReason || "未提供")}</p><div class="order-actions">${button("refund-detail", "查看退款记录", `data-id="${esc(refund.refundId)}" data-admin="true"`, "secondary small")}</div><form class="retry-form refund-retry-form" data-refund-id="${esc(refund.refundId)}"><label class="skip" for="refund-retry-reason-${esc(refund.refundId)}">退款恢复原因</label><input id="refund-retry-reason-${esc(refund.refundId)}" name="reason" required maxlength="200" placeholder="填写处理原因（必填）" value="${esc(op?.reason || "")}" ${op && !op.confirmed ? "readonly" : ""}><button class="button small" type="submit">${op && !op.confirmed ? "继续原恢复请求" : "继续处理退款"}</button></form></article>`;
          })
          .join("")
      : empty(
          "暂时没有待处理退款",
          "正常退款由系统自动处理，需要继续处理的退款会显示在这里。",
        )
  }`;
}
async function loadManage() {
  if (state.user?.role !== "ADMIN") {
    renderManage();
    return;
  }
  const epoch = state.epoch;
  const tab = state.adminTab;
  const sequence = ++state.adminSequence;
  const routeSequence = state.routeSequence;
  if (tab === "events") {
    const result = await api(
      `/v1/admin/events?page=${state.adminPage}&size=10`,
    );
    if (
      epoch !== state.epoch ||
      state.route !== "manage" ||
      state.adminTab !== tab ||
      sequence !== state.adminSequence ||
      routeSequence !== state.routeSequence
    )
      return;
    state.adminEvents = result.items;
    state.adminNext = result.hasNext;
  } else {
    const [failures, refunds] = await Promise.all([
      api(`/v1/admin/processing-failures?page=${state.adminPage}&size=10`),
      api("/v1/admin/refunds?status=REVIEW"),
    ]);
    if (
      epoch !== state.epoch ||
      state.route !== "manage" ||
      state.adminTab !== tab ||
      sequence !== state.adminSequence ||
      routeSequence !== state.routeSequence
    )
      return;
    state.failures = failures;
    state.refundReviews = refunds.items;
  }
  renderManage();
}
async function retryRefund(form) {
  const refundId = form.dataset.refundId;
  const scope = "refund-retry:" + refundId;
  const epoch = state.epoch;
  const reason = String(new FormData(form).get("reason") || "").trim();
  if (!reason) return;
  await locked(scope, $('button[type="submit"]', form), async () => {
    let op = readOps()[scope];
    if (op && !op.confirmed && op.reason !== reason)
      throw Object.assign(new Error(), { code: "IDEMPOTENCY_CONFLICT" });
    try {
      const current = await api("/v1/admin/refunds/" + idPath(refundId));
      if (epoch !== state.epoch) return;
      if (current.status !== "REVIEW") {
        if (op) saveOp(scope, { ...op, confirmed: true });
        await refundDetail(refundId, true);
        await loadManage();
        return;
      }
      // A later REVIEW after a confirmed recovery is a new attempt. Unknown requests retain their key.
      if (!op || op.confirmed) {
        op = {
          kind: "refund-retry",
          refundId,
          reason,
          key: uid(),
          confirmed: false,
        };
        saveOp(scope, op);
      }
      const result = await api(
        "/v1/admin/refunds/" + idPath(refundId) + "/retry",
        { method: "POST", body: { reason: op.reason }, key: op.key },
      );
      if (epoch !== state.epoch) return;
      saveOp(scope, { ...op, ...result, confirmed: true });
      toast("退款恢复请求已受理，请等待处理结果。");
      await refundDetail(refundId, true);
      await loadManage();
    } catch (error) {
      if (
        epoch === state.epoch &&
        error.status &&
        error.status < 500 &&
        error.status !== 429 &&
        error.code !== "IDEMPOTENCY_CONFLICT"
      )
        saveOp(scope, null);
      throw error;
    }
  });
}
function findSession(id) {
  return state.adminEvents
    .flatMap((e) => e.sessions)
    .find((s) => s.sessionId === id);
}
function findTier(id) {
  return state.adminEvents
    .flatMap((e) => e.sessions)
    .flatMap((s) => s.ticketTiers)
    .find((t) => t.tierId === id);
}
const localDate = (value) => {
  const d = new Date(value);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};
function adminForm(kind, id) {
  let title,
    fields,
    url,
    method = "POST";
  if (kind === "event") {
    const e = id ? state.adminEvents.find((x) => x.eventId === id) : {};
    title = id ? "编辑活动" : "创建一场活动";
    url = "/v1/admin/events" + (id ? "/" + idPath(id) : "");
    if (id) method = "PATCH";
    fields =
      formField(
        "title",
        "活动名称",
        "text",
        e.title || "",
        'required maxlength="128" placeholder="例如：周末音乐现场"',
      ) +
      formField(
        "venueText",
        "活动地点",
        "text",
        e.venueText || "",
        'maxlength="255" placeholder="场馆或详细地址"',
      ) +
      `<div class="field"><label for="field-description">活动介绍</label><textarea id="field-description" name="description" maxlength="5000" placeholder="介绍这场活动，告诉观众值得期待的内容。">${esc(e.description || "")}</textarea></div>`;
  } else if (kind === "session" || kind === "session-edit") {
    const edit = kind === "session-edit";
    const s = edit ? findSession(id) : {};
    const now = Date.now();
    title = edit ? "编辑场次时间" : "添加一个场次";
    url = "/v1/admin/sessions" + (edit ? "/" + idPath(id) : "");
    if (edit) method = "PATCH";
    fields =
      (edit ? "" : `<input type="hidden" name="eventId" value="${esc(id)}">`) +
      formField(
        "startAt",
        "活动开始时间",
        "datetime-local",
        localDate(s.startAt || now + 86400000),
        "required",
      ) +
      '<div class="form-grid">' +
      formField(
        "bookingOpenAt",
        "开售时间",
        "datetime-local",
        localDate(s.bookingOpenAt || now - 300000),
        "required",
      ) +
      formField(
        "bookingCloseAt",
        "停售时间",
        "datetime-local",
        localDate(s.bookingCloseAt || now + 72000000),
        "required",
      ) +
      '</div><div class="notice">停售时间需晚于开售时间，且不晚于活动开始时间。新场次先保存为草稿，添加票档后可发布。</div>';
  } else {
    const edit = kind === "tier-edit";
    const t = edit ? findTier(id) : {};
    const session = edit ? findSession(t.sessionId) : findSession(id);
    const frozen = session.state !== "DRAFT";
    title = edit ? "编辑票档" : "添加一个票档";
    url = "/v1/admin/ticket-tiers" + (edit ? "/" + idPath(id) : "");
    if (edit) method = "PATCH";
    fields =
      (edit
        ? ""
        : `<input type="hidden" name="sessionId" value="${esc(id)}">`) +
      formField(
        "name",
        "票档名称",
        "text",
        t.name || "",
        'required maxlength="64" placeholder="例如：普通票"',
      ) +
      formField(
        "areaLabel",
        "区域说明",
        "text",
        t.areaLabel || "",
        'maxlength="64" placeholder="例如：一层看台"',
      ) +
      '<div class="form-grid">' +
      formField(
        "capacity",
        "票数",
        "number",
        t.capacity || 50,
        `required min="1" max="1000000" step="1" ${frozen ? "disabled" : ""}`,
      ) +
      formField(
        "priceYuan",
        "单价（元）",
        "number",
        t.unitPriceCent ? cash(t.unitPriceCent).replace(/,/g, "") : "99.00",
        `required min="0.01" max="10000" step="0.01" ${frozen ? "disabled" : ""}`,
      ) +
      "</div>" +
      (frozen
        ? '<div class="notice">此场次已发布，可调整票档名称和区域说明；价格和容量已固定。</div>'
        : "");
  }
  showModal(
    `<h2 id="modal-title">${title}</h2><form id="admin-form" data-kind="${kind}" data-url="${url}" data-method="${method}">${fields}<p class="error-text" id="admin-error" role="alert"></p><div class="form-actions">${button("close-modal", "取消", "", "secondary")}<button class="button" type="submit">保存${kind === "session" ? "草稿" : ""}</button></div></form>`,
  );
}
async function saveAdmin(form) {
  const version = modalVersion;
  await locked("admin-save", $('button[type="submit"]', form), async () => {
    const body = Object.fromEntries(new FormData(form));
    for (const k of ["startAt", "bookingOpenAt", "bookingCloseAt"])
      if (body[k]) body[k] = new Date(body[k]).toISOString();
    if (body.capacity) body.capacity = Number(body.capacity);
    if (body.priceYuan) {
      body.unitPriceCent = Math.round(Number(body.priceYuan) * 100);
      delete body.priceYuan;
    }
    try {
      await api(form.dataset.url, { method: form.dataset.method, body });
      if (modalIsCurrent(version)) closeModal();
      toast("已保存。");
      await loadManage();
    } catch (e) {
      const errorNode = $("#admin-error");
      if (form.isConnected && errorNode)
        errorNode.textContent = errorMessage(e);
      throw e;
    }
  });
}
async function sessionState(id, action, el) {
  await locked("session-state:" + id, el, async () => {
    await api(`/v1/admin/sessions/${idPath(id)}/${action}`, {
      method: "POST",
      body: {},
    });
    toast(
      action === "publish"
        ? "场次已发布，观众现在可以查看。"
        : action === "pause"
          ? "已暂停销售。"
          : "已恢复销售。",
    );
    await loadManage();
  });
}
async function retryProcessing(form) {
  const id = form.dataset.eventId;
  const reason = String(new FormData(form).get("reason")).trim();
  if (!reason) return;
  await locked("recover:" + id, $("button", form), async () => {
    const scope = "recover:" + id;
    let op = readOps()[scope];
    if (op && op.reason !== reason)
      throw Object.assign(
        new Error("上次恢复请求结果尚未确认，请使用相同原因重试。"),
        { code: "IDEMPOTENCY_CONFLICT" },
      );
    if (!op) {
      op = { kind: "recover", key: uid(), reason };
      saveOp(scope, op);
    }
    const r = await api(`/v1/admin/processing-failures/${idPath(id)}/retry`, {
      method: "POST",
      body: { reason },
      key: op.key,
    });
    saveOp(scope, { ...op, ...r });
    if (r.operationId) {
      showModal(
        `<h2 id="modal-title">恢复请求已受理</h2><p class="dialog-intro">系统正在继续处理原模拟付款记录。</p><div id="recovery-state">${pill(r.status || "ACCEPTED")}</div><div class="form-actions">${button("recovery-state", "刷新处理状态", `data-id="${esc(r.operationId)}"`, "secondary")}${button("close-modal", "返回")}</div>`,
      );
    } else toast("恢复请求已受理，请刷新查看处理结果。");
    await loadManage();
  });
}
async function recoveryState(id) {
  const version = modalVersion;
  const result = await api(
    "/v1/admin/processing-failures/operations/" + idPath(id),
  );
  if (!modalIsCurrent(version) || !$("#recovery-state")) return;
  $("#recovery-state").innerHTML =
    `${pill(result.status)}<p class="status-note">${esc(result.result || result.resultCode || result.outcome || "请以最新状态确认是否完成。")}</p>`;
}
async function showOrder(id) {
  const o = await api("/v1/orders/" + idPath(id));
  state.tab = "orders";
  state.orderPage = 1;
  closeModal();
  location.hash = "tickets";
  await route();
  if (!state.orders.some((row) => row.orderId === id)) {
    state.orders.unshift(o);
    renderTickets();
  }
}
async function route() {
  const target = ["tickets", "manage"].includes(location.hash.slice(1))
    ? location.hash.slice(1)
    : "discover";
  const changed = target !== state.route;
  state.route = target;
  const sequence = ++state.routeSequence;
  if (changed) {
    if ($("#modal").open) closeModal();
    $("#main").innerHTML = loading(
      target === "tickets"
        ? "正在整理你的票夹…"
        : target === "manage"
          ? "正在加载活动管理…"
          : "正在加载活动…",
    );
  }
  $("#main").setAttribute("aria-busy", "true");
  document.querySelectorAll("[data-nav]").forEach((link) => {
    const active = link.dataset.nav === target;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  try {
    if (target === "discover") await loadDiscover();
    else if (target === "tickets") await loadTickets();
    else await loadManage();
  } catch (error) {
    if (sequence !== state.routeSequence) return;
    $("#main").innerHTML =
      `<div class="error-state">${empty("暂时没能加载内容", errorMessage(error), button("refresh", "重新加载"))}</div>`;
  } finally {
    if (sequence === state.routeSequence)
      $("#main").removeAttribute("aria-busy");
  }
}
document.addEventListener("click", (event) => {
  const el = event.target.closest("[data-action]");
  if (!el || el.disabled) return;
  event.preventDefault();
  const a = el.dataset.action;
  const action = async () => {
    switch (a) {
      case "browse-events":
        $("#discover-title")?.focus({ preventScroll: true });
        $("#discover-section")?.scrollIntoView({
          behavior: window.matchMedia("(prefers-reduced-motion: reduce)")
            .matches
            ? "auto"
            : "smooth",
          block: "start",
        });
        break;
      case "clear-search":
        state.eventQuery = "";
        if ($("#event-search")) {
          $("#event-search").value = "";
          $("#event-search").focus();
        }
        renderEventResults();
        break;
      case "login":
        openLogin();
        break;
      case "logout":
        await logout(el);
        break;
      case "close-modal":
        closeModal();
        break;
      case "send-code":
        await sendCode(el);
        break;
      case "refresh":
        await locked("refresh", el, async () => {
          await poll();
          await route();
        });
        break;
      case "event":
        await openEvent(el.dataset.id);
        break;
      case "session":
        await selectSession(el.dataset.id);
        break;
      case "book":
        await purchase("booking", el.dataset.session, el.dataset.tier, el);
        break;
      case "join-wait":
        await purchase("wait", el.dataset.session, el.dataset.tier, el);
        break;
      case "retry-book":
      case "retry-wait": {
        const o = readOps()[el.dataset.scope];
        if (o)
          await purchase(o.kind, o.sessionId, o.tierId, el, el.dataset.scope);
        break;
      }
      case "dismiss-request": {
        const op = readOps()[el.dataset.scope];
        if (op) saveOp(el.dataset.scope, { ...op, dismissed: true });
        renderTicketContent();
        break;
      }
      case "ticket-tab":
        state.tab = el.dataset.tab;
        renderTickets();
        Array.from(document.querySelectorAll('[data-action="ticket-tab"]'))
          .find((node) => node.dataset.tab === state.tab)
          ?.focus({ preventScroll: true });
        break;
      case "pay":
        await pay(el.dataset.id, el);
        break;
      case "refund":
        await locked("refund-form:" + el.dataset.id, el, () =>
          openRefund(el.dataset.id),
        );
        break;
      case "refund-detail":
        await locked("refund-view", el, () =>
          refundDetail(el.dataset.id, el.dataset.admin === "true"),
        );
        break;
      case "cancel-order":
        confirmCancellation("order", el.dataset.id);
        break;
      case "cancel-order-confirm":
        await cancelOrder(el.dataset.id, el);
        break;
      case "cancel-wait":
        confirmCancellation("wait", el.dataset.id);
        break;
      case "cancel-wait-confirm":
        await cancelWait(el.dataset.id, el);
        break;
      case "payment-detail":
        await locked("payment-view", el, () => paymentDetail(el.dataset.id));
        break;
      case "show-order":
        await showOrder(el.dataset.id);
        break;
      case "admin-tab":
        state.adminTab = el.dataset.tab;
        state.adminPage = 1;
        await route();
        Array.from(document.querySelectorAll('[data-action="admin-tab"]'))
          .find((node) => node.dataset.tab === state.adminTab)
          ?.focus({ preventScroll: true });
        break;
      case "create-event":
        adminForm("event");
        break;
      case "edit-event":
        adminForm("event", el.dataset.id);
        break;
      case "create-session":
        adminForm("session", el.dataset.id);
        break;
      case "edit-session":
        adminForm("session-edit", el.dataset.id);
        break;
      case "create-tier":
        adminForm("tier", el.dataset.id);
        break;
      case "edit-tier":
        adminForm("tier-edit", el.dataset.id);
        break;
      case "session-state":
        await sessionState(el.dataset.id, el.dataset.state, el);
        break;
      case "recovery-state":
        await recoveryState(el.dataset.id);
        break;
      case "page": {
        const page = Math.max(1, Number(el.dataset.page));
        const kind = el.dataset.kind;
        if (kind === "events") {
          state.eventPage = page;
          state.eventQuery = "";
        } else if (kind === "orders") state.orderPage = page;
        else if (kind === "waits") state.waitPage = page;
        else state.adminPage = page;
        await route();
        break;
      }
    }
  };
  action().catch((e) => toast(errorMessage(e), true));
});
document.addEventListener("submit", (event) => {
  const form = event.target;
  event.preventDefault();
  if (form.id === "login-form")
    login(form).catch((e) => toast(errorMessage(e), true));
  else if (form.id === "admin-form")
    saveAdmin(form).catch((e) => toast(errorMessage(e), true));
  else if (form.id === "refund-form")
    submitRefund(form).catch((e) => toast(errorMessage(e), true));
  else if (form.matches(".refund-retry-form"))
    retryRefund(form).catch((e) => toast(errorMessage(e), true));
  else if (form.matches(".retry-form"))
    retryProcessing(form).catch((e) => toast(errorMessage(e), true));
});
$("#modal").addEventListener("click", (e) => {
  if (e.target === $("#modal")) {
    const rect = $("#modal").getBoundingClientRect();
    if (
      e.clientX < rect.left ||
      e.clientX > rect.right ||
      e.clientY < rect.top ||
      e.clientY > rect.bottom
    )
      closeModal();
  }
});
$("#modal").addEventListener("cancel", (event) => {
  event.preventDefault();
  closeModal();
});
$("#modal").addEventListener("keydown", (event) => {
  if (event.key !== "Tab" || !$("#modal").open) return;
  const controls = Array.from(
    $("#modal").querySelectorAll(
      'a[href], button, input:not([type="hidden"]), select, textarea, [tabindex]',
    ),
  ).filter(
    (control) =>
      control.tabIndex >= 0 &&
      !control.disabled &&
      !control.closest("[hidden], [inert]") &&
      control.getClientRects().length > 0,
  );
  const first = controls[0];
  const last = controls.at(-1);
  const active = document.activeElement;
  if (!first) {
    event.preventDefault();
    focusModal();
  } else if (!controls.includes(active)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (event.shiftKey && active === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
});
$("#modal").addEventListener("close", () => {
  if (!$("#modal").open) {
    cleanModal();
    flushTicketRender();
  }
});
document.addEventListener("input", (event) => {
  if (event.target.id === "field-phone") tickCode();
  if (event.target.id === "event-search") {
    state.eventQuery = event.target.value;
    renderEventResults();
  }
});
document.addEventListener("focusout", () => {
  if (state.ticketRenderPending) setTimeout(flushTicketRender, 0);
});
window.addEventListener("hashchange", () => route());
window.addEventListener("storage", (event) => {
  if (state.user && event.key === scopeKey()) renderTicketContent(true);
});
setInterval(() => {
  tickCode();
  tickDeadlines();
}, 1000);
setInterval(() => poll(), 4000);
(async () => {
  renderAccount();
  $("#main").innerHTML = loading("正在打开下一场期待…");
  if (state.token) {
    try {
      state.user = await api("/v1/auth/me");
      renderAccount();
    } catch (e) {
      if (e.status !== 401) toast(errorMessage(e), true);
    }
  }
  await route();
  await poll();
})();
