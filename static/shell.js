/* 壳：顶栏 / 左栏 / 状态栏 / 海洋背景 / 进场 / AI 小球的全部行为。
   页面骨架由 Java 侧 WebServer.shell() 输出，这里只负责接线。
   调试参数沿用原型：?nbg=1 关背景、?nofx=1 关进场、?t=秒 定格进场。 */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const QS = new URLSearchParams(location.search);
  const pad2 = (n) => String(n).padStart(2, "0");
  const fmt = (n) => Number(n).toLocaleString("zh-CN");

  if (QS.has("nofx")) document.documentElement.classList.add("nofx");

  /* ---------------- 1. 鲨鱼海洋背景 ---------------- */
  function ocean() {
    const cv = $("oceanBg");
    if (QS.has("nbg") || !cv || !window.AsciiOcean) return;
    try {
      const eng = new window.AsciiOcean.OceanEngine(cv, { fadeIn: true });
      eng.setSettings({ heroOpacity: 0.95, idleOpacity: 0.95, background: "#05080f", grid: 0.03, dotDensity: 0.1 });
      eng.start();
      try { eng.drawStill(); } catch (e) { /* 引擎未就绪 */ }
      window.addEventListener("beforeunload", () => { try { eng.destroy(); } catch (e) {} });
    } catch (e) { /* 背景失败不影响页面 */ }
  }

  /* ---------------- 2. 时钟 ---------------- */
  function clock() {
    const el = $("clock");
    if (!el) return;
    (function tick() {
      const d = new Date();
      el.textContent = d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
        " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
      setTimeout(tick, 1000);
    })();
  }

  /* ---------------- 3. 面板四角裁切标记 ---------------- */
  function corners(root) {
    (root || document).querySelectorAll(".panel").forEach((p) => {
      if (p.querySelector(".cm")) return;
      ["tl", "tr", "bl", "br"].forEach((k) => {
        const i = document.createElement("i");
        i.className = "cm " + k;
        i.setAttribute("aria-hidden", "true");
        p.appendChild(i);
      });
    });
  }

  /* ---------------- 4. AI 小球 / 面板 / 气泡 ---------------- */
  const AI_SPEECH = [
    "不要为了省钱克扣饭钱，要好好吃饭。",
    "今天记一笔了吗？月底才不慌。",
    "餐饮已经过千了，这个月还剩几天。",
    "预算还剩一截，稳得住。",
    "奶茶第 4 杯了，要不要顺手记一下？",
  ];

  function aiInit() {
    const orb = $("aiOrb"), panel = $("aiPanel"), bubble = $("aiBubble");
    if (!orb || !panel) return;

    const ta = $("aiInput"), stat = $("aiStat"), prev = $("aiPrev"), adv = $("aiAdvice");
    const engine = $("aiEngine");
    let busy = false, open = false, speechIdx = 0, bubbleTimer = 0, idleTimer = 0;

    const setStatus = (msg, err) => {
      if (!stat) return;
      stat.textContent = msg || "";
      stat.className = "aiStat" + (err ? " err" : "") + (busy ? " busy" : "");
    };

    const setEngine = (ok, model) => {
      if (engine) {
        engine.textContent = ok ? "就绪" : "离线";
        engine.classList.toggle("warn", !ok);
      }
      const st = $("aiModel");
      if (st) st.textContent = (model || "AI") + " · " + (ok ? "就绪" : "离线");
    };

    function openPanel(focus) {
      open = true;
      panel.classList.add("show");
      panel.setAttribute("aria-hidden", "false");
      orb.setAttribute("aria-expanded", "true");
      hideBubble();
      if (focus) setTimeout(() => ta && ta.focus(), 60);
    }

    function closePanel() {
      open = false;
      panel.classList.remove("show");
      panel.setAttribute("aria-hidden", "true");
      orb.setAttribute("aria-expanded", "false");
      if (ta) ta.blur();
    }

    function hideBubble() {
      if (bubble) bubble.classList.remove("show");
      clearTimeout(bubbleTimer);
    }

    function say() {
      if (open || !bubble) return;
      const txt = bubble.querySelector(".txt");
      if (txt) txt.textContent = AI_SPEECH[speechIdx++ % AI_SPEECH.length];
      bubble.classList.add("show");
      orb.classList.add("say");
      clearTimeout(bubbleTimer);
      bubbleTimer = setTimeout(() => { bubble.classList.remove("show"); orb.classList.remove("say"); }, 9000);
    }

    function idleLoop(first) {
      clearTimeout(idleTimer);
      idleTimer = setTimeout(() => { say(); idleLoop(false); }, first ? 30000 : 120000);
    }

    /* --- 预览行 --- */
    function renderRows(items) {
      prev.hidden = false;
      prev.innerHTML = "";
      const hd = document.createElement("div");
      hd.className = "hd";
      ["商品 / ITEM", "金额", "性质", "类别", "日期", ""].forEach((t) => {
        const s = document.createElement("span");
        s.textContent = t;
        hd.appendChild(s);
      });
      prev.appendChild(hd);

      items.forEach((it) => {
        const row = document.createElement("div");
        row.className = "rw";

        const g = document.createElement("input");
        g.className = "g"; g.value = it.goods || "";

        const p = document.createElement("input");
        p.className = "p"; p.type = "number"; p.step = "0.01"; p.min = "0.01"; p.value = it.price;

        const t = document.createElement("select");
        t.className = "t";
        ["日常", "特殊"].forEach((n, i) => {
          const o = document.createElement("option");
          o.value = String(i + 1); o.textContent = n;
          t.appendChild(o);
        });
        t.value = String(it.type === 2 ? 2 : 1);

        const c = document.createElement("select");
        c.className = "c";
        (window.CAT_LIST || ["其他"]).forEach((n) => {
          const o = document.createElement("option");
          o.value = n; o.textContent = n;
          c.appendChild(o);
        });
        c.value = it.cat || "其他";
        // 换类别时把性质拉到该类的默认值（餐饮=日常，其余=特殊），仍可手动改
        c.addEventListener("change", () => { t.value = c.value === "餐饮" ? "1" : "2"; });

        const d = document.createElement("input");
        d.className = "d"; d.type = "number"; d.min = "1"; d.max = "31"; d.value = it.day;

        const del = document.createElement("button");
        del.className = "del"; del.type = "button"; del.title = "删除这条"; del.textContent = "×";
        del.onclick = () => row.remove();

        row.append(g, p, t, c, d, del);
        prev.appendChild(row);
      });

      const foot = document.createElement("div");
      foot.className = "aiFoot";
      const cancel = document.createElement("button");
      cancel.className = "btn"; cancel.type = "button"; cancel.textContent = "取消";
      cancel.onclick = () => { prev.hidden = true; prev.innerHTML = ""; setStatus(""); };
      const ok = document.createElement("button");
      ok.className = "btn solid"; ok.type = "button"; ok.textContent = "确认入库";
      ok.onclick = doSave;
      foot.append(cancel, ok);
      prev.appendChild(foot);
    }

    function collect() {
      const out = [];
      prev.querySelectorAll(".rw").forEach((row) => {
        const goods = row.querySelector(".g").value.trim();
        const price = parseFloat(row.querySelector(".p").value);
        const type = parseInt(row.querySelector(".t").value, 10) === 2 ? 2 : 1;
        const cat = row.querySelector(".c").value;
        let day = parseInt(row.querySelector(".d").value, 10);
        if (!goods || !(price > 0)) return;
        if (!day || day < 1) day = new Date().getDate();
        if (day > 31) day = 31;
        out.push({ goods, price: Math.round(price * 100) / 100, type, cat, day });
      });
      return out;
    }

    const post = (url, body) => fetch(url, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body || {}),
    }).then((r) => r.json());

    function showAdvice(text) {
      if (!adv) return;
      adv.hidden = false;
      adv.textContent = "";
      const tag = document.createElement("b");
      tag.textContent = "本月建议 / ADVICE";
      adv.append(tag, document.createTextNode(text));
    }

    function doParse() {
      if (busy) return;
      const text = (ta && ta.value || "").trim();
      if (!text) { if (ta) ta.focus(); setStatus("先说说今天买了什么", true); return; }
      busy = true; setStatus("AI 正在解析…"); prev.hidden = true; prev.innerHTML = "";
      orb.classList.add("say");
      post("/ai/parse", { text }).then((j) => {
        busy = false; orb.classList.remove("say");
        if (!j.ok) { setStatus(j.error || "解析失败", true); return; }
        const items = j.items || [];
        if (!items.length) { setStatus("没识别出条目，换个说法试试", true); return; }
        setStatus("识别到 " + items.length + " 条 · 确认后入库");
        renderRows(items);
      }).catch(() => { busy = false; orb.classList.remove("say"); setStatus("请求失败，检查服务是否在运行", true); });
    }

    function doSave() {
      if (busy) return;
      const items = collect();
      if (!items.length) { setStatus("至少保留一条：商品名和金额都要有", true); return; }
      busy = true; setStatus("正在写入…");
      post("/ai/save", { items }).then((j) => {
        busy = false;
        if (!j.ok) { setStatus(j.error || "入库失败", true); return; }
        prev.innerHTML = "";
        const done = document.createElement("div");
        done.className = "aiDone";
        done.innerHTML = "<b>✓</b> 已记 " + j.saved + " 笔";
        prev.appendChild(done);
        prev.hidden = false;
        setStatus("");
        if (j.advice) showAdvice(j.advice);
        else if (j.adviceError) setStatus("已记 " + j.saved + " 笔，建议没拿到：" + j.adviceError, true);
      }).catch(() => { busy = false; setStatus("请求失败，检查服务是否在运行", true); });
    }

    function doReclass() {
      if (busy) return;
      if (!confirm("让 AI 重新分类本月全部记录？会覆盖现有分类。")) return;
      busy = true; setStatus("AI 正在重新分类本月…（按批处理，可能要几十秒）");
      orb.classList.add("say");
      post("/ai/classify", {}).then((j) => {
        busy = false; orb.classList.remove("say");
        if (!j.ok) { setStatus(j.error || "分类失败", true); return; }
        setStatus(j.changed > 0 ? "已更新 " + j.changed + " 条分类" : "没有需要改的，分类已经是最新");
      }).catch(() => { busy = false; orb.classList.remove("say"); setStatus("请求失败，检查服务是否在运行", true); });
    }

    function doAdvice() {
      if (busy) return;
      busy = true; setStatus("正在生成建议…");
      post("/ai/advice", {}).then((j) => {
        busy = false;
        if (!j.ok) { setStatus(j.error || "建议没拿到", true); return; }
        setStatus("");
        showAdvice(j.advice);
      }).catch(() => { busy = false; setStatus("请求失败", true); });
    }

    orb.addEventListener("click", () => (open ? closePanel() : (openPanel(true), touch())));
    if (bubble) bubble.addEventListener("click", () => { hideBubble(); openPanel(true); });
    const parseBtn = $("aiParse"), adviseBtn = $("aiAdvise"), reclassBtn = $("aiReclass");
    if (parseBtn) parseBtn.addEventListener("click", doParse);
    if (adviseBtn) adviseBtn.addEventListener("click", doAdvice);
    if (reclassBtn) reclassBtn.addEventListener("click", doReclass);

    if (ta) {
      ta.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); doParse(); }
      });
      ta.addEventListener("input", touch);
    }

    document.addEventListener("keydown", (e) => { if (e.key === "Escape") closePanel(); });
    document.addEventListener("pointerdown", (e) => {
      if (!open) return;
      if (panel.contains(e.target) || orb.contains(e.target)) return;
      closePanel();
    });

    function touch() { idleLoop(true); }

    /* 后端状态：模型名与就绪与否 */
    fetch("/ai/status", { credentials: "same-origin" })
      .then((r) => r.json())
      .then((j) => setEngine(!!j.ok, j.model))
      .catch(() => setEngine(false, ""));

    idleLoop(true);
  }

  /* ---------------- 5. 挂载 ---------------- */
  function mount() {
    ocean();
    clock();
    corners();
    aiInit();

    // 状态栏右侧那排装饰条
    const bc = $("barcode");
    if (bc && !bc.children.length) {
      for (let i = 0; i < 28; i++) {
        const b = document.createElement("i");
        b.style.width = (Math.random() < 0.5 ? 1 : 2) + "px";
        b.style.height = (6 + Math.random() * 10).toFixed(1) + "px";
        bc.appendChild(b);
      }
    }

    // 键盘：F2 记一笔、K 搜索（先只做 F2）
    document.addEventListener("keydown", (e) => {
      if (e.key === "F2") { e.preventDefault(); location.href = "/save"; }
    });

    // ?t=秒：把进场动画定格（截图对比用）
    if (QS.has("t")) {
      const at = Math.max(0, parseFloat(QS.get("t")) || 0) * 1000;
      requestAnimationFrame(() => {
        document.getAnimations().forEach((a) => {
          try { a.pause(); a.currentTime = at; } catch (e) { /* 非 WAAPI 动画 */ }
        });
      });
    }
  }

  window.Shell = { mount, corners };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount);
  else mount();
})();
