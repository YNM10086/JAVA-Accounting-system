/*
 * 软导航：拦截站内 GET 跳转，只替换 <main> 内容，让海洋背景 canvas 一直活着。
 * - 只接管左键点击的站内链接和 method=get 的表单
 * - POST（保存/删除）仍然整页提交，避免动写库逻辑
 * - /export、/chart-img 这类文件/图片请求不接管
 */
(function () {
    'use strict';

    var SKIP = /^\/(export|chart-img)(\/|$)/;
    var seq = 0;

    /* ---------- 顶部细进度条 ---------- */
    var bar = document.createElement('div');
    bar.id = 'lohNavBar';
    document.body.appendChild(bar);

    function barStart() {
        bar.style.transition = 'none';
        bar.style.width = '0';
        bar.style.opacity = '1';
        // 强制 reflow，让宽度从 0 开始过渡
        void bar.offsetWidth;
        bar.style.transition = 'width .6s ease,opacity .25s ease';
        bar.style.width = '72%';
    }

    function barEnd() {
        bar.style.width = '100%';
        setTimeout(function () {
            bar.style.opacity = '0';
            setTimeout(function () { bar.style.width = '0'; }, 250);
        }, 120);
    }

    /* ---------- 重新执行被替换内容里的 <script> ---------- */
    function runScripts(root) {
        var loaded = window.__lohLoadedScripts || (window.__lohLoadedScripts = {});
        var list = Array.prototype.slice.call(root.querySelectorAll('script'));
        list.forEach(function (old) {
            var src = old.getAttribute('src');
            if (src) {
                if (loaded[src]) {
                    old.parentNode.removeChild(old);
                    return;
                }
                loaded[src] = true;
            }
            var s = document.createElement('script');
            for (var i = 0; i < old.attributes.length; i++) {
                s.setAttribute(old.attributes[i].name, old.attributes[i].value);
            }
            s.text = old.textContent;
            old.parentNode.replaceChild(s, old);
        });
    }

    /* ---------- 核心：取页面、换 main ---------- */
    function navigate(url, push) {
        var mine = ++seq;
        barStart();
        fetch(url, {
            cache: 'no-store',
            credentials: 'same-origin',
            headers: { 'X-LOH-Soft-Nav': '1' }
        }).then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.text();
        }).then(function (html) {
            if (mine !== seq) return;                       // 有更新的导航，丢弃这次
            var doc = new DOMParser().parseFromString(html, 'text/html');
            var next = doc.querySelector('main.container');
            var cur = document.querySelector('main.container');
            if (!next || !cur) { location.href = url; return; }

            cur.innerHTML = next.innerHTML;
            if (doc.title) document.title = doc.title;
            if (push) history.pushState({ loh: 1 }, '', url);

            window.scrollTo(0, 0);
            runScripts(cur);
            barEnd();
        }).catch(function () {
            if (mine !== seq) return;
            barEnd();
            location.href = url;                             // 出错就退回整页跳转
        });
    }

    /* ---------- 链接点击 ---------- */
    document.addEventListener('click', function (e) {
        if (e.defaultPrevented || e.button !== 0 ||
            e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

        var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
        if (!a) return;
        if (a.target && a.target !== '_self') return;
        if (a.hasAttribute('download')) return;

        var href = a.getAttribute('href');
        if (!href || href.charAt(0) === '#') return;
        if (/^(mailto:|tel:|javascript:)/i.test(href)) return;

        var url;
        try { url = new URL(href, location.href); } catch (_) { return; }
        if (url.origin !== location.origin) return;
        if (SKIP.test(url.pathname)) return;

        e.preventDefault();
        navigate(url.pathname + url.search, true);
    }, false);

    /* ---------- GET 表单（月份/日期筛选） ---------- */
    document.addEventListener('submit', function (e) {
        var f = e.target;
        if (!f || !f.tagName || f.tagName !== 'FORM') return;
        var method = (f.getAttribute('method') || 'get').toLowerCase();
        if (method !== 'get') return;                        // POST 交给浏览器
        if (f.target && f.target !== '_self') return;

        var action = (f.getAttribute('action') || location.pathname).split('#')[0];
        var params = new URLSearchParams();
        new FormData(f).forEach(function (v, k) { params.append(k, v); });
        var qs = params.toString();
        if (qs) action += (action.indexOf('?') >= 0 ? '&' : '?') + qs;

        e.preventDefault();
        navigate(action, true);
    }, false);

    /* ---------- 浏览器前进/后退 ---------- */
    window.addEventListener('popstate', function () {
        navigate(location.pathname + location.search, false);
    });
})();
