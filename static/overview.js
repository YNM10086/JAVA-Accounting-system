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

  /* ---------------- 1. 30 天柱状 + 7 日均线 ---------------- */
  function drawFlow() {
    const svg = $("flow");
    if (!svg || !OV.daily) return;
    const W = 1000, H = 190, padL = 8, padR = 52, base = H - 26;
    const data = OV.daily;
    const peak = Math.max(1, ...data);
    const max = peak * 1.18;
    const slot = (W - padL - padR) / data.length;
    const bw = Math.max(3, slot * 0.56);

    // 基线
    svg.appendChild(el("line", { x1: padL, y1: base, x2: W - padR, y2: base, stroke: "rgba(155,180,225,.18)", "stroke-width": 1 }));

    data.forEach((v, i) => {
      const h = (v / max) * (base - 12);
      const x = padL + slot * i + (slot - bw) / 2;
      const y = base - h;
      const bar = el("rect", { x: x.toFixed(1), y: y.toFixed(1), width: bw.toFixed(1), height: Math.max(0, h).toFixed(1), fill: v > 0 ? "rgba(255,138,30,.86)" : "rgba(255,138,30,.1)" });
      const title = el("title", {});
      title.textContent = (i + 1) + " 日 · ¥" + fmt2(v);
      bar.appendChild(title);
      svg.appendChild(bar);
    });

    // 7 日均线
    const pts = [];
    for (let i = 0; i < data.length; i++) {
      let sum = 0, n = 0;
      for (let k = Math.max(0, i - 6); k <= i; k++) { sum += data[k]; n++; }
      const avg = sum / n;
      const x = padL + slot * i + slot / 2;
      const y = base - (avg / max) * (base - 12);
      pts.push(x.toFixed(1) + "," + y.toFixed(1));
    }
    svg.appendChild(el("polyline", {
      points: pts.join(" "), fill: "none", stroke: "rgba(200,210,230,.62)",
      "stroke-width": 1, "stroke-dasharray": "3 4",
    }));

    const avg = data.reduce((a, b) => a + b, 0) / data.length;
    const meta = $("flowMeta");
    if (meta) meta.textContent = "日均 ¥" + fmt(avg) + " · 峰值 ¥" + fmt(peak);
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
