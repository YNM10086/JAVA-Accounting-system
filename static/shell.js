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
    "预算还剩一截，稳得住。",
    "奶茶这杯，要不要顺手记一下？",
    "记完这笔，今天的账就齐了。",
    "月底翻账单的人，会感谢现在记一笔的你。",
    "有点小贵的东西，记下来才知道值不值。",
    "今天的花销，比昨天更清楚了吗？",
    "话费、房租这种固定支出，别忘了也记上。",
    "钱花在哪儿，比花了多少更重要。",
    "每一笔都记全，月底的建议才准。",
    "记完账，顺手看一眼总览的环图。",
    "想不起来昨天花了什么？现在补一笔也来得及。",
    "攒钱不是不花，是知道自己在花什么。",
    "这一笔算日常还是特殊，你说了算。",
    "记账这事，连续几天就顺手了。",
    "分类拿不准？让我猜，你改就是了。",
    "预算紧不紧，提前几天就能看出来。",
    "有点犹豫的支出，先记下来再决定也不迟。",
    "账本干净的人，心里也干净。",
  ];

  function aiInit() {
    const orb = $("aiOrb"), panel = $("aiPanel"), bubble = $("aiBubble");
    if (!orb || !panel) return;

    const ta = $("aiInput"), stat = $("aiStat"), prev = $("aiPrev"), adv = $("aiAdvice");
    const engine = $("aiEngine");
    let busy = false, open = false, speechIdx = -1, bubbleTimer = 0, idleTimer = 0;

    /** 随机挑一句，但不连着重复上一句 */
    function nextSpeech() {
      if (AI_SPEECH.length < 2) return 0;
      let i = Math.floor(Math.random() * AI_SPEECH.length);
      if (i === speechIdx) i = (i + 1) % AI_SPEECH.length;
      speechIdx = i;
      return i;
    }

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
      if (txt) txt.textContent = AI_SPEECH[nextSpeech()];
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

    /* 记完一笔 / 重分完分类：静默换掉数据段，页面不重载、面板不关、滚动不动 */
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
        softRefresh();
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
        if (j.changed > 0) {
          setStatus("已更新 " + j.changed + " 条分类");
          softRefresh({ flash: "已更新 " + j.changed + " 条分类" });
          return;
        }
        setStatus("没有需要改的，分类已经是最新");
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

    document.addEventListener("keydown", (e) => {
      if (e.key !== "Escape") return;
      if (open) { closePanel(); return; }
      const view = document.querySelector(".view");
      if (view && view.scrollTop > 0) view.scrollTo({ top: 0, behavior: "smooth" });
    });
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


  /* ---------------- 6. 单页锚点：点击定位 + 滚动高亮 ---------------- */
  function navSpy() {
    const view = document.querySelector(".view");
    const links = Array.prototype.slice.call(document.querySelectorAll(".nav a[href^='#']"));
    if (!view || !links.length) return;
    const smooth = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const go = (id) => {
      const t = document.getElementById(id);
      if (!t) return false;
      t.scrollIntoView({ behavior: smooth ? "smooth" : "auto", block: "start" });
      history.replaceState(null, "", "#" + id);
      return true;
    };

    links.forEach((a) => a.addEventListener("click", (e) => {
      const id = a.getAttribute("href").slice(1);
      if (go(id)) e.preventDefault();
    }));

    // 首次带 # 进页面时，等布局稳定再定位
    if (location.hash) setTimeout(() => go(location.hash.slice(1)), 80);

    const io = new IntersectionObserver((entries) => {
      entries.forEach((en) => {
        if (!en.isIntersecting) return;
        links.forEach((l) => l.classList.toggle("on", l.getAttribute("href") === "#" + en.target.id));
      });
    }, { root: view, rootMargin: "-8px 0px -70% 0px", threshold: 0 });
    document.querySelectorAll("section.sec").forEach((s) => io.observe(s));
  }

  /* ---------------- 6b. 明细：展开全部 ---------------- */
  function detailMore() {
    const btn = document.getElementById("detMore");
    if (!btn) return;
    btn.addEventListener("click", () => {
      document.querySelectorAll("#details tbody tr.more").forEach((tr) => { tr.hidden = false; });
      btn.parentElement.remove();
    });
  }

  /* ---------------- 7. 设置页里的 AI 状态与模型切换 ---------------- */
  function settingsBits() {
    const el = document.getElementById("settingsAi");
    const sel = document.getElementById("modelSel");
    const hint = document.getElementById("modelHint");
    if (!el && !sel) return;
    fetch("/ai/status", { credentials: "same-origin" })
      .then((r) => r.json())
      .then((j) => {
        if (el) el.textContent = j.ok ? "Ollama · " + (j.model || "已连接") : "离线（Ollama 未启动）";
        if (!sel) return;

        const models = j.models || [];
        sel.innerHTML = "";
        models.forEach((m) => {
          const o = document.createElement("option");
          o.value = m;
          o.textContent = m + (m === j.model ? "  ← 当前" : "");
          if (m === j.model) o.selected = true;
          sel.appendChild(o);
        });
        if (!models.length) {
          const o = document.createElement("option");
          o.value = "";
          o.textContent = "（Ollama 未启动，读不到模型）";
          sel.appendChild(o);
          sel.disabled = true;
          return;
        }
        if (hint) hint.textContent = "切换后立刻生效，建议解析与建议都用它";

        sel.addEventListener("change", () => {
          const want = sel.value;
          if (!want) return;
          sel.disabled = true;
          if (hint) hint.textContent = "正在切换…";
          fetch("/ai/model", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ model: want }),
          })
            .then((r) => r.json())
            .then((k) => {
              if (!k.ok) throw new Error(k.error || "切换失败");
              const hdr = document.getElementById("aiModel");
              if (hdr) hdr.textContent = want + " · 就绪";
              if (el) el.textContent = "Ollama · " + want;
              if (hint) hint.textContent = "当前模型：" + want + "（已生效）";
              flash("AI 模型已切到 " + want);
            })
            .catch((err) => {
              if (hint) hint.textContent = "切换失败，仍是 " + j.model;
              flash("切换失败：" + (err && err.message ? err.message : err), true);
            })
            .finally(() => { sel.disabled = false; });
        });
      })
      .catch(() => { if (el) el.textContent = "读取失败"; });
  }

  /* ---------------- 7b. 导出：页面内拿 blob 再下载，不跳转 ---------------- */
  function exportLinks() {
    document.querySelectorAll("a[href^='/export']").forEach((a) => {
      if (a.dataset.dlBound === "1") return;   // 静默刷新会再调一次，别重复绑
      a.dataset.dlBound = "1";
      a.addEventListener("click", (e) => {
        e.preventDefault();
        if (a.dataset.busy === "1") return;

        const href = a.getAttribute("href");
        const label = a.textContent;
        const wasSolid = a.classList.contains("solid");
        a.dataset.busy = "1";
        a.textContent = "正在生成…";
        a.style.opacity = ".6";

        fetch(href, { credentials: "same-origin" })
          .then((r) => {
            if (!r.ok) throw new Error("服务返回 " + r.status);
            const cd = r.headers.get("Content-Disposition") || "";
            const mStar = /filename\*=UTF-8''([^;]+)/i.exec(cd);
            const mPlain = /filename="?([^";]+)"?/i.exec(cd);
            let name = mStar ? mStar[1] : (mPlain ? mPlain[1] : "export.xlsx");
            try { name = decodeURIComponent(name); } catch (err) { /* 保持原样 */ }
            return r.blob().then((b) => ({ blob: b, name: name }));
          })
          .then((got) => {
            const url = URL.createObjectURL(got.blob);
            const t = document.createElement("a");
            t.href = url;
            t.download = got.name;
            document.body.appendChild(t);
            t.click();
            t.remove();
            setTimeout(() => URL.revokeObjectURL(url), 5000);
            flash("已导出 " + got.name);
          })
          .catch((err) => flash("导出失败：" + (err && err.message ? err.message : err), true))
          .finally(() => {
            a.textContent = label;
            a.style.opacity = "";
            if (wasSolid) a.classList.add("solid");
            delete a.dataset.busy;
          });
      });
    });
  }

  /* ---------------- 7c. 静默刷新：只换数据段，不重载整页 ---------------- */
  /** 页面上所有段落 id（用于保留地址栏锚点） */
  const SOFT_IDS = ["overview", "entry", "income", "details", "budget", "settings", "export"];

  /** 从服务端渲染的 HTML 里抠出 window.__flash=[文字, 是否错误] */
  function flashFrom(doc) {
    const hit = Array.prototype.slice.call(doc.querySelectorAll("script"))
      .map((s) => s.textContent || "")
      .find((t) => t.indexOf("window.__flash=") >= 0);
    if (!hit) return null;
    const a = hit.indexOf("["), b = hit.lastIndexOf("]");
    if (a < 0 || b <= a) return null;
    try {
      // 版本是 JS 字面量（单引号 + 转义），直接按 JS 求值最稳
      const arr = new Function("return [" + hit.slice(a + 1, b) + "]")();
      return { text: String(arr[0] || ""), err: !!arr[1] };
    } catch (e) { return null; }
  }

  /** 把一份新的 /main HTML 就地换进页面：数据段换内容，图表用 ov 重画 */
  function applyPage(html, ov, hash) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    if (!doc.getElementById("overview")) { location.reload(); return; }

    // 首屏入场动画已经放过了：重建的节点别再放一遍
    document.body.classList.add("settled");

    // 总览：只换 KPI 卡片和「最近流水」，图表 DOM 留着让 OVRefresh 就地重画
    ["ovKpis", "ovRecent"].forEach((id) => {
      const fresh = doc.getElementById(id);
      const old = document.getElementById(id);
      if (fresh && old) old.innerHTML = fresh.innerHTML;
    });

    // 记一笔：只在「新增 ↔ 修改」切换时换表单，否则保留用户正在输入的内容
    const oldEntry = document.getElementById("entry");
    const freshEntry = doc.getElementById("entry");
    if (oldEntry && freshEntry) {
      const wasEdit = !!oldEntry.querySelector("input[name='id']");
      const nowEdit = !!freshEntry.querySelector("input[name='id']");
      if (wasEdit !== nowEdit) oldEntry.innerHTML = freshEntry.innerHTML;
    }

    // 明细 / 收入 / 预算：整段换（没有进场动画，换完就是新数字）
    ["details", "income", "budget"].forEach((id) => {
      const fresh = doc.getElementById(id);
      const old = document.getElementById(id);
      if (fresh && old) old.innerHTML = fresh.innerHTML;
    });

    // 设置段不整段换（会重建模型下拉、闪一下「读取中…」），只更新预算那行
    const sbFresh = doc.getElementById("settingsBudget");
    const sbOld = document.getElementById("settingsBudget");
    if (sbFresh && sbOld) sbOld.innerHTML = sbFresh.innerHTML;

    if (ov && typeof window.OVRefresh === "function") window.OVRefresh(ov);

    corners();
    detailMore();
    exportLinks();

    const f = flashFrom(doc);
    if (f) flash(f.text, f.err);

    // 地址栏里的 ?ok=/?edit= 清掉，保留段落锚点
    const want = hash != null ? hash : (location.hash || "").replace("#", "");
    history.replaceState(null, "", "/main" + (SOFT_IDS.indexOf(want) >= 0 ? "#" + want : ""));
  }

  /** 软打开一个页面（改一笔 / 取消修改这类同站链接），不整页跳 */
  function openPage(url, hash, focusSel) {
    Promise.all([
      fetch(url, { credentials: "same-origin", cache: "no-store" }).then((r) => r.text()),
      fetch("/api/overview", { credentials: "same-origin", cache: "no-store" })
        .then((r) => r.json()).catch(() => null),
    ]).then(([html, ov]) => {
      applyPage(html, ov, hash);
      const t = hash ? document.getElementById(hash) : null;
      if (t) t.scrollIntoView({ behavior: "smooth", block: "start" });
      if (focusSel) setTimeout(() => { const g = document.querySelector(focusSel); if (g) g.focus(); }, 360);
    }).catch(() => { location.href = url; });
  }

  let softBusy = null;

  /** 拉最新数据静默刷新；o.flash 可附带一条提示 */
  function softRefresh(o) {
    const opt = o || {};
    if (softBusy) return softBusy;
    softBusy = Promise.all([
      fetch("/main", { credentials: "same-origin", cache: "no-store" }).then((r) => r.text()),
      fetch("/api/overview", { credentials: "same-origin", cache: "no-store" })
        .then((r) => r.json()).catch(() => null),
    ]).then(([html, ov]) => {
      applyPage(html, ov);
      if (opt.flash) flash(opt.flash, !!opt.err);
    }).catch(() => flash("刷新数据失败，按 F5 试试", true))
      .finally(() => { softBusy = null; });
    return softBusy;
  }

  /** 表单改成后台提交：POST 完直接拿返回的那份 /main 去换，不整页跳 */
  function silentSubmit(form) {
    const act = form.getAttribute("action") || "";
    const btn = form.querySelector("button[type='submit']");
    const label = btn ? btn.textContent : "";
    if (btn) { btn.disabled = true; btn.textContent = "处理中…"; }

    const body = new URLSearchParams();
    new FormData(form).forEach((v, k) => body.append(k, v));

    const posted = fetch(act, {
      method: "POST", body, credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    }).then((r) => r.text());

    // 必须等 POST 落库后再取数据，否则图表会拿到写之前的快照
    const wasEdit = !!form.querySelector("input[name='id']") ||
                    (form.querySelector("input[name='_action']") || {}).value === "update";

    return posted
      .then((html) => fetch("/api/overview", { credentials: "same-origin", cache: "no-store" })
        .then((r) => r.json()).catch(() => null)
        .then((ov) => {
          applyPage(html, ov);
          // 新增成功后把表单清空；修改模式下表单已被换成新的一份
          if (form.isConnected && !wasEdit) form.reset();
        }))
      .catch(() => { location.reload(); })
      .finally(() => {
        if (btn && document.contains(btn)) { btn.disabled = false; btn.textContent = label; }
      });
  }

  /* ---------------- 7d. 开屏声音：开关 + 音量（存在 localStorage，开屏页读同一份） ---------------- */
  let previewAc = null;

  function previewBeep(vol) {
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      if (!previewAc) previewAc = new AC();
      if (previewAc.state === "suspended") previewAc.resume().catch(() => {});
      const t0 = previewAc.currentTime + 0.01;
      const o = previewAc.createOscillator(), g = previewAc.createGain();
      o.type = "triangle";
      o.frequency.value = 740;
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(Math.max(0.0002, (vol / 100) * 0.4), t0 + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.15);
      o.connect(g).connect(previewAc.destination);
      o.start(t0);
      o.stop(t0 + 0.2);
    } catch (e) { /* 出不了声就算了 */ }
  }

  function soundPrefs() {
    const chk = document.getElementById("splashSnd");
    const rng = document.getElementById("splashVol");
    const val = document.getElementById("splashVolVal");
    if (!chk || !rng) return;

    const KEY_ON = "bk_splash_sound", KEY_VOL = "bk_splash_volume";
    let on = true, vol = 40;
    try {
      on = localStorage.getItem(KEY_ON) !== "0";
      const v = parseFloat(localStorage.getItem(KEY_VOL));
      if (isFinite(v)) vol = Math.max(0, Math.min(100, Math.round(v)));
    } catch (e) { /* 隐私模式 */ }

    const paint = () => {
      chk.checked = on;
      rng.value = String(vol);
      rng.disabled = !on;
      if (val) val.textContent = vol + "%";
    };
    paint();

    chk.addEventListener("change", () => {
      on = chk.checked;
      try { localStorage.setItem(KEY_ON, on ? "1" : "0"); } catch (e) { /* 忽略 */ }
      rng.disabled = !on;
      flash(on ? "开屏声音：开" : "开屏声音：关（下次启动生效）");
      if (on) previewBeep(vol);
    });

    rng.addEventListener("input", () => {
      vol = Math.max(0, Math.min(100, parseInt(rng.value, 10) || 0));
      if (val) val.textContent = vol + "%";
      try { localStorage.setItem(KEY_VOL, String(vol)); } catch (e) { /* 忽略 */ }
    });
    // 松手时试听一声，音量大小心里有数
    rng.addEventListener("change", () => previewBeep(vol));
  }

  /* ---------------- 5. 挂载 ---------------- */
  function mount() {
    ocean();
    clock();
    corners();
    aiInit();
    navSpy();
    detailMore();
    settingsBits();
    soundPrefs();
    exportLinks();
    if (window.__flash) flash(window.__flash[0], window.__flash[1]);

    // 首屏进场动画放完就把话说死：之后重建的节点不再重播
    setTimeout(() => document.body.classList.add("settled"), 1600);

    // 记一笔 / 删 / 收入 / 预算：全部静默提交，不刷新整页
    document.addEventListener("submit", (e) => {
      const f = e.target;
      if (!f || f.tagName !== "FORM") return;
      if (!/^\/(save|del|income|budget)$/.test(f.getAttribute("action") || "")) return;
      e.preventDefault();
      silentSubmit(f);
    });

    // 「改」和「取消修改」也是同站链接：软打开，别整页重载
    document.addEventListener("click", (e) => {
      const a = e.target && e.target.closest
        ? e.target.closest("a[href^='/main?edit='], a[href^='/main?iedit='], a[href='/main'], a[href^='/main#']")
        : null;
      if (!a) return;
      e.preventDefault();
      const url = a.getAttribute("href");
      const hash = (url.split("#")[1] || "");
      openPage(url, hash, hash === "entry" ? "#entry input[name='goods']" : "");
    });

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

    // 键盘：F2 去记一笔、K 唤 AI、Esc 回顶部（在 AI 面板里 Esc 先关面板）
    document.addEventListener("keydown", (e) => {
      const tag = (e.target && e.target.tagName) || "";
      const typing = /INPUT|TEXTAREA|SELECT/.test(tag);
      if (e.key === "F2") {
        e.preventDefault();
        const t = document.getElementById("entry");
        if (t) t.scrollIntoView({ behavior: "smooth", block: "start" });
        setTimeout(() => { const g = document.querySelector("#entry input[name='goods']"); if (g) g.focus(); }, 420);
      } else if ((e.key === "k" || e.key === "K") && !typing && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        const orb = document.getElementById("aiOrb");
        if (orb) orb.click();
      }
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

  /* ---------------- 8. 顶部闪现提示（由页面脚本调用） ---------------- */
  function flash(text, isErr) {
    if (!text) return;
    const d = document.createElement("div");
    d.className = "flash" + (isErr ? " err" : "");
    d.textContent = text;
    document.body.appendChild(d);
    setTimeout(() => d.remove(), 2800);
  }

  window.Shell = { mount, corners, flash };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount);
  else mount();
})();
