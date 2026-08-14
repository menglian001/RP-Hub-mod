// 临时诊断脚本：排查 App 内 novel 页面无法加载的问题
// 定位完成后可删除本文件及 index.html 中的引用
(function () {
    'use strict';

    var SITE = 'https://rp-hub-mod.pages.dev';

    function collect(done) {
        var info = {};

        info.href = location.href;
        info.origin = location.origin;

        // 壳暴露的桥接方法
        var bridge = window.RPHubNative;
        info.hasBridge = typeof bridge !== 'undefined';
        if (info.hasBridge) {
            var methods = [];
            for (var k in bridge) {
                try { methods.push(k + (typeof bridge[k] === 'function' ? '()' : '')); } catch (e) { }
            }
            info.bridgeMethods = methods.join(', ') || '(无法枚举)';
            try {
                info.contentVersion = typeof bridge.contentVersion === 'function'
                    ? bridge.contentVersion() : String(bridge.contentVersion);
            } catch (e) { info.contentVersion = 'ERR: ' + e.message; }
            try {
                info.announcement = typeof bridge.getAnnouncement === 'function'
                    ? String(bridge.getAnnouncement()).slice(0, 200) : 'n/a';
            } catch (e) { info.announcement = 'ERR: ' + e.message; }
        }

        // 逐个探测本地资源是否可达
        var probes = [
            ['novel/index.html', './novel/index.html'],
            ['character/index.html', './character/index.html'],
            ['assets/js/app.js', './assets/js/app.js'],
            ['version.json', './version.json']
        ];

        info.probes = [];
        var pending = probes.length;

        probes.forEach(function (p) {
            var label = p[0], url = p[1];
            var t0 = Date.now();
            fetch(url, { method: 'GET', cache: 'no-store' })
                .then(function (r) {
                    return r.text().then(function (body) {
                        info.probes.push(label + ' -> HTTP ' + r.status
                            + ' / ' + body.length + ' bytes / ' + (Date.now() - t0) + 'ms');
                    });
                })
                .catch(function (e) {
                    info.probes.push(label + ' -> FAIL: ' + e.message);
                })
                .then(function () {
                    if (--pending === 0) done(info);
                });
        });
    }

    function render(info) {
        var lines = [];
        lines.push('页面地址: ' + info.href);
        lines.push('Origin: ' + info.origin);
        lines.push('原生桥: ' + (info.hasBridge ? '存在' : '不存在（当前是浏览器环境）'));
        if (info.hasBridge) {
            lines.push('当前内容版本: ' + info.contentVersion);
            lines.push('桥方法: ' + info.bridgeMethods);
            lines.push('公告数据: ' + info.announcement);
        }
        lines.push('');
        lines.push('本地资源探测:');
        info.probes.sort().forEach(function (p) { lines.push('  ' + p); });

        var text = lines.join('\n');

        var box = document.createElement('div');
        box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;'
            + 'max-height:60vh;overflow:auto;background:#111;color:#0f0;'
            + 'font:12px/1.6 monospace;padding:12px 12px 40px;'
            + 'white-space:pre-wrap;word-break:break-all;'
            + '-webkit-user-select:text;user-select:text;';
        box.textContent = text;

        var close = document.createElement('button');
        close.textContent = '关闭诊断';
        close.style.cssText = 'position:absolute;right:8px;top:8px;background:#c00;color:#fff;'
            + 'border:0;padding:6px 10px;border-radius:4px;font:12px monospace;';
        close.onclick = function () { box.remove(); };
        box.appendChild(close);

        var copy = document.createElement('button');
        copy.textContent = '复制';
        copy.style.cssText = 'position:absolute;right:160px;top:8px;background:#06c;color:#fff;'
            + 'border:0;padding:6px 10px;border-radius:4px;font:12px monospace;';
        copy.onclick = function () {
            try {
                var ta = document.createElement('textarea');
                ta.value = text;
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                ta.remove();
                copy.textContent = '已复制';
            } catch (e) { copy.textContent = '复制失败'; }
        };
        box.appendChild(copy);

        var update = document.createElement('button');
        update.textContent = '强制检查更新';
        update.style.cssText = 'position:absolute;right:8px;top:40px;background:#f80;color:#000;'
            + 'border:0;padding:6px 10px;border-radius:4px;font:12px monospace;font-weight:bold;';
        update.onclick = function () {
            if (window.RPHubNative && typeof window.RPHubNative.checkUpdate === 'function') {
                try {
                    window.RPHubNative.checkUpdate();
                    update.textContent = '已触发';
                } catch (e) {
                    update.textContent = 'ERR: ' + e.message;
                }
            } else {
                update.textContent = '无 checkUpdate 方法';
            }
        };
        box.appendChild(update);

        document.body.appendChild(box);
        console.log('[HOTFIX-DIAG]\n' + text);
    }

    function start() {
        // 延迟一点，等原生桥注入完成
        setTimeout(function () { collect(render); }, 2500);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
