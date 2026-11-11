/*
 * AI 记账挂件（本地 Ollama）
 * - 单击：展开输入框，自然语言 → 解析 → 预览 → 确认入库
 * - 双击：直接要一条本月消费建议
 * - 空闲时自己冒泡提醒，点气泡打开输入框
 * 由 WebServer.pageRaw 注入，所有页面都在；软导航只换 <main>，挂件不会重建。
 */
(function () {
    'use strict';
    if (window.__aiwLoaded) return;
    window.__aiwLoaded = true;

    var PROMPTS = [
        '今天记录了吗？',
        '随手记一笔，月底不慌',
        '今天喝了奶茶没？',
        '花出去的钱，记下来才算数',
        '要不要我帮你看看这个月花得怎么样？'
    ];

    var state = {
        open: false,
        busy: false,
        ollamaOk: null,
        bubbleMode: '',
        lastActive: Date.now(),
        lastPrompt: 0,
        promptIdx: 0,
        bubbleTimer: 0,
        clickTimer: 0
    };

    /* ==================== 样式 ==================== */

    var css = [
        '#aiw{position:fixed;inset:0;pointer-events:none;z-index:9000;',
        'font-family:"Microsoft YaHei","PingFang SC","Noto Sans SC",sans-serif}',
        '#aiw *{box-sizing:border-box}',

        '.aiw-orb{position:fixed;right:22px;bottom:22px;width:48px;height:48px;border-radius:50%;',
        'display:flex;align-items:center;justify-content:center;pointer-events:auto;cursor:pointer;',
        'color:#cfe0ff;border:1px solid rgba(120,150,210,.38);',
        'background:radial-gradient(circle at 30% 25%,rgba(111,155,255,.42),rgba(13,17,29,.95) 72%);',
        'box-shadow:0 6px 22px rgba(0,0,0,.45),0 0 18px rgba(111,155,255,.18);',
        'transition:transform .2s cubic-bezier(.16,1,.3,1),box-shadow .3s,border-color .3s,opacity .3s}',
        '.aiw-orb:hover{transform:translateY(-2px) scale(1.06);border-color:rgba(143,179,255,.75);',
        'box-shadow:0 10px 28px rgba(0,0,0,.5),0 0 26px rgba(111,155,255,.38)}',
        '.aiw-orb:active{transform:scale(.94)}',
        '.aiw-orb.aiw-off{opacity:.45;filter:grayscale(.55)}',
        '.aiw-orb svg{animation:aiwFloat 5s ease-in-out infinite}',
        '@keyframes aiwFloat{0%,100%{transform:translateY(0)}50%{transform:translateY(-2.5px)}}',

        '.aiw-panel{position:fixed;right:22px;bottom:82px;width:352px;max-width:calc(100vw - 32px);',
        'max-height:min(74vh,640px);overflow:auto;pointer-events:auto;padding:14px;',
        'background:linear-gradient(180deg,rgba(20,25,40,.97),rgba(11,15,26,.98));',
        'border:1px solid rgba(120,150,210,.28);border-radius:16px;',
        'box-shadow:0 18px 50px rgba(0,0,0,.58),inset 0 0 0 1px rgba(255,255,255,.02);',
        'backdrop-filter:blur(16px) saturate(130%);-webkit-backdrop-filter:blur(16px) saturate(130%);',
        'opacity:0;transform:translateY(10px) scale(.98);',
        'transition:opacity .18s ease,transform .18s cubic-bezier(.16,1,.3,1)}',
        '.aiw-panel.show{opacity:1;transform:none}',

        '.aiw-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}',
        '.aiw-title{display:flex;align-items:center;gap:7px;font-size:13px;font-weight:600;color:#e6ebf7;letter-spacing:.02em}',
        '.aiw-dot{width:7px;height:7px;border-radius:50%;background:#6f9bff;box-shadow:0 0 10px rgba(111,155,255,.9)}',
        '.aiw-close{border:0;background:transparent;color:#93a0bb;font-size:20px;line-height:1;',
        'cursor:pointer;padding:0 2px;border-radius:6px;transition:color .2s,background .2s}',
        '.aiw-close:hover{color:#fff;background:rgba(255,255,255,.08)}',

        '#aiwText{width:100%;min-height:78px;resize:none;padding:10px 12px;border-radius:10px;',
        'background:rgba(9,12,21,.72);border:1px solid rgba(120,150,210,.26);color:#e6ebf7;',
        'font:inherit;font-size:13px;line-height:1.55;outline:none;transition:border-color .2s,box-shadow .2s}',
        '#aiwText:focus{border-color:rgba(143,179,255,.65);box-shadow:0 0 0 3px rgba(111,155,255,.13)}',
        '#aiwText::placeholder{color:#6b7896}',

        '.aiw-row{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:8px}',
        '.aiw-kbd{font-size:10.5px;color:#6b7896}',
        '.aiw-btn{border:0;border-radius:9px;padding:8px 15px;font:inherit;font-size:12.5px;font-weight:600;',
        'color:#fff;cursor:pointer;background:linear-gradient(135deg,#6f9bff,#8f7bff);',
        'box-shadow:0 4px 14px rgba(111,155,255,.28);transition:filter .2s,transform .15s,opacity .2s}',
        '.aiw-btn:hover{filter:brightness(1.1)}',
        '.aiw-btn:active{transform:scale(.97)}',
        '.aiw-btn:disabled{opacity:.5;cursor:default;filter:none}',
        '.aiw-ghost{background:rgba(255,255,255,.06);color:#93a0bb;border:1px solid rgba(120,150,210,.22);box-shadow:none}',

        '.aiw-status{min-height:16px;margin-top:8px;font-size:12px;color:#93a0bb}',
        '.aiw-status.err{color:#ff8a8a}',

        '.aiw-items{margin-top:4px}',
        '.aiw-item{display:grid;grid-template-columns:1fr 76px 66px 50px 22px;gap:6px;align-items:center;margin-bottom:6px}',
        '.aiw-item-head{font-size:10.5px;color:#6b7896;padding:0 2px;margin-bottom:4px}',
        '.aiw-item input,.aiw-item select{width:100%;height:30px;padding:0 7px;border-radius:7px;',
        'background:rgba(9,12,21,.72);border:1px solid rgba(120,150,210,.22);color:#e6ebf7;',
        'font:inherit;font-size:12.5px;outline:none;transition:border-color .2s}',
        '.aiw-item input:focus,.aiw-item select:focus{border-color:rgba(143,179,255,.6)}',
        '.aiw-del{border:0;background:transparent;color:#6b7896;font-size:16px;line-height:1;cursor:pointer;',
        'padding:0;border-radius:5px;transition:color .2s,background .2s}',
        '.aiw-del:hover{color:#ff8a8a;background:rgba(255,107,107,.12)}',

        '.aiw-foot{display:flex;justify-content:flex-end;gap:8px;margin-top:10px}',
        '.aiw-done{padding:14px 4px 2px;font-size:13px;font-weight:600;color:#4ade80}',
        '.aiw-advice{margin-top:10px;padding:10px 12px;border-radius:10px;font-size:12.5px;line-height:1.65;',
        'color:#dbe5fb;background:rgba(111,155,255,.09);border:1px solid rgba(111,155,255,.22);white-space:pre-wrap}',
        '.aiw-advice-tag{display:block;font-size:10px;letter-spacing:.1em;color:#8fb3ff;margin-bottom:5px}',

        '.aiw-bubble{position:fixed;right:80px;bottom:28px;max-width:300px;max-height:220px;overflow:auto;',
        'padding:9px 13px;pointer-events:auto;cursor:pointer;white-space:pre-wrap;',
        'font-size:12.5px;line-height:1.55;color:#dbe5fb;',
        'background:rgba(20,25,40,.96);border:1px solid rgba(120,150,210,.3);border-radius:13px;',
        'box-shadow:0 10px 30px rgba(0,0,0,.5);opacity:0;transform:translateY(6px) scale(.97);',
        'transition:opacity .2s,transform .2s}',
        '.aiw-bubble.show{opacity:1;transform:none}',
        '.aiw-bubble::after{content:"";position:absolute;right:-5px;bottom:14px;width:10px;height:10px;',
        'background:inherit;border-right:1px solid rgba(120,150,210,.3);border-bottom:1px solid rgba(120,150,210,.3);',
        'transform:rotate(45deg)}'
    ].join('');

    var styleEl = document.createElement('style');
    styleEl.id = 'aiwStyle';
    styleEl.textContent = css;
    document.head.appendChild(styleEl);

    /* ==================== DOM ==================== */

    var root = document.createElement('div');
    root.id = 'aiw';
    root.innerHTML =
        '<div class="aiw-bubble" id="aiwBubble" hidden></div>' +
        '<div class="aiw-panel" id="aiwPanel" hidden>' +
        '  <div class="aiw-head">' +
        '    <span class="aiw-title"><span class="aiw-dot"></span>AI 记账</span>' +
        '    <button class="aiw-close" id="aiwClose" aria-label="关闭">&times;</button>' +
        '  </div>' +
        '  <textarea id="aiwText" rows="3" placeholder="今天买了什么？例如：中午牛肉面 28，晚上打车 35"></textarea>' +
        '  <div class="aiw-row"><span class="aiw-kbd">Enter 解析 &middot; Shift+Enter 换行</span>' +
        '    <button class="aiw-btn" id="aiwParse">解析</button></div>' +
        '  <div class="aiw-status" id="aiwStatus"></div>' +
        '  <div class="aiw-items" id="aiwItems"></div>' +
        '  <div class="aiw-advice" id="aiwAdvice" hidden></div>' +
        '</div>' +
        '<button class="aiw-orb" id="aiwOrb" aria-label="AI 记账" title="AI 记账（双击给建议）">' +
        '  <svg viewBox="0 0 24 24" width="23" height="23" fill="none" stroke="currentColor" ' +
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">' +
        '    <path d="M11 3.5l1.7 4.4 4.4 1.7-4.4 1.7L11 15.7 9.3 11.3 4.9 9.6l4.4-1.7z"/>' +
        '    <path d="M18 14.5l.8 2.1 2.1.8-2.1.8-.8 2.1-.8-2.1-2.1-.8 2.1-.8z"/>' +
        '  </svg>' +
        '</button>';
    document.body.appendChild(root);

    var orb = document.getElementById('aiwOrb');
    var panel = document.getElementById('aiwPanel');
    var bubble = document.getElementById('aiwBubble');
    var textarea = document.getElementById('aiwText');
    var statusEl = document.getElementById('aiwStatus');
    var itemsEl = document.getElementById('aiwItems');
    var adviceEl = document.getElementById('aiwAdvice');
    var parseBtn = document.getElementById('aiwParse');

    /* ==================== 小工具 ==================== */

    function setStatus(text, isErr) {
        statusEl.textContent = text || '';
        statusEl.className = 'aiw-status' + (isErr ? ' err' : '');
    }

    function setBusy(b) {
        state.busy = b;
        parseBtn.disabled = b;
        textarea.disabled = b;
        var btn = document.getElementById('aiwConfirm');
        if (btn) btn.disabled = b;
    }

    function clearItems() {
        itemsEl.innerHTML = '';
        adviceEl.hidden = true;
        adviceEl.textContent = '';
    }

    function showBubble(text, ms, mode) {
        clearTimeout(state.bubbleTimer);
        state.bubbleMode = mode || '';
        bubble.textContent = text;
        bubble.hidden = false;
        // 下一帧再加 show，保证过渡动画
        requestAnimationFrame(function () { bubble.classList.add('show'); });
        if (ms > 0) {
            state.bubbleTimer = setTimeout(hideBubble, ms);
        }
    }

    function hideBubble() {
        clearTimeout(state.bubbleTimer);
        bubble.classList.remove('show');
        setTimeout(function () {
            if (!bubble.classList.contains('show')) bubble.hidden = true;
        }, 220);
        state.bubbleMode = '';
    }

    function post(url, data) {
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data || {})
        }).then(function (r) { return r.json(); });
    }

    /* ==================== 面板 ==================== */

    function openPanel() {
        if (state.open) return;
        state.open = true;
        hideBubble();
        panel.hidden = false;
        requestAnimationFrame(function () { panel.classList.add('show'); });
        setStatus('');
        clearItems();
        setTimeout(function () { textarea.focus(); }, 60);
    }

    function closePanel() {
        state.open = false;
        panel.classList.remove('show');
        setTimeout(function () {
            if (!state.open) panel.hidden = true;
        }, 200);
    }

    function renderItems(items) {
        itemsEl.innerHTML = '';
        var head = document.createElement('div');
        head.className = 'aiw-item aiw-item-head';
        ['商品', '金额', '类型', '日期', ''].forEach(function (t) {
            var s = document.createElement('span');
            s.textContent = t;
            head.appendChild(s);
        });
        itemsEl.appendChild(head);

        items.forEach(function (it) {
            var row = document.createElement('div');
            row.className = 'aiw-item';

            var g = document.createElement('input');
            g.className = 'aiw-g';
            g.maxLength = 40;
            g.value = it.goods;

            var p = document.createElement('input');
            p.className = 'aiw-p';
            p.type = 'number';
            p.step = '0.01';
            p.min = '0.01';
            p.value = it.price;

            var t = document.createElement('select');
            t.className = 'aiw-t';
            var o1 = document.createElement('option');
            o1.value = '1'; o1.textContent = '日常';
            var o2 = document.createElement('option');
            o2.value = '2'; o2.textContent = '特殊';
            t.appendChild(o1); t.appendChild(o2);
            t.value = String(it.type === 2 ? 2 : 1);

            var d = document.createElement('input');
            d.className = 'aiw-d';
            d.type = 'number';
            d.min = '1';
            d.max = '31';
            d.value = it.day;

            var del = document.createElement('button');
            del.className = 'aiw-del';
            del.type = 'button';
            del.title = '删除这条';
            del.textContent = '\u00d7';
            del.onclick = function () { row.remove(); };

            row.appendChild(g); row.appendChild(p); row.appendChild(t);
            row.appendChild(d); row.appendChild(del);
            itemsEl.appendChild(row);
        });

        var foot = document.createElement('div');
        foot.className = 'aiw-foot';

        var cancel = document.createElement('button');
        cancel.className = 'aiw-btn aiw-ghost';
        cancel.type = 'button';
        cancel.textContent = '取消';
        cancel.onclick = function () { clearItems(); setStatus(''); };

        var ok = document.createElement('button');
        ok.className = 'aiw-btn';
        ok.id = 'aiwConfirm';
        ok.type = 'button';
        ok.textContent = '确认入库';
        ok.onclick = confirmSave;

        foot.appendChild(cancel);
        foot.appendChild(ok);
        itemsEl.appendChild(foot);
    }

    function collectItems() {
        var out = [];
        var rows = itemsEl.querySelectorAll('.aiw-item:not(.aiw-item-head)');
        for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var g = row.querySelector('.aiw-g').value.trim();
            var p = parseFloat(row.querySelector('.aiw-p').value);
            var t = parseInt(row.querySelector('.aiw-t').value, 10) === 2 ? 2 : 1;
            var d = parseInt(row.querySelector('.aiw-d').value, 10);
            if (!g || !(p > 0)) continue;
            if (!d || d < 1) d = new Date().getDate();
            if (d > 31) d = 31;
            out.push({
                goods: g,
                price: Math.round(p * 100) / 100,
                type: t,
                day: d
            });
        }
        return out;
    }

    function confirmSave() {
        if (state.busy) return;
        var items = collectItems();
        if (!items.length) {
            setStatus('至少保留一条：商品名和金额都要有', true);
            return;
        }
        setBusy(true);
        setStatus('正在写入并生成建议…');
        post('/ai/save', { items: items }).then(function (j) {
            setBusy(false);
            if (!j.ok) {
                setStatus(j.error || '入库失败', true);
                return;
            }
            itemsEl.innerHTML = '';
            var done = document.createElement('div');
            done.className = 'aiw-done';
            done.textContent = '\u2713 已记 ' + j.saved + ' 笔';
            itemsEl.appendChild(done);

            var foot = document.createElement('div');
            foot.className = 'aiw-foot';
            var again = document.createElement('button');
            again.className = 'aiw-btn aiw-ghost';
            again.type = 'button';
            again.textContent = '继续记一笔';
            again.onclick = function () {
                clearItems();
                setStatus('');
                textarea.focus();
            };
            foot.appendChild(again);
            itemsEl.appendChild(foot);

            if (j.advice) {
                showAdvice(j.advice);
                setStatus('');
            } else if (j.adviceError) {
                setStatus('已记 ' + j.saved + ' 笔，建议没拿到：' + j.adviceError, true);
            } else {
                setStatus('');
            }
        }).catch(function () {
            setBusy(false);
            setStatus('请求失败，检查服务是否在运行', true);
        });
    }

    function showAdvice(text) {
        adviceEl.hidden = false;
        adviceEl.textContent = '';
        var tag = document.createElement('span');
        tag.className = 'aiw-advice-tag';
        tag.textContent = '本月建议';
        adviceEl.appendChild(tag);
        adviceEl.appendChild(document.createTextNode(text));
    }

    function doParse() {
        if (state.busy) return;
        var text = textarea.value.trim();
        if (!text) {
            textarea.focus();
            setStatus('先说说今天买了什么', true);
            return;
        }
        setBusy(true);
        setStatus('AI 正在解析…');
        clearItems();
        post('/ai/parse', { text: text }).then(function (j) {
            setBusy(false);
            if (!j.ok) {
                setStatus(j.error || '解析失败', true);
                return;
            }
            textarea.value = '';
            setStatus('识别到 ' + j.items.length + ' 笔，确认后入库');
            renderItems(j.items);
        }).catch(function () {
            setBusy(false);
            setStatus('请求失败，检查服务是否在运行', true);
        });
    }

    function requestAdvice() {
        if (state.busy) return;
        state.busy = true;
        showBubble('正在看这个月的账…', 0, 'advice');
        post('/ai/advice', {}).then(function (j) {
            state.busy = false;
            if (!j.ok) {
                showBubble(j.error || '拿不到建议', 6000, 'advice');
                return;
            }
            if (state.open) {
                hideBubble();
                showAdvice(j.advice);
                setStatus('');
            } else {
                showBubble(j.advice, 20000, 'advice');
            }
        }).catch(function () {
            state.busy = false;
            showBubble('请求失败，检查服务是否在运行', 6000, 'advice');
        });
    }

    /* ==================== 事件 ==================== */

    orb.addEventListener('click', function () {
        if (state.clickTimer) return;
        state.clickTimer = setTimeout(function () {
            state.clickTimer = 0;
            ensureOllama(function (ok) {
                if (ok) openPanel();
                else showBubble('Ollama 没在运行，先启动它再来～', 6000, 'advice');
            });
        }, 280);
    });

    orb.addEventListener('dblclick', function () {
        if (state.clickTimer) {
            clearTimeout(state.clickTimer);
            state.clickTimer = 0;
        }
        ensureOllama(function (ok) {
            if (ok) requestAdvice();
            else showBubble('Ollama 没在运行，先启动它再来～', 6000, 'advice');
        });
    });

    document.getElementById('aiwClose').onclick = closePanel;
    parseBtn.onclick = doParse;

    textarea.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            doParse();
        }
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && state.open) closePanel();
    });

    bubble.addEventListener('click', function () {
        if (state.bubbleMode === 'prompt') {
            hideBubble();
            ensureOllama(function (ok) {
                if (ok) openPanel();
                else showBubble('Ollama 没在运行，先启动它再来～', 6000, 'advice');
            });
        } else {
            hideBubble();
        }
    });

    /* 空闲提醒：60 秒没动静冒第一句，之后最多每 4 分钟换一句 */
    function markActive() {
        state.lastActive = Date.now();
        if (state.bubbleMode === 'prompt') hideBubble();
    }
    ['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach(function (ev) {
        document.addEventListener(ev, markActive, { passive: true, capture: true });
    });

    setInterval(function () {
        if (state.open || state.busy || !bubble.hidden) return;
        var idle = Date.now() - state.lastActive;
        if (idle < 60000) return;
        if (Date.now() - state.lastPrompt < 240000) return;
        state.lastPrompt = Date.now();
        showBubble(PROMPTS[state.promptIdx++ % PROMPTS.length], 9000, 'prompt');
    }, 10000);

    /* ==================== Ollama 状态 ==================== */

    function ensureOllama(cb) {
        if (state.ollamaOk === true) { cb(true); return; }
        fetch('/ai/status', { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                state.ollamaOk = !!j.ok;
                orb.classList.toggle('aiw-off', !j.ok);
                orb.title = j.ok ? 'AI 记账（双击给建议）' : 'Ollama 未运行（' + (j.model || '') + '）';
                cb(!!j.ok);
            })
            .catch(function () { cb(false); });
    }

    ensureOllama(function () {});
})();
