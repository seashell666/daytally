/* 日刻 · 数据中台 — 本地页面逻辑（无外部依赖） */
(() => {
  "use strict";

  const ORDER = ["muscle", "push", "horse", "pullup", "custom"];
  // 公开版本不带任何真实口令：口令由用户第一次在「连接设置」里填，存在本机 config.json。
  const DEFAULT_TOKEN = "";
  const FALLBACK = {
    muscle: { name: "肌肉重量", unit: "公斤" },
    push: { name: "俯卧撑", unit: "个" },
    horse: { name: "蹲马步", unit: "分钟" },
    pullup: { name: "引体向上", unit: "个" },
    custom: { name: "自定义", unit: "次" },
  };
  const TONE = {
    muscle: { accent: "#D2505C", bar: "rgba(210,80,92,.46)", glow: "rgba(210,80,92,.16)", ring: "#D2505C" },
    push: { accent: "#D2505C", bar: "rgba(210,80,92,.42)", glow: "rgba(210,80,92,.12)", ring: "#D2505C" },
    horse: { accent: "#8B76D9", bar: "rgba(139,118,217,.42)", glow: "rgba(139,118,217,.13)", ring: "#8B76D9" },
    pullup: { accent: "#6E58BE", bar: "rgba(110,88,190,.40)", glow: "rgba(110,88,190,.12)", ring: "#6E58BE" },
    custom: { accent: "#B43644", bar: "rgba(180,54,68,.38)", glow: "rgba(180,54,68,.11)", ring: "#B43644" },
  };
  const PAGE_META = {
    overview: { title: "今天，刻下多少", sub: "在电脑上看见树莓派里的日刻数据" },
    today: { title: "今天的刻度", sub: "每一个项目，今天都走到哪儿了" },
    trend: { title: "把日子连成线", sub: "过去 30 天的起伏，比单个数字更有意思" },
    bodyfat: { title: "体脂率，单独一条线", sub: "只读树莓派里的真实测量；有几次，就画几次" },
    projects: { title: "五个固定项目", sub: "单击看详情，双击编辑项目内容" },
    fun: { title: "今天的小趣事", sub: "数据自己长出来的看点，也可以自己记一条" },
    data: { title: "数据管理", sub: "清记录，不动项目；先把边界说清楚" },
  };
  const params = new URLSearchParams(window.location.search);
  const requestedPage = params.get("page") || "";
  const requestedMetric = params.get("metric") || "";
  const state = {
    page: Object.prototype.hasOwnProperty.call(PAGE_META, requestedPage) ? requestedPage : "overview",
    activeMetric: ORDER.includes(requestedMetric) ? requestedMetric : "muscle",
    animatePage: false,
    renderSig: "",
    config: null,
    snapshot: null,
    notes: [],
    online: false,
    loadError: "",
    lastUpdated: null,
    loading: false,
    modalOpen: false,
    settingsPrompted: false,
    clickTimer: null,
    bodyFatRange: "30",
    bodyFatDate: "",
  };

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  function esc(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, (ch) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  function dateKey(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  }

  function parseDay(key) {
    const [y, m, d] = String(key).split("-").map(Number);
    return new Date(y, (m || 1) - 1, d || 1);
  }

  function shiftDay(key, delta) {
    const d = parseDay(key);
    d.setDate(d.getDate() + delta);
    return dateKey(d);
  }

  function todayKey() { return dateKey(new Date()); }

  function lastDays(n, end = todayKey()) {
    const list = [];
    for (let i = n - 1; i >= 0; i -= 1) list.push(shiftDay(end, -i));
    return list;
  }

  function weekdayShort(key) {
    return ["日", "一", "二", "三", "四", "五", "六"][parseDay(key).getDay()];
  }

  function fmtDate(key) {
    const d = parseDay(key);
    return `${d.getMonth() + 1} 月 ${d.getDate()} 日`;
  }

  function fmtDateFull(key) {
    return `${fmtDate(key)} · 周${weekdayShort(key)}`;
  }

  function fmtTime(ts) {
    if (!ts) return "还没刷新";
    const d = new Date(ts);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }

  function fmtNumber(value, digits = 0) {
    if (value == null || Number.isNaN(Number(value))) return "--";
    return Number(value).toLocaleString("zh-CN", {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
  }

  function metric(key) {
    const m = state.snapshot && state.snapshot.metrics ? state.snapshot.metrics[key] : null;
    const fb = FALLBACK[key] || { name: key, unit: "" };
    return {
      key,
      name: (m && m.name) || fb.name,
      unit: (m && m.unit) || fb.unit,
      per: Number((m && m.per) || 0),
      goal: Number((m && m.goal) || 0),
      maxGroups: Number((m && m.maxGroups) || 0),
      data: (m && m.data && typeof m.data === "object") ? m.data : {},
    };
  }

  function rawValue(key, day) {
    const m = metric(key);
    const value = m.data[day];
    if (value == null || value === "") return null;
    const n = Number(value);
    return Number.isNaN(n) ? null : n;
  }

  function groupsFor(key, day) {
    const m = metric(key);
    if (key === "muscle") return rawValue(key, day);
    const raw = rawValue(key, day);
    return raw == null ? 0 : Math.max(0, Math.round(raw));
  }

  function valueFor(key, day) {
    const m = metric(key);
    if (key === "muscle") return rawValue(key, day);
    const groups = groupsFor(key, day);
    return groups * (m.per > 0 ? m.per : 1);
  }

  function valueText(key, value) {
    if (value == null || Number.isNaN(Number(value))) return "—";
    return key === "muscle" ? Number(value).toFixed(1) : fmtNumber(Math.round(value));
  }

  function unitText(key) {
    const m = metric(key);
    return m.unit || "";
  }

  function perText(key) {
    const m = metric(key);
    if (key === "muscle") return "记录值";
    return `每组 ${m.per || 1} ${m.unit || ""}`.trim();
  }

  function anyData(day) {
    return ORDER.some((k) => {
      if (k === "muscle") return rawValue(k, day) != null;
      return groupsFor(k, day) > 0;
    });
  }

  function streakDays() {
    let d = todayKey();
    if (!anyData(d)) d = shiftDay(d, -1);
    let n = 0;
    while (anyData(d) && n < 3650) {
      n += 1;
      d = shiftDay(d, -1);
    }
    return n;
  }

  function recordedDays() {
    const set = new Set();
    ORDER.forEach((k) => {
      Object.keys(metric(k).data).forEach((day) => {
        if (anyData(day)) set.add(day);
      });
    });
    return set.size;
  }

  function totalFor(key) {
    const m = metric(key);
    if (key === "muscle") {
      const days = Object.keys(m.data).sort();
      for (let i = days.length - 1; i >= 0; i -= 1) {
        const v = rawValue(key, days[i]);
        if (v != null) return v;
      }
      return null;
    }
    return Object.keys(m.data).reduce((sum, day) => sum + (valueFor(key, day) || 0), 0);
  }

  function deltaText(key, value, prev, unit) {
    if (value == null && prev == null) return "还没记录";
    if (value == null) return "今天还没记录";
    if (prev == null) return "第一条记录";
    const diff = Number(value) - Number(prev);
    if (Number.isNaN(diff)) return "";
    if (diff === 0) return "和昨天一样";
    const sign = diff > 0 ? "+" : "";
    const digits = key === "muscle" ? 1 : 0;
    return `${sign}${fmtNumber(diff, digits)} ${unit}`;
  }

  function goalPercent(key) {
    const m = metric(key);
    if (!m.goal || m.goal <= 0) return 0;
    return Math.max(0, Math.min(100, Math.round((Number(totalFor(key) || 0) / m.goal) * 100)));
  }

  function snapshotReady() {
    return !!(state.snapshot && state.snapshot.metrics);
  }

  function recordsSummary() {
    const days = recordedDays();
    let entries = 0;
    ORDER.forEach((k) => {
      entries += Object.keys(metric(k).data).filter((d) => {
        const v = rawValue(k, d);
        return v != null;
      }).length;
    });
    return { days, entries, bodyFatEntries: bodyFatRecords().length };
  }

  function pageTitle() {
    return PAGE_META[state.page] || PAGE_META.overview;
  }

  /* ---------- 体脂率：独立记录，不混进肌肉重量 / 五项目 ---------- */

  function bodyFatRecords() {
    const raw = state.snapshot && Array.isArray(state.snapshot.bodyFatRecords)
      ? state.snapshot.bodyFatRecords : [];
    const seen = new Set();
    return raw.map((r) => {
      if (!r || typeof r !== "object") return null;
      const recordId = String(r.recordId || "").trim();
      const metricType = String(r.metricType || "body_fat");
      const unit = String(r.unit || "%");
      const value = Number(r.value);
      const measuredAt = Number(r.measuredAt);
      if (!recordId || seen.has(recordId)) return null;
      if (metricType !== "body_fat" || unit !== "%") return null;
      if (!Number.isFinite(value) || value < 1 || value > 75) return null;
      if (!Number.isFinite(measuredAt) || measuredAt <= 0) return null;
      seen.add(recordId);
      return {
        recordId,
        value,
        measuredAt,
        source: String(r.source || "manual"),
        deviceId: String(r.deviceId || ""),
        createdAt: Number(r.createdAt) || measuredAt,
        updatedAt: Number(r.updatedAt) || measuredAt,
      };
    }).filter(Boolean).sort((a, b) => a.measuredAt - b.measuredAt);
  }

  function bodyFatDay(record) {
    return dateKey(new Date(record.measuredAt));
  }

  function bodyFatSelected(range = state.bodyFatRange) {
    const records = bodyFatRecords();
    if (range === "all") return records;
    const days = range === "7" ? 7 : 30;
    const start = lastDays(days)[0];
    return records.filter((r) => bodyFatDay(r) >= start);
  }

  function bodyFatSourceLabel(source) {
    const s = String(source || "").toLowerCase();
    if (s === "okok") return "OKOK 测量";
    if (s === "okok-manual") return "OKOK · 已确认";
    if (s === "manual") return "手动记录";
    return source || "未知来源";
  }

  function fmtBodyFatDate(ts) {
    const d = new Date(ts);
    return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日`;
  }

  function fmtBodyFatTime(ts) {
    const d = new Date(ts);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }

  function bodyFatDateTime(ts) {
    return `${fmtBodyFatDate(ts)} · ${fmtBodyFatTime(ts)}`;
  }

  function bodyFatStats() {
    const all = bodyFatRecords();
    const selected = bodyFatSelected();
    const current = all.length ? all[all.length - 1] : null;
    const high = all.length ? all.reduce((a, b) => (b.value > a.value ? b : a)) : null;
    const low = all.length ? all.reduce((a, b) => (b.value < a.value ? b : a)) : null;
    const change = selected.length >= 2
      ? selected[selected.length - 1].value - selected[0].value : null;
    return { all, selected, current, high, low, change };
  }

  function bodyFatChart(records, range = state.bodyFatRange, height = 300) {
    if (!records.length) {
      return `<div class="chart-empty"><div class="ce-icon">%</div><div><strong>这段时间还没有体脂记录</strong><span>去手机桌面点「体脂率 · 大卡」的“去记录”，测完确认后会出现在这里。</span></div></div>`;
    }
    const W = 1000, H = height;
    const pad = { l: 52, r: 18, t: 18, b: 38 };
    const innerW = W - pad.l - pad.r;
    const innerH = H - pad.t - pad.b;
    const now = Date.now();
    const firstRecord = records[0];
    const lastRecord = records[records.length - 1];
    let startMs;
    let endMs;
    if (range === "all") {
      startMs = firstRecord.measuredAt;
      endMs = Math.max(lastRecord.measuredAt, startMs + 86400000);
    } else {
      const days = range === "7" ? 7 : 30;
      startMs = parseDay(lastDays(days)[0]).getTime();
      endMs = Math.max(now, lastRecord.measuredAt, startMs + 86400000);
    }
    const span = Math.max(endMs - startMs, 1);
    const values = records.map((r) => r.value);
    let max = Math.max(...values) + 2;
    let min = Math.min(...values) - 2;
    min = Math.max(0, min);
    if (max - min < 3) max = min + 3;
    const rangeY = max - min;
    const xAt = (ts) => pad.l + ((ts - startMs) / span) * innerW;
    const yAt = (v) => pad.t + innerH - ((v - min) / rangeY) * innerH;
    const color = "#D2505C";
    const gradientId = "bf-grad";
    const grid = [0, 1, 2, 3, 4].map((i) => {
      const v = max - (rangeY / 4) * i;
      const y = yAt(v);
      return `<line x1="${pad.l}" y1="${y.toFixed(1)}" x2="${W - pad.r}" y2="${y.toFixed(1)}" stroke="#ece3f7" stroke-dasharray="3 5" stroke-width="1"/><text x="${pad.l - 10}" y="${(y + 3.5).toFixed(1)}" text-anchor="end" fill="#a296b2" font-size="10">${v.toFixed(1)}%</text>`;
    }).join("");
    const xLabels = [0, 1, 2, 3, 4].map((i) => {
      const ts = startMs + (span * i) / 4;
      const d = new Date(ts);
      const label = range === "all" || span > 4 * 86400000
        ? `${d.getMonth() + 1}月${d.getDate()}日`
        : `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
      const anchor = i === 0 ? "start" : (i === 4 ? "end" : "middle");
      const x = i === 0 ? pad.l : (i === 4 ? W - pad.r : xAt(ts));
      return `<text x="${x.toFixed(1)}" y="${H - 12}" text-anchor="${anchor}" fill="#a296b2" font-size="10">${label}</text>`;
    }).join("");
    const points = records.map((r) => ({ r, x: xAt(r.measuredAt), y: yAt(r.value) }));
    const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
    const area = points.length > 1
      ? `<path d="${line} L${points[points.length - 1].x.toFixed(1)},${pad.t + innerH} L${points[0].x.toFixed(1)},${pad.t + innerH} Z" fill="url(#${gradientId})"/>`
      : "";
    const dots = points.map((p, i) => {
      const isLast = i === points.length - 1;
      return `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${isLast ? 5 : 3.4}" fill="${isLast ? color : "#fff"}" stroke="${color}" stroke-width="${isLast ? 3 : 2}"><title>${esc(bodyFatDateTime(p.r.measuredAt))}：${p.r.value.toFixed(1)}% · ${esc(bodyFatSourceLabel(p.r.source))}</title></circle>`;
    }).join("");
    return `<svg class="chart-svg bf-chart-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="体脂率趋势">
      <defs><linearGradient id="${gradientId}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".18"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
      ${grid}${area}<path d="${line}" fill="none" stroke="${color}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>${dots}${xLabels}
    </svg>`;
  }

  function renderBodyFatLegacy() {
    if (!snapshotReady()) return emptyState();
    const stats = bodyFatStats();
    const unsupported = state.snapshot && !Object.prototype.hasOwnProperty.call(state.snapshot, "bodyFatRecords");
    const current = stats.current;
    const rangeText = state.bodyFatRange === "7" ? "最近 7 天" : (state.bodyFatRange === "30" ? "最近 30 天" : "全部历史");
    const change = stats.change == null ? "—" : `${stats.change > 0 ? "+" : ""}${stats.change.toFixed(1)}`;
    const changeHint = stats.selected.length >= 2 ? `${stats.selected.length} 次测量` : "至少两次测量";
    const history = bodyFatSelected().slice().reverse().filter((r) => !state.bodyFatDate || bodyFatDay(r) === state.bodyFatDate);
    return `<div class="page-enter">
      ${unsupported ? `<div class="bf-unsupported">服务端还没有返回体脂记录字段。请把树莓派服务升级到 v1.3.0 后再看这一页；页面不会用模拟数据填空。</div>` : ""}
      <section class="bf-hero">
        <div class="bf-hero-main">
          <div class="bf-kicker">BODY FAT / 独立记录</div>
          <div class="bf-label">当前体脂率</div>
          <div class="bf-value ${current ? "" : "empty"}">${current ? current.value.toFixed(1) : "--.-"}<span>%</span></div>
          <div class="bf-meta">${current ? `${esc(bodyFatDateTime(current.measuredAt))} · ${esc(bodyFatSourceLabel(current.source))}` : "还没有真实记录，去手机桌面记一笔"}</div>
          <p class="bf-copy">体脂率单独存、单独同步。这里不把它混进肌肉重量，也不生成假的历史；有几次测量，就显示几次。</p>
        </div>
        <div class="bf-hero-side">
          <div class="bf-side-line"><span>记录总数</span><b>${stats.all.length} 次</b></div>
          <div class="bf-side-line"><span>最近一次</span><b>${current ? fmtBodyFatDate(current.measuredAt) : "—"}</b></div>
          <div class="bf-side-line"><span>数据来源</span><b>树莓派同步</b></div>
        </div>
      </section>
      <div class="stat-grid bf-stats">
        ${statBox("当前体脂率", current ? current.value.toFixed(1) : "—", current ? "%" : "")}
        ${statBox("历史最高", stats.high ? stats.high.value.toFixed(1) : "—", stats.high ? "%" : "", stats.high ? fmtBodyFatDate(stats.high.measuredAt) : "还没有记录")}
        ${statBox("历史最低", stats.low ? stats.low.value.toFixed(1) : "—", stats.low ? "%" : "", stats.low ? fmtBodyFatDate(stats.low.measuredAt) : "还没有记录")}
        ${statBox("选定时间段变化", change, stats.change == null ? "" : "%", changeHint)}
        ${statBox("最近测量时间", current ? fmtBodyFatTime(current.measuredAt) : "—", "", current ? fmtBodyFatDate(current.measuredAt) : "还没有记录")}
      </div>
      <section class="panel panel-pad bf-chart-panel">
        <div class="panel-head bf-head">
          <div><h2 class="panel-title">体脂率趋势 · ${rangeText}</h2><div class="panel-kicker">每个点就是你确认保存的一次测量，不按天合并</div></div>
          <div class="seg" role="group" aria-label="切换体脂趋势范围">
            <button type="button" data-bf-range="7" class="${state.bodyFatRange === "7" ? "on" : ""}">7 天</button>
            <button type="button" data-bf-range="30" class="${state.bodyFatRange === "30" ? "on" : ""}">30 天</button>
            <button type="button" data-bf-range="all" class="${state.bodyFatRange === "all" ? "on" : ""}">全部历史</button>
          </div>
        </div>
        <div class="chart-wrap bf-chart-wrap">${bodyFatChart(stats.selected, state.bodyFatRange, 300)}</div>
      </section>
      <section class="panel panel-pad bf-history-panel">
        <div class="panel-head bf-head">
          <div><h2 class="panel-title">历史明细</h2><div class="panel-kicker">共 ${history.length} 条${state.bodyFatDate ? ` · 只看 ${esc(state.bodyFatDate)}` : " · 按测量时间倒序"}</div></div>
          <div class="bf-filter">
            <label for="bf-date">按日期查看</label>
            <input id="bf-date" type="date" value="${esc(state.bodyFatDate)}">
            ${state.bodyFatDate ? `<button type="button" class="panel-link" data-action="bf-date-clear">看全部</button>` : ""}
          </div>
        </div>
        ${history.length ? `<div class="bf-table-wrap"><table class="daily-table bf-table"><thead><tr><th>测量日期</th><th>时间</th><th>体脂率</th><th>来源</th><th>设备</th></tr></thead><tbody>${history.map((r) => `<tr><td>${esc(fmtBodyFatDate(r.measuredAt))}</td><td>${esc(fmtBodyFatTime(r.measuredAt))}</td><td class="num bf-num">${r.value.toFixed(1)}%</td><td><span class="bf-source">${esc(bodyFatSourceLabel(r.source))}</span></td><td class="bf-device">${esc(r.deviceId || "—")}</td></tr>`).join("")}</tbody></table></div>` : `<div class="bf-history-empty"><span>⌁</span><div><strong>${stats.all.length ? "这个筛选范围没有记录" : "还没有体脂率记录"}</strong><p>${stats.all.length ? "换一个时间段，或者清除日期筛选。" : "先在手机桌面添加「体脂率 · 大卡」，点“去记录”，在 OKOK 测完后回日刻确认。"}</p></div></div>`}
      </section>
    </div>`;
  }

  function bodyFatDailySeries(records, days = 14) {
    const slots = lastDays(days);
    const byDay = {};
    records.forEach((record) => {
      const day = bodyFatDay(record);
      if (!byDay[day] || record.measuredAt >= byDay[day].measuredAt) byDay[day] = record;
    });
    return slots.map((day) => ({ day, record: byDay[day] || null }));
  }

  function bodyFatMiniChart(series) {
    const valid = series.filter((item) => item.record).map((item) => item.record.value);
    if (!valid.length) {
      return `<div class="bf-mini-empty"><span>%</span><div><strong>这 14 天还没有体脂记录</strong><p>手机桌面点「体脂率 · 大卡」的“去记录”，测完回日刻确认。</p></div></div>`;
    }
    const W = 560, H = 116, pad = { l: 6, r: 6, t: 10, b: 14 };
    const innerW = W - pad.l - pad.r;
    const innerH = H - pad.t - pad.b;
    let max = Math.max(...valid) + 0.8;
    let min = Math.min(...valid) - 0.8;
    if (max - min < 2) { max += 1; min -= 1; }
    const range = max - min;
    const xAt = (i) => pad.l + (i / Math.max(series.length - 1, 1)) * innerW;
    const yAt = (v) => pad.t + innerH - ((v - min) / range) * innerH;
    let segments = [];
    let current = [];
    series.forEach((item, i) => {
      if (!item.record) {
        if (current.length) segments.push(current);
        current = [];
      } else {
        current.push({ i, value: item.record.value });
      }
    });
    if (current.length) segments.push(current);
    const paths = segments.map((segment) => {
      const d = segment.map((p, idx) => `${idx === 0 ? "M" : "L"}${xAt(p.i).toFixed(1)},${yAt(p.value).toFixed(1)}`).join(" ");
      return `<path d="${d}" fill="none" stroke="#D2505C" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>`;
    }).join("");
    const area = segments.filter((segment) => segment.length > 1).map((segment) => {
      const first = segment[0], last = segment[segment.length - 1];
      const d = segment.map((p, idx) => `${idx === 0 ? "M" : "L"}${xAt(p.i).toFixed(1)},${yAt(p.value).toFixed(1)}`).join(" ");
      return `<path d="${d} L${xAt(last.i).toFixed(1)},${pad.t + innerH} L${xAt(first.i).toFixed(1)},${pad.t + innerH} Z" fill="url(#bf-mini-grad)"/>`;
    }).join("");
    const dots = series.map((item, i) => item.record
      ? `<circle cx="${xAt(i).toFixed(1)}" cy="${yAt(item.record.value).toFixed(1)}" r="${i === series.length - 1 ? 4 : 2.8}" fill="${i === series.length - 1 ? "#D2505C" : "#fff"}" stroke="#D2505C" stroke-width="2"><title>${esc(fmtBodyFatDate(item.record.measuredAt))}：${item.record.value.toFixed(1)}%</title></circle>`
      : "").join("");
    return `<svg class="bf-mini-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="近14天体脂率趋势">
      <defs><linearGradient id="bf-mini-grad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D2505C" stop-opacity=".18"/><stop offset="1" stop-color="#D2505C" stop-opacity="0"/></linearGradient></defs>
      <line x1="${pad.l}" y1="${pad.t + innerH}" x2="${W - pad.r}" y2="${pad.t + innerH}" stroke="#eee5f8" stroke-width="1"/>
      ${area}${paths}${dots}
      <text x="${pad.l}" y="${H - 2}" fill="#a296b2" font-size="9">${fmtDate(series[0].day)}</text>
      <text x="${W - pad.r}" y="${H - 2}" text-anchor="end" fill="#a296b2" font-size="9">${fmtDate(series[series.length - 1].day)}</text>
    </svg>`;
  }

  function bodyFatOverviewPanel() {
    const stats = bodyFatStats();
    const todayRecord = stats.all.filter((record) => bodyFatDay(record) === todayKey()).slice(-1)[0] || null;
    const latest = stats.current;
    const series = bodyFatDailySeries(stats.all, 14);
    const measured = series.filter((item) => item.record).map((item) => item.record.value);
    const first = measured.length ? measured[0] : null;
    const last = measured.length ? measured[measured.length - 1] : null;
    const change = first != null && last != null ? last - first : null;
    const shown = todayRecord || latest;
    const label = todayRecord ? "今天" : (latest ? "最近一次" : "当前");
    return `<section class="panel panel-pad bf-glance" aria-label="今天体脂率与近14天趋势">
      <div class="bf-glance-head">
        <div><div class="bf-kicker">BODY FAT / 今日</div><h2 class="panel-title">体脂率</h2></div>
        <button class="panel-link" type="button" data-page="bodyfat">体脂率页</button>
      </div>
      <div class="bf-glance-now">
        <div>
          <div class="bf-glance-label">${label}</div>
          <div class="bf-glance-value ${shown ? "" : "empty"}">${shown ? shown.value.toFixed(1) : "--.-"}<span>%</span></div>
          <div class="bf-glance-meta">${shown ? `${fmtBodyFatTime(shown.measuredAt)} · ${esc(bodyFatSourceLabel(shown.source))}` : "今天还没测，去手机记一笔"}</div>
        </div>
        <div class="bf-glance-side">
          <div><span>今天</span><b>${todayRecord ? `${todayRecord.value.toFixed(1)}%` : "未测"}</b></div>
          <div><span>14 天变化</span><b class="${change == null ? "" : (change > 0 ? "up" : "down")}">${change == null ? "—" : `${change > 0 ? "+" : ""}${change.toFixed(1)}%`}</b></div>
        </div>
      </div>
      <div class="bf-glance-chart">${bodyFatMiniChart(series)}</div>
      <div class="bf-glance-foot"><span>近 14 天</span><span>${measured.length ? `${measured.length} 次测量 · ${fmtDate(series[0].day)} 至 ${fmtDate(series[series.length - 1].day)}` : "还没有记录"}</span></div>
    </section>`;
  }

  function bfStatCard(kicker, value, unit, extra, options = {}) {
    const tone = options.tone || "#D2505C";
    const wash = options.wash || "rgba(210,80,92,.10)";
    return `<article class="bf-stat-card ${options.featured ? "featured" : ""}" style="--bf-tone:${tone};--bf-wash:${wash}">
      <div class="bf-stat-ic">${options.icon || "%"}</div>
      <div class="bf-stat-kicker">${esc(kicker)}</div>
      <div class="bf-stat-value">${value}${unit ? `<span>${esc(unit)}</span>` : ""}</div>
      <div class="bf-stat-extra">${esc(extra)}</div>
    </article>`;
  }

  function renderBodyFat() {
    if (!snapshotReady()) return emptyState();
    const stats = bodyFatStats();
    const unsupported = state.snapshot && !Object.prototype.hasOwnProperty.call(state.snapshot, "bodyFatRecords");
    const current = stats.current;
    const todayRecord = stats.all.filter((record) => bodyFatDay(record) === todayKey()).slice(-1)[0] || null;
    const rangeText = state.bodyFatRange === "7" ? "最近 7 天" : (state.bodyFatRange === "30" ? "最近 30 天" : "全部历史");
    const change = stats.change == null ? "—" : `${stats.change > 0 ? "+" : ""}${stats.change.toFixed(1)}`;
    const changeHint = stats.selected.length >= 2 ? `${stats.selected.length} 次测量` : "至少两次测量";
    const history = bodyFatSelected().slice().reverse().filter((record) => !state.bodyFatDate || bodyFatDay(record) === state.bodyFatDate);
    return `<div class="page-enter bf-page">
      ${unsupported ? `<div class="bf-unsupported">服务端还没有返回体脂记录字段。请把树莓派服务升级到 v1.3.0 后再看这一页；页面不会用模拟数据填空。</div>` : ""}
      <section class="bf-stat-grid" aria-label="体脂率概览">
        ${bfStatCard("当前体脂率", current ? current.value.toFixed(1) : "--.-", "%", current ? `${fmtBodyFatDate(current.measuredAt)} · ${bodyFatSourceLabel(current.source)}` : "还没有真实记录", { featured: true, icon: "%", tone: "#D2505C", wash: "rgba(210,80,92,.12)" })}
        ${bfStatCard("今天", todayRecord ? todayRecord.value.toFixed(1) : "—", todayRecord ? "%" : "", todayRecord ? `${fmtBodyFatTime(todayRecord.measuredAt)} · ${bodyFatSourceLabel(todayRecord.source)}` : "今天还没测", { icon: "今", tone: "#8B76D9", wash: "rgba(139,118,217,.12)" })}
        ${bfStatCard("历史最高", stats.high ? stats.high.value.toFixed(1) : "—", stats.high ? "%" : "", stats.high ? fmtBodyFatDate(stats.high.measuredAt) : "还没有记录", { icon: "↑", tone: "#B43644", wash: "rgba(180,54,68,.10)" })}
        ${bfStatCard("历史最低", stats.low ? stats.low.value.toFixed(1) : "—", stats.low ? "%" : "", stats.low ? fmtBodyFatDate(stats.low.measuredAt) : "还没有记录", { icon: "↓", tone: "#2E9A6B", wash: "rgba(46,154,107,.10)" })}
        ${bfStatCard("选定时间段变化", change, stats.change == null ? "" : "%", changeHint, { icon: "⌁", tone: "#6E58BE", wash: "rgba(110,88,190,.11)" })}
      </section>
      <div class="bf-workbench">
        <section class="panel panel-pad bf-chart-panel">
          <div class="panel-head bf-head">
            <div><h2 class="panel-title">体脂率趋势 · ${rangeText}</h2><div class="panel-kicker">每个点就是你确认保存的一次测量，不按天合并</div></div>
            <div class="seg" role="group" aria-label="切换体脂趋势范围">
              <button type="button" data-bf-range="7" class="${state.bodyFatRange === "7" ? "on" : ""}">7 天</button>
              <button type="button" data-bf-range="30" class="${state.bodyFatRange === "30" ? "on" : ""}">30 天</button>
              <button type="button" data-bf-range="all" class="${state.bodyFatRange === "all" ? "on" : ""}">全部历史</button>
            </div>
          </div>
          <div class="bf-chart-wrap">${bodyFatChart(stats.selected, state.bodyFatRange, 300)}</div>
        </section>
        <section class="panel panel-pad bf-history-panel">
          <div class="panel-head bf-head">
            <div><h2 class="panel-title">历史明细</h2><div class="panel-kicker">共 ${history.length} 条${state.bodyFatDate ? ` · 只看 ${esc(state.bodyFatDate)}` : " · 按测量时间倒序"}</div></div>
            <div class="bf-filter">
              <label for="bf-date">按日期</label>
              <input id="bf-date" type="date" value="${esc(state.bodyFatDate)}">
              ${state.bodyFatDate ? `<button type="button" class="panel-link" data-action="bf-date-clear">看全部</button>` : ""}
            </div>
          </div>
          ${history.length ? `<div class="bf-table-wrap scroll-hide"><table class="daily-table bf-table"><thead><tr><th>测量日期</th><th>时间</th><th>体脂率</th><th>来源</th><th>设备</th></tr></thead><tbody>${history.map((record) => `<tr><td>${esc(fmtBodyFatDate(record.measuredAt))}</td><td>${esc(fmtBodyFatTime(record.measuredAt))}</td><td class="num bf-num">${record.value.toFixed(1)}%</td><td><span class="bf-source">${esc(bodyFatSourceLabel(record.source))}</span></td><td class="bf-device">${esc(record.deviceId || "—")}</td></tr>`).join("")}</tbody></table></div>` : `<div class="bf-history-empty"><span>⌁</span><div><strong>${stats.all.length ? "这个筛选范围没有记录" : "还没有体脂率记录"}</strong><p>${stats.all.length ? "换一个时间段，或者清除日期筛选。" : "先在手机桌面添加「体脂率 · 大卡」，点“去记录”，在 OKOK 测完后回日刻确认。"}</p></div></div>`}
        </section>
      </div>
    </div>`;
  }

  function pageClass() { return state.animatePage ? "page-enter" : "page-static"; }

  function emptyState() {
    const hasToken = !!(state.config && state.config.hasToken);
    const noConnection = state.loadError || !hasToken;
    return `<div class="panel empty-state ${pageClass()}">
      <div class="e-icon">${hasToken ? "◌" : "⌁"}</div>
      <h3>${hasToken ? "还差一步，连上树莓派" : "先填上树莓派的地址"}</h3>
      <p>${noConnection ? esc(state.loadError || "数据在树莓派的 Docker 里，电脑这边需要 IP、端口和 Token 才能读到。") : "还没有数据。"}</p>
      <button class="btn btn-primary" data-action="open-settings">打开连接设置</button>
    </div>`;
  }

  function pageBanner() {
    if (state.loadError) {
      return `<div class="offline-banner">
        <span>!</span>
        <div><strong>没有读到实时数据。</strong> ${esc(state.loadError)}${state.snapshot ? " 下面先显示上一次读到的内容。" : ""}</div>
        <button class="btn btn-danger-ghost" data-action="open-settings">去设置</button>
      </div>`;
    }
    return "";
  }

  function statBox(kicker, value, unit = "", extra = "") {
    return `<div class="stat-box"><div class="sk">${esc(kicker)}</div><div class="sv">${value}${unit ? `<span class="su">${esc(unit)}</span>` : ""}</div>${extra ? `<div class="hint">${esc(extra)}</div>` : ""}</div>`;
  }

  function tinyBars(key, days = 7) {
    const values = lastDays(days).map((d) => valueFor(key, d) || 0);
    const max = Math.max(...values, 1);
    return `<div class="tiny-bars" aria-hidden="true">${values.map((v) => {
      const h = Math.max(5, Math.round((v / max) * 22));
      return `<i style="--h:${h}px"></i>`;
    }).join("")}</div>`;
  }

  function sparkline(key, days = 14) {
    const values = lastDays(days).map((d) => valueFor(key, d) || 0);
    const max = Math.max(...values, 1);
    const min = Math.min(...values, 0);
    const w = 50, h = 22, pad = 2;
    const range = Math.max(max - min, 1);
    const pts = values.map((v, i) => {
      const x = pad + (i / Math.max(values.length - 1, 1)) * (w - pad * 2);
      const y = h - pad - ((v - min) / range) * (h - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(" ");
    return `<svg class="pc-spark" viewBox="0 0 ${w} ${h}" aria-hidden="true"><polyline points="${pts}" fill="none" stroke="${TONE[key].accent}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" opacity=".72"/></svg>`;
  }

  function ring(key) {
    const pct = goalPercent(key);
    const tone = TONE[key];
    return `<div class="ring ${pct >= 100 ? "gold" : ""}" style="--p:${pct};--ring-color:${tone.ring}" title="目标完成 ${pct}%"><div class="ring-inner">${pct}%</div></div>`;
  }

  /* 近 14 天的迷你折线：塞进指标卡底部，替代原来的小柱子和独立项目卡里的 sparkline */
  function sparkStrip(key, days = 14) {
    const list = lastDays(days);
    const values = list.map((d) => valueFor(key, d));
    const valid = values.filter((v) => v != null);
    if (!valid.length) return `<div class="spark-strip empty">近 ${days} 天还没有记录</div>`;
    const w = 200, h = 26, pad = 3;
    let max = Math.max(...valid), min = Math.min(...valid);
    if (Math.max(max - min, 1) < 1e-9) { max += 1; min -= 1; }
    const range = Math.max(max - min, 1);
    const xAt = (i) => pad + (i / Math.max(list.length - 1, 1)) * (w - pad * 2);
    const yAt = (v) => h - pad - ((v - min) / range) * (h - pad * 2);
    const segments = [];
    let points = [];
    values.forEach((v, i) => {
      if (v == null) {
        if (points.length > 1) segments.push(points.join(" "));
        points = [];
        return;
      }
      points.push(`${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`);
    });
    if (points.length > 1) segments.push(points.join(" "));
    const dots = values.map((v, i) => v == null ? "" : `<circle cx="${xAt(i).toFixed(1)}" cy="${yAt(v).toFixed(1)}" r="1.8" fill="${TONE[key].accent}" opacity=".55"/>`).join("");
    return `<div class="spark-strip"><svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true">${segments.map((pts) => `<polyline points="${pts}" fill="none" stroke="${TONE[key].accent}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" opacity=".62"/>`).join("")}${dots}</svg></div>`;
  }

  function metricCard(key) {
    const m = metric(key);
    const value = valueFor(key, todayKey());
    const yesterday = valueFor(key, shiftDay(todayKey(), -1));
    const unit = unitText(key);
    const tone = TONE[key];
    if (key === "muscle") {
      const week = lastDays(7).map((d) => {
        const v = rawValue(key, d);
        return `<div class="week-cell ${d === todayKey() ? "today" : ""}"><span>周${weekdayShort(d)}</span><b>${v == null ? "--" : Number(v).toFixed(1)}</b></div>`;
      }).join("");
      return `<article class="metric-card height-card" data-metric="muscle" style="--glow:${tone.glow}" title="单击查看肌肉重量项目详情">
        <div class="metric-top"><span class="metric-name">肌肉重量</span><span class="metric-per">今天</span></div>
        <div class="metric-value"><span class="n ${value == null ? "empty" : ""}">${valueText(key, value)}</span>${value == null ? "" : `<span class="u">${esc(unit)}</span>`}</div>
        <div class="metric-sub">${esc(deltaText(key, value, yesterday, unit))}${value == null ? "" : " · 保持记录"}</div>
        <div class="week-strip">${week}</div>
      </article>`;
    }
    const total = totalFor(key);
    const pct = goalPercent(key);
    return `<article class="metric-card has-proj" data-metric="${key}" style="--accent:${tone.accent};--bar:${tone.bar};--glow:${tone.glow}" title="单击查看${esc(m.name)}项目详情">
      <div class="metric-top proj"><div class="mt-left"><span class="metric-name">${esc(m.name)}</span><div class="metric-per">${esc(perText(key))}</div></div>${ring(key)}</div>
      <div class="metric-value"><span class="n ${value == null ? "empty" : ""}">${valueText(key, value)}</span>${value == null ? "" : `<span class="u">${esc(unit)}</span>`}</div>
      <div class="metric-sub">今天 ${groupsFor(key, todayKey())} 组 · 昨天 ${valueText(key, yesterday)}</div>
      ${sparkStrip(key)}
      <div class="metric-foot"><span class="tag">累计 ${total == null ? "—" : `${fmtNumber(total)} ${esc(m.unit)}`}</span><span class="mfoot-pct">目标 ${fmtNumber(m.goal)}</span></div>
    </article>`;
  }

  function chartPanelInner(key) {
    return `<div class="chart-head">
        <div><h2 class="panel-title">最近 30 天</h2><div class="panel-kicker">${esc(metric(key).name)} · 每一天的刻度</div></div>
        <div class="seg" role="group" aria-label="切换趋势项目">
          ${ORDER.map((k) => `<button data-trend-metric="${k}" class="${k === key ? "on" : ""}" type="button">${esc(metric(k).name)}</button>`).join("")}
        </div>
      </div>
      <div class="chart-wrap">${lineChart(key, 30)}</div>
      <div class="chart-footer"><span>今日 <b>${valueText(key, valueFor(key, todayKey()))} ${esc(unitText(key))}</b></span><span>30 天最高 <b>${valueText(key, maxOf(key, 30))} ${esc(unitText(key))}</b></span><span>连续记录 <b>${streakDays()} 天</b></span></div>`;
  }

  function chartPanel(key) {
    return `<section class="panel chart-panel" id="chart-panel" aria-label="最近三十天趋势">${chartPanelInner(key)}</section>`;
  }

  function maxOf(key, days) {
    const vals = lastDays(days).map((d) => valueFor(key, d)).filter((v) => v != null);
    return vals.length ? Math.max(...vals) : null;
  }

  function minOf(key, days) {
    const vals = lastDays(days).map((d) => valueFor(key, d)).filter((v) => v != null);
    return vals.length ? Math.min(...vals) : null;
  }

  function lineChart(key, days = 30, height = 270) {
    const list = lastDays(days);
    const values = list.map((d) => valueFor(key, d));
    const valid = values.filter((v) => v != null);
    if (!valid.length) {
      return `<div class="empty-state"><div class="e-icon">⌁</div><h3>还没有趋势</h3><p>等树莓派里有记录，线就会自己长出来。</p></div>`;
    }
    if (key !== "muscle" && valid.every((v) => Number(v) === 0)) {
      return `<div class="chart-empty"><div class="ce-icon">⌁</div><div><strong>清空后还没有记录</strong><span>去手机记录一笔，这条线会重新长出来。</span></div></div>`;
    }
    const W = 1000, H = height;
    const pad = { l: 52, r: 18, t: 18, b: 38 };
    const innerW = W - pad.l - pad.r;
    const innerH = H - pad.t - pad.b;
    let max = Math.max(...valid);
    let min = Math.min(...valid);
    if (key === "muscle") {
      const spread = Math.max(max - min, 1);
      max += spread * .2;
      min -= spread * .2;
    } else {
      max = Math.max(max, 1);
      max *= 1.08;
      min = 0;
    }
    const range = Math.max(max - min, 1);
    const xAt = (i) => pad.l + (i / Math.max(list.length - 1, 1)) * innerW;
    const yAt = (v) => pad.t + innerH - ((v - min) / range) * innerH;
    const color = TONE[key].accent;
    const gradientId = `grad-${key}`;
    const grid = [0, 1, 2, 3, 4].map((i) => {
      const v = min + (range / 4) * i;
      const y = yAt(v);
      const label = key === "muscle" ? Number(v).toFixed(1) : fmtNumber(Math.round(v));
      return `<line x1="${pad.l}" y1="${y.toFixed(1)}" x2="${W - pad.r}" y2="${y.toFixed(1)}" stroke="#ece3f7" stroke-dasharray="3 5" stroke-width="1"/><text x="${pad.l - 10}" y="${(y + 3.5).toFixed(1)}" text-anchor="end" fill="#a296b2" font-size="10">${label}</text>`;
    }).join("");
    const xLabels = list.map((d, i) => {
      if (i % 5 !== 0 && i !== list.length - 1) return "";
      const anchor = i === list.length - 1 ? "end" : (i === 0 ? "start" : "middle");
      const x = i === list.length - 1 ? W - pad.r : (i === 0 ? pad.l : xAt(i));
      return `<text x="${x.toFixed(1)}" y="${H - 12}" text-anchor="${anchor}" fill="#a296b2" font-size="10">${parseDay(d).getMonth() + 1}月${parseDay(d).getDate()}日</text>`;
    }).join("");
    let segments = [];
    let current = [];
    values.forEach((v, i) => {
      if (v == null) {
        if (current.length) segments.push(current);
        current = [];
      } else {
        current.push({ i, v });
      }
    });
    if (current.length) segments.push(current);
    const linePaths = segments.map((seg) => {
      const d = seg.map((p, idx) => `${idx === 0 ? "M" : "L"}${xAt(p.i).toFixed(1)},${yAt(p.v).toFixed(1)}`).join(" ");
      return `<path d="${d}" fill="none" stroke="${color}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>`;
    }).join("");
    const areaPaths = segments.filter((seg) => seg.length > 1).map((seg) => {
      const first = seg[0], last = seg[seg.length - 1];
      const d = seg.map((p, idx) => `${idx === 0 ? "M" : "L"}${xAt(p.i).toFixed(1)},${yAt(p.v).toFixed(1)}`).join(" ");
      return `<path d="${d} L${xAt(last.i).toFixed(1)},${pad.t + innerH} L${xAt(first.i).toFixed(1)},${pad.t + innerH} Z" fill="url(#${gradientId})"/>`;
    }).join("");
    const dots = values.map((v, i) => {
      if (v == null) return "";
      const isToday = list[i] === todayKey();
      return `<circle cx="${xAt(i).toFixed(1)}" cy="${yAt(v).toFixed(1)}" r="${isToday ? 5 : 3.2}" fill="${isToday ? color : "#fff"}" stroke="${color}" stroke-width="${isToday ? 3 : 2}"><title>${fmtDate(list[i])}：${valueText(key, v)} ${esc(unitText(key))}</title></circle>`;
    }).join("");
    return `<svg class="chart-svg" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${esc(metric(key).name)}最近${days}天趋势">
      <defs><linearGradient id="${gradientId}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".16"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
      ${grid}${areaPaths}${linePaths}${dots}${xLabels}
    </svg>`;
  }

  function buildHighlights(limit = 4) {
    const items = [];
    const today = todayKey();
    const yest = shiftDay(today, -1);
    const countKeys = ["push", "horse", "pullup", "custom"];
    countKeys.forEach((k) => {
      const m = metric(k);
      const v = valueFor(k, today);
      const p = valueFor(k, yest);
      const groups = groupsFor(k, today);
      if (v > 0) {
        let text = `今天 ${groups} 组，共 ${fmtNumber(v)} ${m.unit || ""}`;
        if (p > 0) {
          const diff = v - p;
          if (diff > 0) text += `，比昨天多 ${fmtNumber(diff)} ${m.unit || ""}`;
          else if (diff < 0) text += `，比昨天少 ${fmtNumber(-diff)} ${m.unit || ""}`;
          else text += "，和昨天打平";
        }
        items.push({ icon: "↑", title: `${m.name}有动静`, text, tone: TONE[k].accent });
      }
    });
    const muscle = valueFor("muscle", today);
    if (muscle != null) {
      const prev = valueFor("muscle", yest);
      items.push({ icon: "·", title: "肌肉重量记下了", text: `${muscle.toFixed(1)} 公斤${prev != null ? `，${deltaText("muscle", muscle, prev, "公斤")}` : "，第一条记录"}` });
    }
    if (items.length < limit) {
      const best = ["push", "horse", "pullup", "custom"].map((k) => ({ k, v: maxOf(k, 30) })).sort((a, b) => (b.v || 0) - (a.v || 0))[0];
      if (best && best.v > 0) {
        items.push({ icon: "✦", title: "最近 30 天的峰值", text: `${metric(best.k).name}最高 ${fmtNumber(best.v)} ${metric(best.k).unit || ""}` });
      } else {
        items.push({ icon: "○", title: "今天还没有记录", text: "去手机桌面点一下方块，这里会自动长出趣事。" });
      }
    }
    if (items.length < limit && streakDays() > 1) {
      items.push({ icon: "∞", title: "连续记录", text: `${streakDays()} 天了，日子被连成一条线` });
    }
    if (items.length < limit && recordedDays() > 0) {
      items.push({ icon: "▦", title: "已经刻下的日子", text: `${recordedDays()} 天有记录，共 ${recordsSummary().entries} 条刻度` });
    }
    return items.slice(0, limit);
  }

  function funPanel() {
    const items = buildHighlights(3);
    return `<section class="panel panel-pad">
      <div class="panel-head"><div><h2 class="panel-title">今日趣事</h2><div class="panel-kicker">从数据里自己长出来的看点</div></div><button class="panel-link" data-action="goto-fun">全部趣事</button></div>
      <div class="fun-list">${items.map((it) => `<div class="fun-item"><div class="fun-icon" style="color:${it.tone || TONE.push.accent}">${it.icon}</div><div><strong>${esc(it.title)}</strong><span>${esc(it.text)}</span></div></div>`).join("")}</div>
    </section>`;
  }

  function projectCard(key) {
    const m = metric(key);
    const total = totalFor(key);
    const pct = goalPercent(key);
    return `<article class="project-card" data-project="${key}" title="单击详情，双击编辑">
      <div class="pc-top"><div><h3>${esc(m.name)}</h3><div class="pc-per">${esc(perText(key))}</div></div>${key === "muscle" ? `<div class="ring" style="--p:0;--ring-color:#D2505C"><div class="ring-inner">—</div></div>` : ring(key)}</div>
      <div class="pc-val">${valueText(key, total)}${total == null ? "" : `<span class="pc-unit">${esc(m.unit)}</span>`}</div>
      <div class="pc-meta">${key === "muscle" ? "记录值 · 不计组数" : `目标 ${fmtNumber(m.goal)} · 完成 ${pct}%`}</div>
      <div class="pc-hint">单击详情 · 双击编辑</div>
      ${sparkline(key)}
    </article>`;
  }

  function projectsGrid() {
    return `<div class="project-grid">${ORDER.map((k) => projectCard(k)).join("")}</div>`;
  }

  function renderOverview() {
    if (!snapshotReady()) return emptyState();
    return `<div class="${pageClass()} overview-page">
      <div class="strip-head"><span class="strip-title">五个项目 · 今天</span><button class="panel-link" data-page="projects">打开项目页</button></div>
      ${metricGrid()}
      <div class="dashboard-grid">${chartPanel(state.activeMetric)}${bodyFatOverviewPanel()}</div>
    </div>`;
  }

  function metricGrid() {
    return `<section class="metric-grid" aria-label="今日项目">${ORDER.map((k) => metricCard(k)).join("")}</section>`;
  }

  function renderToday() {
    if (!snapshotReady()) return emptyState();
    const today = todayKey();
    const count = ORDER.filter((k) => (k === "muscle" ? rawValue(k, today) != null : groupsFor(k, today) > 0)).length;
    const totalGroups = ["push", "horse", "pullup", "custom"].reduce((sum, k) => sum + groupsFor(k, today), 0);
    return `<div class="${pageClass()} today-page">
      <div class="today-layout">
        <section class="panel panel-pad today-panel">
          <div class="section-title-row"><div><h2 class="section-title">今天，五个刻度</h2><div class="section-sub">${fmtDateFull(today)} · 每组数量来自项目配置</div></div><button class="panel-link" data-page="trend">看完整趋势</button></div>
          <div class="today-list">${ORDER.map((k) => todayRow(k)).join("")}</div>
        </section>
        <aside class="today-side">
          <section class="panel panel-pad today-facts">
            <div class="panel-head"><div><h2 class="panel-title">今天的数据</h2><div class="panel-kicker">先看整体，再往下看小结</div></div></div>
            <div class="today-fact-grid">
              ${statBox("今日已记录", `${count}<span class="su">/ 5</span>`, "", "")}
              ${statBox("连续记录", `${streakDays()}<span class="su">天</span>`, "", "")}
              ${statBox("今日组数", `${totalGroups}<span class="su">组</span>`, "", "")}
              ${statBox("今天日期", fmtDate(today), "", `周${weekdayShort(today)}`)}
            </div>
          </section>
          <section class="panel panel-pad today-summary">
            <div class="panel-head"><div><h2 class="panel-title">今天的小结</h2><div class="panel-kicker">不评价，只把今天放好</div></div></div>
            <div class="fun-list">
              <div class="fun-item"><div class="fun-icon">✓</div><div><strong>${count} 个项目有记录</strong><span>${count === 5 ? "今天五个刻度都在。" : `还有 ${5 - count} 个项目空着，不着急。`}</span></div></div>
              <div class="fun-item"><div class="fun-icon">⌁</div><div><strong>${totalGroups} 组动作</strong><span>这是今天所有计数项目加起来的组数，不是个数。</span></div></div>
              <div class="fun-item"><div class="fun-icon">∞</div><div><strong>${streakDays() > 0 ? `连续 ${streakDays()} 天` : "从今天开始"}</strong><span>${streakDays() > 0 ? "这条线还在继续。" : "第一条记录，也算开始。"}</span></div></div>
            </div>
          </section>
        </aside>
      </div>
    </div>`;
  }

  function todayRow(key) {
    const m = metric(key);
    const today = todayKey();
    const yest = shiftDay(today, -1);
    const v = valueFor(key, today);
    const p = valueFor(key, yest);
    const unit = m.unit || "";
    const days = lastDays(7).map((d) => {
      const val = valueFor(key, d);
      return { d, val };
    });
    const max = Math.max(...days.map((x) => x.val || 0), 1);
    return `<div class="today-row" data-metric="${key}" style="--accent:${TONE[key].accent}">
      <div><div class="tr-name">${esc(m.name)}</div><div class="tr-per">${esc(perText(key))}</div></div>
      <div class="seven-bars">${days.map((x) => `<div class="sb ${x.d === today ? "today" : ""}"><i style="--h:${Math.max(4, Math.round(((x.val || 0) / max) * 36))}px"></i><b>周${weekdayShort(x.d)}</b></div>`).join("")}</div>
      <div class="tr-value"><span class="tr-num">${valueText(key, v)}</span><span class="tr-unit">${esc(unit)}</span><div class="tr-compare">${esc(deltaText(key, v, p, unit))}</div></div>
    </div>`;
  }

  function renderTrendLegacy() {
    if (!snapshotReady()) return emptyState();
    const key = state.activeMetric;
    const today = todayKey();
    const vals = lastDays(30).map((d) => valueFor(key, d));
    const valid = vals.filter((v) => v != null);
    const total = key === "muscle" ? null : valid.reduce((a, b) => a + b, 0);
    return `<div class="page-enter">
      <div class="trend-tabs" role="tablist">${ORDER.map((k) => `<button type="button" data-trend-metric="${k}" class="${k === key ? "on" : ""}">${esc(metric(k).name)}</button>`).join("")}</div>
      <section class="panel panel-pad">
        <div class="panel-head"><div><h2 class="panel-title">${esc(metric(key).name)} · 最近 30 天</h2><div class="panel-kicker">${key === "muscle" ? "记录值按公斤显示，断点表示那天没量" : "按项目配置换算成实际数量，悬停圆点可看当天数值"}</div></div><button class="panel-link" data-page="projects">项目设置</button></div>
        <div style="height:360px">${lineChart(key, 30, 360)}</div>
        <div class="stat-grid">
          ${statBox("今天", valueText(key, valueFor(key, today)), unitText(key))}
          ${statBox("昨天", valueText(key, valueFor(key, shiftDay(today, -1))), unitText(key))}
          ${statBox("30 天最高", valueText(key, maxOf(key, 30)), unitText(key))}
          ${statBox(key === "muscle" ? "30 天最低" : "30 天合计", key === "muscle" ? valueText(key, minOf(key, 30)) : fmtNumber(total), unitText(key))}
          ${statBox("有记录天数", `${new Set(lastDays(30).filter((d) => valueFor(key, d) != null)).size}`, "天")}
        </div>
      </section>
      <section class="panel panel-pad" style="margin-top:16px">
        <div class="panel-head"><div><h2 class="panel-title">最近 14 天，逐日看</h2><div class="panel-kicker">今天的数字，是明天那一眼的参照</div></div></div>
        <div style="overflow:auto"><table class="daily-table"><thead><tr><th>日期</th><th>数值</th>${key === "muscle" ? "" : "<th>组数</th>"}<th>较前一天</th></tr></thead><tbody>
          ${lastDays(14).slice().reverse().map((d) => {
            const v = valueFor(key, d);
            const p = valueFor(key, shiftDay(d, -1));
            let diff = "—";
            if (v != null && p != null) {
              const n = v - p;
              diff = n === 0 ? "持平" : `<span class="${n > 0 ? "up" : "down"}">${n > 0 ? "+" : ""}${fmtNumber(n, key === "muscle" ? 1 : 0)}</span>`;
            }
            return `<tr><td>${fmtDate(d)} · 周${weekdayShort(d)}</td><td class="num">${valueText(key, v)} ${esc(unitText(key))}</td>${key === "muscle" ? "" : `<td>${groupsFor(key, d)} 组</td>`}<td>${diff}</td></tr>`;
          }).join("")}
        </tbody></table></div>
      </section>
    </div>`;
  }

  function trendChartInner(key) {
    return `<div class="panel-head">
        <div><h2 class="panel-title">${esc(metric(key).name)} · 最近 30 天</h2><div class="panel-kicker">${key === "muscle" ? "记录值按公斤显示，断点表示那天没量" : "按项目配置换算成实际数量，悬停圆点可看当天数值"}</div></div>
        <button class="panel-link" data-page="projects">项目设置</button>
      </div>
      <div class="trend-chart-wrap">${lineChart(key, 30, 300)}</div>`;
  }

  function trendStatsInner(key) {
    const today = todayKey();
    const vals = lastDays(30).map((d) => valueFor(key, d));
    const valid = vals.filter((v) => v != null);
    const total = key === "muscle" ? null : valid.reduce((a, b) => a + b, 0);
    const recorded = new Set(lastDays(30).filter((d) => valueFor(key, d) != null)).size;
    return `<div class="trend-stats-head"><div><h2 class="panel-title">这 30 天的节奏</h2><div class="panel-kicker">今天、昨天、峰值、合计、有记录天数</div></div></div>
      <div class="stat-grid trend-stat-grid">
        ${statBox("今天", valueText(key, valueFor(key, today)), unitText(key))}
        ${statBox("昨天", valueText(key, valueFor(key, shiftDay(today, -1))), unitText(key))}
        ${statBox("30 天最高", valueText(key, maxOf(key, 30)), unitText(key))}
        ${statBox(key === "muscle" ? "30 天最低" : "30 天合计", key === "muscle" ? valueText(key, minOf(key, 30)) : fmtNumber(total), unitText(key))}
        ${statBox("有记录天数", `${recorded}`, "天")}
      </div>`;
  }

  function trendDailyInner(key) {
    return `<div class="panel-head">
        <div><h2 class="panel-title">最近 14 天，逐日看</h2><div class="panel-kicker">方框里可以上下滑，滚动条已经藏起来</div></div>
      </div>
      <div class="trend-daily-scroll scroll-hide"><table class="daily-table trend-daily-table"><thead><tr><th>日期</th><th>数值</th><th>较前一天</th></tr></thead><tbody>
        ${lastDays(14).slice().reverse().map((d) => {
          const v = valueFor(key, d);
          const p = valueFor(key, shiftDay(d, -1));
          let diff = "—";
          if (v != null && p != null) {
            const n = v - p;
            diff = n === 0 ? "持平" : `<span class="${n > 0 ? "up" : "down"}">${n > 0 ? "+" : ""}${fmtNumber(n, key === "muscle" ? 1 : 0)}</span>`;
          }
          return `<tr><td>${fmtDate(d)}<br><small>周${weekdayShort(d)}</small></td><td class="num">${valueText(key, v)} <small>${esc(unitText(key))}</small></td><td>${diff}</td></tr>`;
        }).join("")}
      </tbody></table></div>`;
  }

  function renderTrend() {
    if (!snapshotReady()) return emptyState();
    const key = state.activeMetric;
    return `<div class="${pageClass()} trend-page">
      <div class="trend-tabs" role="tablist">${ORDER.map((k) => `<button type="button" data-trend-metric="${k}" class="${k === key ? "on" : ""}">${esc(metric(k).name)}</button>`).join("")}</div>
      <div class="trend-layout">
        <div class="trend-main">
          <section class="panel panel-pad trend-chart-panel" id="trend-chart-panel">${trendChartInner(key)}</section>
          <section class="panel panel-pad trend-stats-panel" id="trend-stats-panel">${trendStatsInner(key)}</section>
        </div>
        <section class="panel panel-pad trend-daily-panel" id="trend-daily-panel">${trendDailyInner(key)}</section>
      </div>
    </div>`;
  }

  function renderProjects() {
    if (!snapshotReady()) return emptyState();
    return `<div class="page-enter">
      <div class="stat-grid">
        ${statBox("固定项目", "5", "个", "名称 / 每组 / 目标 / 纵轴")}
        ${statBox("已完成目标", ORDER.filter((k) => k !== "muscle" && goalPercent(k) >= 100).length, "个", "按当前累计")}
        ${statBox("全部记录", fmtNumber(recordsSummary().entries), "条", `${recordedDays()} 天`)}
        ${statBox("最短路径", "双击", "", "编辑项目内容")}
        ${statBox("清空影响", "0", "个项目", "只清记录，不动配置")}
      </div>
      <section class="panel panel-pad">
        <div class="panel-head"><div><h2 class="panel-title">五个项目</h2><div class="panel-kicker">单击进入详情 · 双击编辑 · 项目配置是固定的，不会被清空</div></div></div>
        ${projectsGrid()}
        <div style="margin-top:14px;padding:12px 14px;border-radius:14px;background:rgba(243,236,254,.7);color:var(--ink-3);font-size:10.5px;line-height:1.6">
          项目名称、每组数量、单位、目标、纵轴上限都存在树莓派里。清空数据只会把「今天 / 历史记录」归零，不会动这五行配置。
        </div>
      </section>
    </div>`;
  }

  function renderFun() {
    if (!snapshotReady() && !state.notes.length) return emptyState();
    const highlights = buildHighlights(8);
    return `<div class="page-enter">
      <div class="section-title-row"><div><h2 class="section-title">数据自己长出来的</h2><div class="section-sub">今天比昨天、最近峰值、连续记录，都在这里</div></div></div>
      <div class="fun-grid">${highlights.map((it, i) => `<article class="fun-card"><div class="fc-kicker">自动看点 ${String(i + 1).padStart(2, "0")}</div><h3>${esc(it.title)}</h3><p>${esc(it.text)}</p></article>`).join("")}</div>
      <section class="panel panel-pad">
        <div class="panel-head"><div><h2 class="panel-title">我记的趣事</h2><div class="panel-kicker">存在这台 Windows 电脑上，不写进树莓派数据库</div></div><span class="pill">${state.notes.length} 条</span></div>
        ${state.notes.length ? `<div class="notes-list">${state.notes.map((n) => `<div class="note-item"><div class="nt">${esc(n.text)}<div class="nd">${esc(n.day || "")}</div></div><button data-note-delete="${esc(n.id)}" title="删除这条">×</button></div>`).join("")}</div>` : `<div class="fun-empty"><span class="big">✎</span>还没有手写趣事。今天有什么想留下的，写一条。</div>`}
        <form class="note-form" id="note-form"><textarea id="note-text" maxlength="200" placeholder="比如：今天俯卧撑第一次做到 6 组，手臂有点抖但很爽。"></textarea><button class="btn btn-primary" type="submit">记下来</button></form>
      </section>
    </div>`;
  }

  function renderData() {
    const summary = recordsSummary();
    const canClear = !!(state.online && state.config && state.config.hasToken);
    const host = state.config ? state.config.host : "—";
    const port = state.config ? state.config.port : "—";
    const version = state.snapshot && state.snapshot.version ? state.snapshot.version : "—";
    return `<div class="page-enter">
      <section class="danger-zone">
        <div class="danger-icon">!</div>
        <div class="danger-copy"><h2>清空所有数据</h2><p>把今天、历史、肌肉重量记录、体脂率记录和本机趣事全部归零。五个项目的名称、每组、单位、目标、纵轴上限都保留，不会被清空。</p></div>
        <button class="btn btn-danger btn-lg" data-action="clear" ${canClear ? "" : "disabled"}>清空所有数据</button>
      </section>
      <div class="data-grid">
        <section class="panel data-card">
          <h3>当前连接</h3><p>Windows 这边只做展示和转发，Token 不写进页面。</p>
          <div class="kv-list">
            <div class="kv"><span>树莓派</span><b>${esc(host)}:${esc(port)}</b></div>
            <div class="kv"><span>状态</span><b style="color:${state.online ? "var(--green)" : "var(--red-dk)"}">${state.online ? "已连接" : "未连接"}</b></div>
            <div class="kv"><span>服务版本</span><b>${esc(version)}</b></div>
            <div class="kv"><span>最近刷新</span><b>${fmtTime(state.lastUpdated)}</b></div>
            <div class="kv"><span>记录规模</span><b>${summary.days} 天 · ${summary.entries} 条</b></div>
            <div class="kv"><span>体脂记录</span><b>${summary.bodyFatEntries} 条独立记录</b></div>
          </div>
          <div style="margin-top:15px"><button class="btn btn-ghost" data-action="open-settings">修改连接设置</button></div>
        </section>
        <section class="panel data-card">
          <h3>会保留的五个项目</h3><p>清空只清记录，不碰这些默认配置。</p>
          <div class="config-list">${ORDER.map((k) => { const m = metric(k); return `<div class="config-row"><div><b>${esc(m.name)}</b><br><small>${esc(perText(k))}</small></div><div style="text-align:right"><small>${k === "muscle" ? "记录值" : `目标 ${fmtNumber(m.goal)}`}</small><br><small>纵轴 ${m.maxGroups || "—"}</small></div></div>`; }).join("")}</div>
        </section>
        <section class="panel data-card">
          <h3>清空时到底发生什么</h3><p>说清楚，按下去才不慌。</p>
          <div class="kv-list">
            <div class="kv"><span>今天记录</span><b>归零</b></div>
            <div class="kv"><span>历史记录</span><b>归零</b></div>
            <div class="kv"><span>肌肉重量记录</span><b>归零</b></div>
            <div class="kv"><span>体脂率记录</span><b>归零</b></div>
            <div class="kv"><span>本机手写趣事</span><b>清空</b></div>
            <div class="kv"><span>项目名称 / 每组 / 目标 / 纵轴</span><b style="color:var(--green)">保留</b></div>
          </div>
          <div style="margin-top:15px"><button class="btn btn-danger-ghost" data-action="export">导出当前快照</button></div>
        </section>
        <section class="panel data-card">
          <h3>旧版服务端说明</h3><p>v1.3.0 服务端提供物理清空接口，会真正删除记录表和体脂记录。若仍是旧服务端，客户端会退回 5 条“归零”指令：界面归零，但旧数据仍留在树莓派里。</p>
          <div class="kv-list">
            <div class="kv"><span>界面显示</span><b>归零</b></div>
            <div class="kv"><span>树莓派里的旧记录</span><b>旧版保留 / 新版可清</b></div>
            <div class="kv"><span>手机端</span><b>下次同步自动归零</b></div>
          </div>
        </section>
      </div>
    </div>`;
  }

  /* 把「界面上真正显示的东西」压成一个指纹：只有数据变了才重绘整页，
     避免 20 秒轮询每次都把 #content 整块换掉、页面看起来抖一下。
     快照里的 serverTime 每次请求都会变，绝不能算进指纹。 */
  function dataSignature() {
    const snap = state.snapshot;
    const snapSig = snap ? [
      snap.version || "",
      snap.revision || 0,
      snap.seeded ? 1 : 0,
      JSON.stringify(snap.metrics || null),
      JSON.stringify(snap.bodyFatRecords || null),
    ].join("~") : "";
    return [
      state.page,
      state.activeMetric,
      state.bodyFatRange,
      state.online ? "on" : "off",
      state.loadError || "",
      state.config ? (state.config.baseUrl || "") : "",
      state.config && state.config.hasToken ? "t" : "x",
      snapSig,
      JSON.stringify(state.notes || []),
    ].join("\u0001");
  }

  function render(options = {}) {
    const animate = options.animate === true || state.animatePage === true;
    state.animatePage = animate;
    const meta = pageTitle();
    const keepScroll = animate ? 0 : (window.scrollY || 0);
    document.body.dataset.page = state.page;
    $("#page-title").textContent = meta.title;
    $("#page-sub").textContent = meta.sub;
    $$(".nav-btn").forEach((btn) => btn.classList.toggle("active", btn.dataset.page === state.page));
    renderConnection();
    const body = {
      overview: renderOverview,
      today: renderToday,
      trend: renderTrend,
      bodyfat: renderBodyFat,
      projects: renderProjects,
      fun: renderFun,
      data: renderData,
    }[state.page] || renderOverview;
    $("#content").innerHTML = pageBanner() + body();
    if (keepScroll) window.scrollTo(0, keepScroll);
    state.animatePage = false;
    state.renderSig = dataSignature();
  }

  function renderConnection() {
    const hasToken = !!(state.config && state.config.hasToken);
    const base = state.config ? state.config.baseUrl : "";
    const version = state.snapshot && state.snapshot.version;
    const dot = $("#conn-dot");
    const text = $("#conn-text");
    const sub = $("#conn-sub");
    const pill = $("#conn-pill");
    const pillText = $("#conn-pill-text");
    dot.className = "conn-dot";
    if (state.online) {
      dot.classList.add("on");
      text.textContent = "已连接";
      sub.textContent = `${base.replace(/^http:\/\//, "")}${version ? ` · v${version}` : ""}`;
      pill.classList.add("ok");
      pill.classList.remove("bad");
      pillText.textContent = "树莓派已连接";
    } else if (state.loadError) {
      dot.classList.add("bad");
      text.textContent = "连接异常";
      sub.textContent = state.loadError.length > 34 ? state.loadError.slice(0, 32) + "…" : state.loadError;
      pill.classList.add("bad");
      pill.classList.remove("ok");
      pillText.textContent = "连接异常";
    } else {
      text.textContent = hasToken ? "待连接" : "未设置";
      sub.textContent = base ? base.replace(/^http:\/\//, "") : "点下面填写地址和 Token";
      pill.classList.remove("ok", "bad");
      pillText.textContent = hasToken ? "待连接" : "未连接";
    }
  }

  async function api(path, options = {}) {
    const opts = Object.assign({ headers: {} }, options);
    if (opts.body && !opts.headers["Content-Type"]) opts.headers["Content-Type"] = "application/json";
    const res = await fetch(path, opts);
    let data = {};
    try { data = await res.json(); } catch (_) { data = { ok: false, error: `本机服务返回 ${res.status}` }; }
    if (!res.ok || data.ok === false) {
      const err = new Error(data.error || `请求失败（${res.status}）`);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  async function load(options = {}) {
    if (state.loading) return;
    state.loading = true;
    try {
      if (!state.config) {
        state.config = await api("/api/config");
        state.notes = state.config.notes || [];
      }
      if (!state.config.hasToken) {
        state.online = false;
        state.loadError = "";
        if (!options.silent) render();
        if (!state.settingsPrompted) {
          state.settingsPrompted = true;
          setTimeout(() => { if (!state.modalOpen) openSettings(); }, 260);
        }
        return;
      }
      try {
        const snap = await api("/api/snapshot");
        state.snapshot = snap;
        state.online = true;
        state.loadError = "";
        state.lastUpdated = Date.now();
      } catch (err) {
        state.online = false;
        state.loadError = err.message || "连接失败";
      }
      try {
        const notesResult = await api("/api/notes");
        state.notes = notesResult.notes || [];
        if (state.config) state.config.notes = state.notes;
      } catch (_) { /* 趣事失败不影响主数据 */ }
    } catch (err) {
      state.loadError = err.message || "本机服务没有启动";
      state.online = false;
    } finally {
      state.loading = false;
      const sig = dataSignature();
      if (sig !== state.renderSig) {
        if (state.modalOpen) {
          // 弹窗开着的时候不重绘，等用户关掉再来一次，避免弹窗背后的页面闪。
        } else {
          render({ animate: false });
        }
      }
    }
  }

  function navigate(page) {
    state.page = page;
    state.animatePage = true;
    render({ animate: true });
    window.scrollTo({ top: 0, behavior: "auto" });
  }

  function toast(message, kind = "") {
    const el = $("#toast");
    el.textContent = message;
    el.className = `toast show ${kind}`.trim();
    clearTimeout(el._timer);
    el._timer = setTimeout(() => { el.className = "toast"; }, 2600);
  }

  function openModal(html, className = "") {
    state.modalOpen = true;
    state.backdropArmed = false;
    $("#modal-root").innerHTML = `<div class="modal-backdrop"><div class="modal ${className}" role="dialog" aria-modal="true">${html}</div></div>`;
    document.body.style.overflow = "hidden";
    const backdrop = $("#modal-root .modal-backdrop");
    if (backdrop) {
      // 点卡片外面的灰底就关掉。用 mousedown + mouseup 配对，
      // 避免在卡片里按下、拖到外面松手时误关。
      backdrop.addEventListener("mousedown", (event) => {
        state.backdropArmed = event.target === backdrop;
      });
      backdrop.addEventListener("mouseup", (event) => {
        if (state.backdropArmed && event.target === backdrop) closeModal();
        state.backdropArmed = false;
      });
    }
    setTimeout(() => {
      const first = $("#modal-root input, #modal-root button, #modal-root textarea");
      if (first) first.focus();
    }, 50);
  }

  function closeModal() {
    state.modalOpen = false;
    state.backdropArmed = false;
    $("#modal-root").innerHTML = "";
    document.body.style.overflow = "";
  }

  function openSettings() {
    const cfg = state.config || {};
    openModal(`<div class="modal-head"><div class="modal-icon">⌁</div><div><h2>连接树莓派</h2><p class="m-sub">日刻的数据存在树莓派里。填上它的地址和口令，电脑这边就能读数据、改项目、清记录。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
      <form id="settings-form">
        <div class="form-grid">
          <div class="field"><label for="set-host">树莓派 IP / 主机</label><input id="set-host" name="host" value="${esc(cfg.host || "")}" placeholder="192.168.1.50" autocomplete="off"><div class="hint">只填 IP 或主机名，不用带 http://</div></div>
          <div class="field"><label for="set-port">端口</label><input id="set-port" name="port" type="number" min="1" max="65535" value="${esc(cfg.port || 8787)}"><div class="hint">Docker 映射出来的端口，默认 8787</div></div>
          <div class="field full"><label for="set-token">访问口令（Token）</label><input id="set-token" name="token" type="password" value="${esc(cfg.token || DEFAULT_TOKEN)}" placeholder="和树莓派同步服务里设置的一致" autocomplete="off"><div class="hint">填一次就存在本机 %APPDATA%\\DayTallyDesktop\\config.json，下次打开自动填好，不会写进网页。</div></div>
        </div>
        <div class="test-result" id="test-result">${state.loadError ? esc(state.loadError) : "还没有测试"}</div>
        <div class="modal-actions"><button class="btn btn-ghost" type="button" data-action="test">测试连接</button><button class="btn btn-primary" type="submit">保存并连接</button></div>
      </form>`);
  }

  async function saveSettings(form) {
    const host = form.host.value.trim();
    const port = Number(form.port.value);
    const token = form.token.value.trim() || DEFAULT_TOKEN;
    if (!host) throw new Error("先填树莓派 IP");
    if (!port || port < 1 || port > 65535) throw new Error("端口不对");
    if (!token) throw new Error("访问口令不能为空");
    const cfg = await api("/api/config", { method: "POST", body: JSON.stringify({ host, port, token }) });
    state.config = cfg;
    state.notes = cfg.notes || [];
    return cfg;
  }

  async function testConnection() {
    const form = $("#settings-form");
    const result = $("#test-result");
    if (!form || !result) return;
    result.className = "test-result";
    result.textContent = "正在连接…";
    try {
      await saveSettings(form);
      const data = await api("/api/test", { method: "POST", body: JSON.stringify({}) });
      const h = data.health || {};
      result.className = "test-result ok";
      result.textContent = `连上了：${h.service || "daytally-sync"} · v${data.version || h.version || "?"} · ${data.metrics || 0} 个项目`;
      state.online = true;
      state.loadError = "";
      renderConnection();
    } catch (err) {
      result.className = "test-result bad";
      result.textContent = err.message || "连接失败";
      state.online = false;
      state.loadError = err.message || "连接失败";
      renderConnection();
    }
  }

  async function submitSettings(event) {
    event.preventDefault();
    try {
      await saveSettings(event.target);
      closeModal();
      toast("连接设置已保存", "ok");
      await load();
    } catch (err) {
      toast(err.message || "保存失败", "bad");
    }
  }

  function openClearModal() {
    if (!state.online || !state.config || !state.config.hasToken) {
      toast("先连上树莓派，再清数据", "bad");
      return;
    }
    const summary = recordsSummary();
    openModal(`<div class="modal-head"><div class="modal-icon">!</div><div><h2>清空所有数据？</h2><p class="m-sub">这个操作会把现有记录归零，做完之后不能从客户端撤回。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
      <div class="warn-list">
        <div class="warn-box delete"><strong>会被清掉</strong><span>今天记录、历史记录、肌肉重量记录、体脂率记录、本机手写趣事；当前约 ${summary.entries} 条刻度、${summary.bodyFatEntries} 条体脂记录、${summary.days} 天。</span></div>
        <div class="warn-box"><strong>会保留</strong><span>五个项目的名称、每组数量、单位、目标、纵轴上限。项目本身不会被删。</span></div>
      </div>
      <label class="check-line"><input type="checkbox" id="clear-ack"> 我知道清空后现有数据归零，且不能从客户端撤回。</label>
      <div class="modal-actions"><button class="btn btn-ghost" data-action="close-modal" type="button">取消</button><button class="btn btn-danger" id="clear-confirm" data-action="clear-confirm" type="button" disabled>确认清空</button></div>`, "danger-modal");
    $("#clear-ack").addEventListener("change", (e) => {
      $("#clear-confirm").disabled = !e.target.checked;
    });
  }

  async function clearAll() {
    const button = $("#clear-confirm");
    if (!button || button.disabled) return;
    button.disabled = true;
    button.textContent = "正在清空…";
    try {
      const result = await api("/api/clear", { method: "POST", body: JSON.stringify({ confirm: "CLEAR_DATA" }) });
      state.snapshot = result.snapshot || null;
      state.notes = [];
      state.online = true;
      state.loadError = "";
      state.lastUpdated = Date.now();
      closeModal();
      toast(result.mode === "purge" ? "数据库记录已清空，项目配置保留" : "记录已归零，项目配置保留", "ok");
      navigate("data");
      await load({ silent: true });
    } catch (err) {
      button.disabled = false;
      button.textContent = "确认清空";
      toast(err.message || "清空失败", "bad");
    }
  }

  function openProjectDetail(key) {
    const m = metric(key);
    const total = totalFor(key);
    const today = valueFor(key, todayKey());
    const max = maxOf(key, 30);
    const days = new Set(lastDays(30).filter((d) => valueFor(key, d) != null)).size;
    openModal(`<div class="modal-head"><div class="modal-icon" style="background:${TONE[key].glow};color:${TONE[key].accent}">${key === "muscle" ? "↥" : "▦"}</div><div><h2>${esc(m.name)}</h2><p class="m-sub">${esc(perText(key))} · ${key === "muscle" ? "记录值" : `目标 ${fmtNumber(m.goal)} ${esc(m.unit)}`}</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
      <div class="stat-grid" style="grid-template-columns:repeat(3,1fr)">
        ${statBox("累计", valueText(key, total), m.unit)}
        ${statBox("今天", valueText(key, today), m.unit)}
        ${statBox("30 天最高", valueText(key, max), m.unit)}
      </div>
      <div style="height:230px;margin:12px 0 4px">${lineChart(key, 30, 230)}</div>
      <div class="kv-list">
        <div class="kv"><span>每组数量</span><b>${key === "muscle" ? "记录值，不按组" : `${m.per} ${esc(m.unit)}`}</b></div>
        <div class="kv"><span>目标</span><b>${m.goal ? `${fmtNumber(m.goal)} ${esc(m.unit)}` : "未设置"}</b></div>
        <div class="kv"><span>纵轴上限</span><b>${m.maxGroups ? `${m.maxGroups} 组` : "—"}</b></div>
        <div class="kv"><span>30 天有记录</span><b>${days} 天</b></div>
      </div>
      <div class="modal-actions" style="margin-top:16px"><button class="btn btn-ghost" data-action="close-modal" type="button">关闭</button>${key === "muscle" ? "" : `<button class="btn btn-primary" data-action="edit-project" data-key="${key}" type="button">编辑项目</button>`}</div>`, "wide");
  }

  function openProjectEdit(key) {
    if (key === "muscle") {
      toast("肌肉重量是记录值，没有每组 / 目标可编辑", "bad");
      return;
    }
    const m = metric(key);
    openModal(`<div class="modal-head"><div class="modal-icon" style="background:${TONE[key].glow};color:${TONE[key].accent}">✎</div><div><h2>编辑 ${esc(m.name)}</h2><p class="m-sub">只改这个项目的配置。清空数据不会碰这里。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
      <form id="project-form" data-key="${key}">
        <div class="form-grid">
          <div class="field"><label for="p-name">项目名</label><input id="p-name" name="name" maxlength="8" value="${esc(m.name)}" required></div>
          <div class="field"><label for="p-unit">单位</label><input id="p-unit" name="unit" maxlength="8" value="${esc(m.unit)}" required></div>
          <div class="field"><label for="p-per">每组数量</label><input id="p-per" name="per" type="number" min="1" max="999" value="${m.per || 1}" required><div class="hint">手机上加一组，就按这个数往上加</div></div>
          <div class="field"><label for="p-goal">目标值</label><input id="p-goal" name="goal" type="number" min="0" max="100000000" value="${m.goal || 0}"><div class="hint">填 0 表示不显示目标进度</div></div>
          <div class="field full"><label for="p-max">纵轴上限（组）</label><input id="p-max" name="maxGroups" type="number" min="1" max="999" value="${m.maxGroups || 32}" required><div class="hint">手机趋势图的纵轴最多显示多少组</div></div>
        </div>
        <div class="warn-box" style="margin-top:14px"><strong>保存后会发生什么</strong><span>只更新这一个项目的配置，并同步给树莓派；今天和历史计数不变。</span></div>
        <div class="modal-actions" style="margin-top:16px"><button class="btn btn-ghost" data-action="close-modal" type="button">取消</button><button class="btn btn-primary" type="submit">保存项目</button></div>
      </form>`, "wide");
  }

  async function submitProject(event) {
    event.preventDefault();
    const form = event.target;
    const key = form.dataset.key;
    const config = {
      name: form.name.value.trim(),
      unit: form.unit.value.trim(),
      per: Number(form.per.value),
      goal: Number(form.goal.value || 0),
      maxGroups: Number(form.maxGroups.value),
    };
    try {
      const result = await api("/api/project", { method: "POST", body: JSON.stringify({ metric: key, config }) });
      if (result.snapshot) state.snapshot = result.snapshot;
      closeModal();
      toast(`${config.name} 的项目配置已保存`, "ok");
      await load({ silent: true });
    } catch (err) {
      toast(err.message || "保存失败", "bad");
    }
  }

  async function addNote(event) {
    event.preventDefault();
    const text = $("#note-text");
    if (!text || !text.value.trim()) return;
    try {
      const result = await api("/api/notes", { method: "POST", body: JSON.stringify({ text: text.value }) });
      state.notes = result.notes || [];
      render();
      toast("趣事已记下", "ok");
    } catch (err) {
      toast(err.message || "没记上", "bad");
    }
  }

  async function deleteNote(id) {
    try {
      const result = await api("/api/notes/delete", { method: "POST", body: JSON.stringify({ id }) });
      state.notes = result.notes || [];
      render();
    } catch (err) {
      toast(err.message || "删除失败", "bad");
    }
  }

  function exportSnapshot() {
    if (!state.snapshot) {
      toast("还没有可导出的快照", "bad");
      return;
    }
    const data = JSON.stringify(state.snapshot, null, 2);
    const blob = new Blob([data], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `日刻快照-${todayKey()}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast("快照已导出", "ok");
  }

  function handleProjectClick(key) {
    clearTimeout(state.clickTimer);
    state.clickTimer = setTimeout(() => openProjectDetail(key), 230);
  }

  /* 只重画跟指标有关的那一块，不再整页重绘：切「肌肉重量 / 引体向上」时页面不会闪 */
  function applyMetric(key) {
    if (!ORDER.includes(key)) return;
    state.activeMetric = key;
    $$("[data-trend-metric]").forEach((btn) => btn.classList.toggle("on", btn.dataset.trendMetric === key));
    if (state.page === "overview") {
      const panel = $("#chart-panel");
      if (panel) panel.innerHTML = chartPanelInner(key);
      return;
    }
    if (state.page === "trend") {
      const chart = $("#trend-chart-panel");
      const stats = $("#trend-stats-panel");
      const daily = $("#trend-daily-panel");
      if (chart) chart.innerHTML = trendChartInner(key);
      if (stats) stats.innerHTML = trendStatsInner(key);
      if (daily) daily.innerHTML = trendDailyInner(key);
      return;
    }
    render({ animate: false });
  }

  function handleDocumentClick(event) {
    const actionEl = event.target.closest("[data-action]");
    if (actionEl) {
      const action = actionEl.dataset.action;
      if (action === "close-modal") { closeModal(); return; }
      if (action === "open-settings") { openSettings(); return; }
      if (action === "goto-fun") { navigate("fun"); return; }
      if (action === "clear") { openClearModal(); return; }
      if (action === "clear-confirm") { clearAll(); return; }
      if (action === "test") { testConnection(); return; }
      if (action === "edit-project") { openProjectEdit(actionEl.dataset.key); return; }
      if (action === "export") { exportSnapshot(); return; }
      if (action === "bf-date-clear") { state.bodyFatDate = ""; render(); return; }
    }

    const bfRange = event.target.closest("[data-bf-range]");
    if (bfRange) {
      state.bodyFatRange = bfRange.dataset.bfRange;
      render();
      return;
    }

    const noteDelete = event.target.closest("[data-note-delete]");
    if (noteDelete) { deleteNote(noteDelete.dataset.noteDelete); return; }

    const trend = event.target.closest("[data-trend-metric]");
    if (trend) {
      applyMetric(trend.dataset.trendMetric);
      return;
    }

    const metricEl = event.target.closest("[data-metric]");
    if (metricEl && !event.target.closest("[data-project]")) {
      if (metricEl.dataset.metric !== state.activeMetric) applyMetric(metricEl.dataset.metric);
      openProjectDetail(metricEl.dataset.metric);
      return;
    }

    const projectEl = event.target.closest("[data-project]");
    if (projectEl) {
      handleProjectClick(projectEl.dataset.project);
      return;
    }

    const pageEl = event.target.closest("[data-page]");
    if (pageEl) { navigate(pageEl.dataset.page); return; }

    if (event.target.classList && event.target.classList.contains("modal-backdrop")) closeModal();
  }

  function handleDocumentDoubleClick(event) {
    const projectEl = event.target.closest("[data-project]");
    if (!projectEl) return;
    clearTimeout(state.clickTimer);
    openProjectEdit(projectEl.dataset.project);
  }

  function handleDocumentChange(event) {
    if (event.target && event.target.id === "bf-date") {
      state.bodyFatDate = event.target.value || "";
      render();
    }
  }

  function handleSubmit(event) {
    if (event.target.id === "settings-form") { submitSettings(event); return; }
    if (event.target.id === "project-form") { submitProject(event); return; }
    if (event.target.id === "note-form") { addNote(event); return; }
  }

  function init() {
    document.addEventListener("click", handleDocumentClick);
    document.addEventListener("dblclick", handleDocumentDoubleClick);
    document.addEventListener("change", handleDocumentChange);
    document.addEventListener("submit", handleSubmit);
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && state.modalOpen) closeModal();
    });
    $("#refresh").addEventListener("click", () => load());
    $("#open-settings").addEventListener("click", openSettings);
    $("#settings-top").addEventListener("click", openSettings);
    $("#conn-pill").addEventListener("click", openSettings);
    load();
    setInterval(() => {
      if (!document.hidden && state.config && state.config.hasToken && !state.modalOpen && !state.loading) load({ silent: true });
    }, 20000);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
