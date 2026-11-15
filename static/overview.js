/* 总览：30 天柱状 + 折线 + 双环 + 流水表，三块互相联动。
   数据来自 Java 侧的 window.OV，这里只负责画与联动，不查库、不猜数。
   联动：点某一日的柱子 → 下面表格只显示那一天；
        切到「全年每月」后点某个月 → 双环换成那个月，表格默认显示那个月最后一天。 */
(function () {
  "use strict";

  const OV = window.OV;
  if (!OV) return;

  const $ = (id) => document.getElementById(id);
  const fmt = (n) => Number(n).toLocaleString("zh-CN", { maximumFractionDigits: 0 });
  const fmt2 = (n) => Number(n).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const NS = "http://www.w3.org/2000/svg";
  const el = (name, attrs) => {
    const e = document.createElementNS(NS, name);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  };

  const RAMP = ["#ff8a1e", "#e3e6ec", "#b6becb", "#8b94a5", "#626b7c", "#414a5a"];
  const TOPN = 5;
  const colorOf = (rank) => RAMP[Math.min(rank, RAMP.length - 1)];

  // 当前在看哪个月 / 哪一天
  const state = {
    month: OV.month,
    day: null,
    data: { month: OV.month, cats: OV.cats || [], types: OV.types || [], expense: OV.expense || 0, rows: OV.rows || [] },
    daily: OV.daily || [],
    today: OV.today || OV.daily.length,
  };

  /* ---------------- 表格 ---------------- */
  function sumOf(rows) { return rows.reduce((a, r) => a + r.p, 0); }

  function renderTable(rows, note) {
    const body = $("recentBody");
    if (!body) return;
    body.innerHTML = "";
    const list = rows.slice(0, 60);
    if (!list.length) {
      const tr = document.createElement("tr");
      const td = document.createElement("td");
      td.colSpan = 6;
      td.className = "empty";
      td.textContent = "这一天没有记录";
      tr.appendChild(td);
      body.appendChild(tr);
    }
    const rank = {};
    (state.data.cats || []).forEach((c, i) => { rank[c.n] = c.n === "其他" ? RAMP[RAMP.length - 1] : colorOf(i); });

    list.forEach((r, i) => {
      const tr = document.createElement("tr");
      const tdId = document.createElement("td"); tdId.className = "id"; tdId.textContent = String(i + 1).padStart(6, "0");
      const tdDt = document.createElement("td"); tdDt.className = "dt"; tdDt.textContent = state.month + "-" + String(r.d).padStart(2, "0");
      const tdTag = document.createElement("td"); tdTag.className = "tag";
      const tick = document.createElement("span"); tick.className = "tick"; tick.style.background = rank[r.c] || "#414a5a";
      tdTag.appendChild(tick); tdTag.appendChild(document.createTextNode(r.c));
      const tdType = document.createElement("td"); tdType.className = "type";
      const badge = document.createElement("span");
      badge.className = r.n === 1 ? "t1" : "t2";
      badge.textContent = r.n === 1 ? "日常" : "特殊";
      tdType.appendChild(badge);
      const tdNote = document.createElement("td"); tdNote.className = "note"; tdNote.textContent = r.g;
      const tdAmt = document.createElement("td"); tdAmt.className = "amt out"; tdAmt.textContent = "-¥" + fmt2(r.p);
      tr.append(tdId, tdDt, tdTag, tdType, tdNote, tdAmt);
      body.appendChild(tr);
    });

    const noteEl = $("recentNote");
    if (noteEl) noteEl.textContent = note;
    const reset = $("recentReset");
    if (reset) reset.hidden = !(state.day !== null);
  }

  function filterDay(d) {
    state.day = d;
    const rows = state.data.rows.filter((r) => r.d === d);
    renderTable(rows, state.month + " 月 " + d + " 日 · " + rows.length + " 笔 · ¥" + fmt2(sumOf(rows)));
  }

  function resetTable() {
    state.day = null;
    renderTable(state.data.rows.slice(0, 6), "本月 · 共 " + state.data.rows.length + " 条");
  }

  /* ---------------- 柱状图 ---------------- */
  function drawFlow() {
    const svg = $("flow");
    if (!svg) return;
    const meta = $("flowMeta"), seg = $("flowSeg"), legAvg = $("legAvg");
    const label = $("flowLabel"), legTxt = $("legDailyTxt");
    const W = 1000, H = 190, padL = 10, padR = 46, top = 14, base = H - 30;
    let mode = "day";

    const yOf = (v, max) => base - (v / max) * (base - top);

    function grid(peak) {
      [0, 0.5, 1].forEach((r) => {
        const y = yOf(peak * r, peak * 1.15);
        svg.appendChild(el("line", {
          x1: padL, y1: y.toFixed(1), x2: W - padR, y2: y.toFixed(1),
          stroke: "rgba(155,180,225," + (r === 0 ? ".24" : ".1") + ")", "stroke-width": 1,
        }));
        const t = el("text", {
          x: W - padR + 6, y: (y + 3.5).toFixed(1), fill: "rgba(154,163,181,.75)",
          "font-size": 9, "font-family": "var(--mono)",
        });
        t.textContent = r === 0 ? "0" : "¥" + fmt(peak * r);
        svg.appendChild(t);
      });
    }

    function col(i, slot, bw, h, fill) {
      const x = padL + slot * i + (slot - bw) / 2;
      const bar = el("rect", {
        x: x.toFixed(1), y: (base - h).toFixed(1), width: bw.toFixed(1), height: Math.max(0, h).toFixed(1), fill,
      });
      bar.style.transition = "fill .12s linear";
      svg.appendChild(bar);
      return bar;
    }

    function hot(i, slot, onText, offText, onEnter, onLeave, onClick, tip) {
      const hit = el("rect", {
        x: (padL + slot * i).toFixed(1), y: top, width: slot.toFixed(1), height: (base - top).toFixed(1), fill: "transparent",
      });
      hit.style.cursor = "pointer";
      hit.addEventListener("pointerenter", () => { onEnter(); if (meta) meta.textContent = onText; });
      hit.addEventListener("pointerleave", () => { onLeave(); if (meta) meta.textContent = offText; });
      if (onClick) {
        hit.addEventListener("click", onClick);
        const t = el("title", {}); t.textContent = tip || onText; hit.appendChild(t);
      }
      svg.appendChild(hit);
    }

    /* ---- 本月每日 ---- */
    function drawDay() {
      const data = state.daily.slice(0, Math.max(1, state.today));
      const n = data.length;
      const peak = Math.max(1, ...data);
      const slot = (W - padL - padR) / n;
      const bw = Math.max(3, Math.min(slot * 0.62, 26));
      const def = "点柱子看当天 · 日均 ¥" + fmt(data.reduce((a, b) => a + b, 0) / Math.max(1, state.today)) + " · 峰值 ¥" + fmt(peak);
      if (meta) meta.textContent = def;
      if (legAvg) legAvg.hidden = false;
      if (label) label.textContent = state.month + " 月每日支出 / DAILY";
      if (legTxt) legTxt.textContent = "每日支出 DAILY";

      grid(peak);
      data.forEach((v, i) => {
        const isSel = state.day === i + 1;
        const isToday = (i + 1) === state.today;
        const h = (v / (peak * 1.15)) * (base - top);
        const baseFill = v > 0 ? (isSel ? "#ffb066" : isToday ? "#ff8a1e" : "rgba(255,138,30,.72)") : "rgba(255,138,30,.12)";
        const bar = col(i, slot, bw, h, baseFill);
        hot(i, slot,
          (i + 1) + " 日 · ¥" + fmt2(v) + " · 点击看当天", def,
          () => bar.setAttribute("fill", "#ffb066"),
          () => bar.setAttribute("fill", baseFill),
          () => filterDay(i + 1),
          "点击：表格只看这一天");
      });

      const pts = [];
      for (let i = 0; i < n; i++) {
        let sum = 0, cnt = 0;
        for (let k = Math.max(0, i - 6); k <= i; k++) { sum += data[k]; cnt++; }
        pts.push((padL + slot * i + slot / 2).toFixed(1) + "," + yOf(sum / cnt, peak * 1.15).toFixed(1));
      }
      svg.appendChild(el("polyline", {
        points: pts.join(" "), fill: "none", stroke: "rgba(200,210,230,.62)",
        "stroke-width": 1, "stroke-dasharray": "3 4",
      }));

      const marks = new Set([1, state.today]);
      for (let d = 5; d < state.today; d += 5) marks.add(d);
      marks.forEach((d) => {
        const i = Math.min(n - 1, d - 1);
        const t = el("text", {
          x: (padL + slot * i + slot / 2).toFixed(1), y: base + 14, "text-anchor": "middle",
          "font-size": 9, "font-family": "var(--mono)",
          fill: d === state.today ? "rgba(255,138,30,.9)" : "rgba(154,163,181,.6)",
        });
        t.textContent = d + "日";
        svg.appendChild(t);
      });
    }

    /* ---- 全年每月 ---- */
    function drawMonth() {
      const months = OV.months || [];
      const cur = OV.month || 12;
      const peak = Math.max(1, ...months);
      const slot = (W - padL - padR) / 12;
      const bw = Math.max(10, Math.min(slot * 0.5, 40));
      const past = months.slice(0, cur).reduce((a, b) => a + b, 0);
      const def = "点柱子换月 · 全年 ¥" + fmt(past) + " · 月均 ¥" + fmt(past / Math.max(1, cur));
      if (meta) meta.textContent = def;
      if (legAvg) legAvg.hidden = true;
      if (label) label.textContent = "全年每月支出 / MONTHLY";
      if (legTxt) legTxt.textContent = "每月支出 MONTHLY";

      grid(peak);
      for (let i = 0; i < 12; i++) {
        const v = months[i] || 0;
        const m = i + 1;
        const isCur = m === cur;
        const isSel = m === state.month;
        const future = m > cur;
        const h = (v / (peak * 1.15)) * (base - top);
        const baseFill = future ? "transparent" : (isSel ? "#ffb066" : isCur ? "#ff8a1e" : "rgba(255,138,30,.72)");
        const bar = col(i, slot, bw, h, baseFill);
        hot(i, slot,
          m + " 月 · " + (future ? "未到" : "¥" + fmt2(v)) + " · 点击切换", def,
          () => { if (!future) bar.setAttribute("fill", "#ffb066"); },
          () => { if (!future) bar.setAttribute("fill", baseFill); },
          () => { if (!future) switchMonth(m); },
          "点击：双环与表格切到 " + m + " 月");

        const t = el("text", {
          x: (padL + slot * i + slot / 2).toFixed(1), y: base + 14, "text-anchor": "middle",
          "font-size": 9, "font-family": "var(--mono)",
          fill: isSel ? "rgba(255,176,102,.95)" : isCur ? "rgba(255,138,30,.9)" : (future ? "rgba(154,163,181,.35)" : "rgba(154,163,181,.6)"),
        });
        t.textContent = m + "月";
        svg.appendChild(t);
      }
    }

    function render() {
      svg.innerHTML = "";
      if (mode === "day") drawDay(); else drawMonth();
    }

    if (seg) {
      seg.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-mode]");
        if (!btn) return;
        mode = btn.dataset.mode;
        Array.prototype.forEach.call(seg.children, (b) => b.classList.toggle("on", b === btn));
        render();
      });
    }

    render();
    return { redraw: render, getMode: () => mode };
  }

  /* ---------------- 双环 ---------------- */
  function renderRing(data) {
    const svg = $("ring"), ul = $("legend");
    if (!svg || !ul) return;
    svg.innerHTML = "";
    ul.innerHTML = "";
    const total = data.expense || 0;

    const cats = (data.cats || []).filter((c) => c.v > 0);
    // 「其他」固定用最深那档灰，橙色永远留给金额最大的真实分类
    const pick = (c, i) => (c.n === "其他" ? RAMP[RAMP.length - 1] : colorOf(i));
    let outer = cats.slice(0, TOPN).map((c) => ({ name: c.n, value: c.v, color: pick(c, cats.indexOf(c)) }));
    const rest = cats.slice(TOPN).reduce((a, c) => a + c.v, 0);
    if (rest > 0) {
      const other = outer.find((c) => c.name === "其他");
      if (other) other.value += rest;
      else outer.push({ name: "其他", value: rest, color: RAMP[RAMP.length - 1] });
    }
    outer = outer.filter((c) => c.value > 0);
    const inner = (data.types || []).map((t, i) => ({ name: t.n, value: t.v, color: i === 0 ? "#ff8a1e" : "#5b708f" }));

    const C = 75, TAU = Math.PI * 2, START = -Math.PI / 2;
    const arc = (r, a0, span, th, color) => {
      const [sx, sy] = [C + r * Math.cos(a0), C + r * Math.sin(a0)];
      const [ex, ey] = [C + r * Math.cos(a0 + span), C + r * Math.sin(a0 + span)];
      const p = el("path", {
        d: "M" + sx.toFixed(2) + " " + sy.toFixed(2) + " A" + r + " " + r + " 0 " + (span > Math.PI ? 1 : 0) + " 1 " + ex.toFixed(2) + " " + ey.toFixed(2),
        fill: "none", stroke: color, "stroke-width": th, "stroke-linecap": "butt",
      });
      p.style.transition = "opacity .14s linear";
      p.style.cursor = "pointer";
      svg.appendChild(p);
      return p;
    };

    const innerArcs = [], outerArcs = [];
    const gapI = (4 * Math.PI) / 180, gapO = (2.2 * Math.PI) / 180;
    let a = START;
    inner.forEach((t) => {
      const span = total > 0 ? (t.value / total) * TAU : 0;
      if (span > gapI) innerArcs.push({ el: arc(45, a + gapI / 2, span - gapI, 8, t.color), t });
      a += span;
    });
    a = START;
    outer.forEach((c) => {
      const span = total > 0 ? (c.value / total) * TAU : 0;
      if (span > gapO) outerArcs.push({ el: arc(63, a + gapO / 2, span - gapO, 12, c.color), c });
      a += span;
    });

    const valEl = $("ringVal"), keyEl = $("ringKey"), note = $("ringNote");
    const totalTxt = "¥" + fmt(total);
    const pct = (v) => (total > 0 ? ((v / total) * 100).toFixed(1) : "0.0") + "%";
    const reset = () => { if (valEl) valEl.textContent = totalTxt; if (keyEl) keyEl.textContent = "EXPENSE"; };
    if (note) note.textContent = data.month + " 月 · " + cats.length + " 类";

    function focus(entry) {
      outerArcs.forEach((o) => { o.el.setAttribute("opacity", !entry || (entry.cat ? o.c === entry.cat : true) ? 1 : 0.18); });
      innerArcs.forEach((o) => { o.el.setAttribute("opacity", !entry || (entry.type ? o.t === entry.type : true) ? 1 : 0.18); });
      Array.prototype.forEach.call(ul.children, (li) => {
        const e2 = li.__e;
        li.style.opacity = !entry || !e2 ? 1 : (entry.cat ? (e2.cat === entry.cat ? 1 : 0.38) : (e2.type === entry.type ? 1 : 0.38));
      });
      if (!entry) { reset(); return; }
      if (entry.cat) { valEl.textContent = "¥" + fmt(entry.cat.value); keyEl.textContent = entry.cat.name + " · " + pct(entry.cat.value); }
      else { valEl.textContent = "¥" + fmt(entry.type.value); keyEl.textContent = entry.type.name + " · " + pct(entry.type.value); }
    }
    const off = () => focus(null);

    const row = (cls, html, entry) => {
      const li = document.createElement("li");
      li.className = cls;
      li.innerHTML = html;
      li.__e = entry;
      li.addEventListener("pointerenter", () => focus(entry));
      li.addEventListener("pointerleave", off);
      ul.appendChild(li);
    };
    inner.forEach((t) => row("ty",
      '<span class="nm"><i style="background:' + t.color + '"></i>' + t.name + "</span>" +
      '<span class="am">' + pct(t.value) + "</span>", { type: t }));
    const sep = document.createElement("li");
    sep.className = "sep";
    ul.appendChild(sep);
    outer.forEach((c) => row("",
      '<span class="nm"><i style="background:' + c.color + '"></i>' + c.name + "</span>" +
      '<span class="am">¥' + fmt(c.value) + '<span style="color:var(--faint)"> · ' + pct(c.value) + "</span></span>",
      { cat: c }));

    outerArcs.forEach((o) => {
      o.el.addEventListener("pointerenter", () => focus({ cat: o.c }));
      o.el.addEventListener("pointerleave", off);
    });
    innerArcs.forEach((o) => {
      o.el.addEventListener("pointerenter", () => focus({ type: o.t }));
      o.el.addEventListener("pointerleave", off);
    });
    reset();
  }

  /* ---------------- 换月 ---------------- */
  function switchMonth(m) {
    if (m === state.month) return;
    fetch("/api/month?m=" + m, { credentials: "same-origin" })
      .then((r) => r.json())
      .then((j) => {
        state.month = m;
        state.data = j;
        state.daily = j.daily || [];
        state.today = j.today || 1;
        state.day = null;
        renderRing(j);
        flow.redraw();
        if (j.rows && j.rows.length) {
          filterDay(j.today);            // 默认落到这个月最后一天
        } else {
          renderTable([], "这个月还没有记录");
        }
      })
      .catch(() => { /* 换月失败就保持原样 */ });
  }

  /* ---------------- 起手 ---------------- */
  const flow = drawFlow();
  renderRing(state.data);
  resetTable();

  const reset = $("recentReset");
  if (reset) reset.addEventListener("click", resetTable);
})();
