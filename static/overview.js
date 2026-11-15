/* 总览：30 天柱状 + 7 日均线 + 双环结构图 + 表格色条。
   数据来自 Java 侧的 window.OV，这里只负责画，不查库、不猜数。 */
(function () {
  "use strict";

  const OV = window.OV;
  if (!OV) return;

  const $ = (id) => document.getElementById(id);
  const fmt = (n) => Number(n).toLocaleString("zh-CN", { maximumFractionDigits: 0 });
  const fmt2 = (n) => Number(n).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const NS = "http://www.w3.org/2000/svg";

  // 外环配色：第 1 名橙，其余灰阶（与页头 accent 同一套）
  const RAMP = ["#ff8a1e", "#e3e6ec", "#b6becb", "#8b94a5", "#626b7c", "#414a5a"];
  const TOPN = 5;
  const colorOf = (rank) => RAMP[Math.min(rank, RAMP.length - 1)];

  const el = (name, attrs) => {
    const e = document.createElementNS(NS, name);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  };

  /* ---------------- 1. 支出柱状：本月每日 ⇄ 全年每月 ---------------- */
  function drawFlow() {
    const svg = $("flow");
    if (!svg || !OV.daily) return;
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

    function col(i, slot, bw, h, fill, active) {
      const x = padL + slot * i + (slot - bw) / 2;
      const bar = el("rect", {
        x: x.toFixed(1), y: (base - h).toFixed(1), width: bw.toFixed(1), height: Math.max(0, h).toFixed(1), fill,
      });
      bar.style.transition = "fill .12s linear";
      svg.appendChild(bar);
      return { bar, x, bw, active };
    }

    function hot(i, slot, label, onText, offText, repaint) {
      const hit = el("rect", {
        x: (padL + slot * i).toFixed(1), y: top, width: slot.toFixed(1), height: (base - top).toFixed(1), fill: "transparent",
      });
      hit.style.cursor = "crosshair";
      hit.addEventListener("pointerenter", () => { repaint(true); if (meta) meta.textContent = onText; });
      hit.addEventListener("pointerleave", () => { repaint(false); if (meta) meta.textContent = offText; });
      svg.appendChild(hit);
    }

    /* ---- 本月每日 ---- */
    function drawDay() {
      const today = Math.min(OV.daily.length, OV.today || OV.daily.length);
      const data = OV.daily.slice(0, Math.max(1, today));
      const n = data.length;
      const peak = Math.max(1, ...data);
      const slot = (W - padL - padR) / n;
      const bw = Math.max(3, Math.min(slot * 0.62, 26));
      const def = "日均 ¥" + fmt(data.reduce((a, b) => a + b, 0) / Math.max(1, today)) +
        " · 峰值 ¥" + fmt(peak) + " · 悬停看单日";
      if (meta) meta.textContent = def;
      if (legAvg) legAvg.hidden = false;
      if (label) label.textContent = "本月每日支出 / DAILY";
      if (legTxt) legTxt.textContent = "每日支出 DAILY";

      grid(peak);
      data.forEach((v, i) => {
        const isToday = (i + 1) === today;
        const h = (v / (peak * 1.15)) * (base - top);
        const fill = v > 0 ? (isToday ? "#ff8a1e" : "rgba(255,138,30,.72)") : "rgba(255,138,30,.12)";
        const c = col(i, slot, bw, h, fill, isToday);
        hot(i, slot, i + 1,
          (i + 1) + " 日 · ¥" + fmt2(v) + (isToday ? " · 今天" : ""), def,
          (over) => c.bar.setAttribute("fill", over ? "#ffb066" : fill));
      });

      // 7 日移动平均（只在已过去的日子上算）
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

      const marks = new Set([1, today]);
      for (let d = 5; d < today; d += 5) marks.add(d);
      marks.forEach((d) => {
        const i = Math.min(n - 1, d - 1);
        const t = el("text", {
          x: (padL + slot * i + slot / 2).toFixed(1), y: base + 14, "text-anchor": "middle",
          "font-size": 9, "font-family": "var(--mono)",
          fill: d === today ? "rgba(255,138,30,.9)" : "rgba(154,163,181,.6)",
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
      const def = "全年 ¥" + fmt(past) + " · 已过 " + cur + " 个月 · 月均 ¥" + fmt(past / Math.max(1, cur));
      if (meta) meta.textContent = def;
      if (legAvg) legAvg.hidden = true;
      if (label) label.textContent = "全年每月支出 / MONTHLY";
      if (legTxt) legTxt.textContent = "每月支出 MONTHLY";

      grid(peak);
      for (let i = 0; i < 12; i++) {
        const v = months[i] || 0;
        const isCur = (i + 1) === cur;
        const future = (i + 1) > cur;
        const h = (v / (peak * 1.15)) * (base - top);
        const fill = future ? "transparent" : (isCur ? "#ff8a1e" : "rgba(255,138,30,.72)");
        const c = col(i, slot, bw, h, fill, isCur);
        hot(i, slot, i + 1,
          (i + 1) + " 月 · " + (future ? "未到" : "¥" + fmt2(v)), def,
          (over) => { if (!future) c.bar.setAttribute("fill", over ? "#ffb066" : fill); });

        const t = el("text", {
          x: (padL + slot * i + slot / 2).toFixed(1), y: base + 14, "text-anchor": "middle",
          "font-size": 9, "font-family": "var(--mono)",
          fill: isCur ? "rgba(255,138,30,.9)" : (future ? "rgba(154,163,181,.35)" : "rgba(154,163,181,.6)"),
        });
        t.textContent = (i + 1) + "月";
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
  }

  /* ---------------- 2. 双环：内环性质、外环细类（前 5 + 其他） ---------------- */
  function drawRing() {
    const svg = $("ring"), ul = $("legend");
    if (!svg || !ul) return;
    const total = OV.expense || 0;

    // 外环：金额前 5 类，其余合成「其他」
    const cats = (OV.cats || []).filter((c) => c.v > 0);
    let outer = cats.slice(0, TOPN).map((c) => ({ name: c.n, value: c.v, color: colorOf(cats.indexOf(c)) }));
    const rest = cats.slice(TOPN).reduce((a, c) => a + c.v, 0);
    if (rest > 0) {
      const existOthers = outer.find((c) => c.name === "其他");
      if (existOthers) existOthers.value += rest;
      else outer.push({ name: "其他", value: rest, color: colorOf(TOPN) });
    }
    outer = outer.filter((c) => c.value > 0);

    const inner = (OV.types || []).map((t, i) => ({ name: t.n, value: t.v, color: i === 0 ? "#ff8a1e" : "#5b708f" }));

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

    const valEl = $("ringVal"), keyEl = $("ringKey");
    const totalKey = "EXPENSE", totalTxt = "¥" + fmt(total);
    const reset = () => { if (valEl) valEl.textContent = totalTxt; if (keyEl) keyEl.textContent = totalKey; };
    const pct = (v) => (total > 0 ? ((v / total) * 100).toFixed(1) : "0.0") + "%";

    function focus(entry) {
      outerArcs.forEach((o) => { o.el.setAttribute("opacity", !entry || (entry.cat ? o.c === entry.cat : true) ? 1 : 0.18); });
      innerArcs.forEach((o) => { o.el.setAttribute("opacity", !entry || (entry.type ? o.t === entry.type : true) ? 1 : 0.18); });
      Array.prototype.forEach.call(ul.children, (li) => {
        const e = li.__e;
        li.style.opacity = !entry || !e ? 1 : (entry.cat ? (e.cat === entry.cat ? 1 : 0.38) : (e.type === entry.type ? 1 : 0.38));
      });
      if (!entry) { reset(); return; }
      if (entry.cat) { valEl.textContent = "¥" + fmt(entry.cat.value); keyEl.textContent = entry.cat.name + " · " + pct(entry.cat.value); }
      else { valEl.textContent = "¥" + fmt(entry.type.value); keyEl.textContent = entry.type.name + " · " + pct(entry.type.value); }
    }
    const off = () => focus(null);

    // 图例：2 行性质 + 分隔 + 外环各类
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

    // 表里的分类色条跟环图同一套色
    const rank = {};
    cats.forEach((c, i) => { rank[c.n] = colorOf(i); });
    document.querySelectorAll(".tag[data-cat]").forEach((td) => {
      const tick = td.querySelector(".tick");
      if (tick) tick.style.background = rank[td.dataset.cat] || "#414a5a";
    });
  }

  drawFlow();
  drawRing();
})();
