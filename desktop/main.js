'use strict';

const { app, BrowserWindow, dialog, shell, net } = require('electron');
const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');

const APP_ID = 'com.bookkeepingsystem.desktop';
const APP_NAME = 'Bookkeeping System';
const isDev = !app.isPackaged;

let javaProc = null;
let mainWindow = null;
let quitting = false;

// 数据目录固定在这里：以后改显示名/产品名，账本数据也不会跟着搬家
app.setPath('userData', path.join(app.getPath('appData'), 'BookkeepingSystem'));
app.setAppUserModelId(APP_ID);

/* ---------------- 路径 ---------------- */

function runtimeRoot() {
  return isDev
    ? path.join(__dirname, 'runtime')
    : path.join(process.resourcesPath, 'runtime');
}

/** 数据目录：The_Pic / records / static 都放这里，避免写进安装目录 */
function dataRoot() {
  return path.join(app.getPath('userData'), 'data');
}

function log(msg) {
  try {
    const dir = app.getPath('userData');
    fs.mkdirSync(dir, { recursive: true });
    fs.appendFileSync(path.join(dir, 'desktop.log'), `[${new Date().toISOString()}] ${msg}\n`);
  } catch (_) {
    /* 日志失败不影响主流程 */
  }
}

function resolveJava(runtime) {  const bundled = path.join(runtime, 'jre', 'bin', 'java.exe');
  if (fs.existsSync(bundled)) return bundled;
  if (process.env.JAVA_HOME) {
    const fromHome = path.join(process.env.JAVA_HOME, 'bin', 'java.exe');
    if (fs.existsSync(fromHome)) return fromHome;
  }
  return 'java';
}

function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const src = path.join(from, entry.name);
    const dst = path.join(to, entry.name);
    if (entry.isDirectory()) copyDir(src, dst);
    else fs.copyFileSync(src, dst);
  }
}

/* ---------------- 启动 Java 服务 ---------------- */

function startServer() {
  return new Promise((resolve, reject) => {
    const runtime = runtimeRoot();
    const classes = path.join(runtime, 'classes');
    if (!fs.existsSync(classes)) {
      reject(new Error(`找不到 Java 程序目录：\n${classes}\n\n请先运行 build.ps1`));
      return;
    }

    const dataDir = dataRoot();
    fs.mkdirSync(dataDir, { recursive: true });
    // 静态资源随版本更新，每次启动覆盖一份到数据目录
    copyDir(path.join(runtime, 'static'), path.join(dataDir, 'static'));

    const cp = [classes, path.join(runtime, 'lib', '*')].join(path.delimiter);
    const java = resolveJava(runtime);

    let log = '';
    let settled = false;

    javaProc = spawn(
      java,
      ['-Dfile.encoding=UTF-8', '-cp', cp, 'System.The_main'],
      {
        cwd: dataDir,
        env: { ...process.env, LOH_NO_BROWSER: '1' },
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      }
    );

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error(`等待服务启动超时（45 秒）\n\n${log.slice(-1500)}`));
    }, 45000);

    const onData = (chunk) => {
      log += chunk.toString('utf8');
      if (log.length > 20000) log = log.slice(-10000);
      const m = /LOH_URL=(http:\/\/\S+)/.exec(log);
      if (m && !settled) {
        settled = true;
        clearTimeout(timer);
        resolve(m[1].trim());
      }
    };

    javaProc.stdout.on('data', onData);
    javaProc.stderr.on('data', onData);

    javaProc.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(new Error(`无法启动 Java：${err.message}\n\n请安装 JDK 17+ 或把 JRE 放到 runtime/jre`));
    });

    javaProc.on('exit', (code) => {
      javaProc = null;
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(new Error(`服务进程提前退出（code ${code}）\n\n${log.slice(-1500)}`));
        return;
      }
      if (!quitting) {
        dialog.showErrorBox('服务已停止', `后台服务意外退出（code ${code}）。\n\n${log.slice(-800)}`);
        app.quit();
      }
    });
  });
}

function killJava() {
  if (!javaProc || javaProc.killed) return;
  const pid = javaProc.pid;
  javaProc = null;
  try {
    spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
  } catch (_) {
    /* ignore */
  }
}

/* ---------------- 窗口 ---------------- */

const LOADING_HTML = `<!doctype html><meta charset="utf-8">
<style>
  html,body{height:100%;margin:0;background:#07101d;color:#c8d6f0;
    font:14px/1.6 "Microsoft YaHei",system-ui,sans-serif;
    display:flex;align-items:center;justify-content:center;user-select:none}
  .box{text-align:center}
  .dot{width:10px;height:10px;margin:0 auto 18px;border-radius:50%;
    background:#ffb066;box-shadow:0 0 24px 6px rgba(255,150,70,.45);
    animation:pulse 1.2s ease-in-out infinite}
  @keyframes pulse{50%{opacity:.35;transform:scale(.85)}}
  p{margin:0;letter-spacing:.08em;opacity:.75}
</style>
<div class="box"><div class="dot"></div><p>正在启动 Bookkeeping System…</p></div>`;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 940,
    minHeight: 620,
    backgroundColor: '#07101d',
    show: false,
    autoHideMenuBar: true,
    title: APP_NAME,
    icon: path.join(__dirname, 'build', 'icon.ico'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  });

  mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(LOADING_HTML));
  mainWindow.once('ready-to-show', () => mainWindow.show());
  // 页面里的 <title> 不覆盖窗口标题，保持应用名一致
  mainWindow.on('page-title-updated', (e) => e.preventDefault());
  mainWindow.setTitle(APP_NAME);

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function bindPageGuards(baseUrl) {
  const wc = mainWindow.webContents;
  wc.on('will-navigate', (event, target) => {
    if (!target.startsWith(baseUrl)) {
      event.preventDefault();
      if (/^https?:/i.test(target)) shell.openExternal(target);
    }
  });
  wc.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i')) {
      wc.toggleDevTools();
    }
  });
}

/* ---------------- 自动更新（GitHub Releases，走 API 资产地址） ----------------
 * 不用 electron-updater：它会去 github.com/releases/download 拉资产，该域名在
 * 部分网络下不可达。这里改用 api.github.com 的资产地址（Accept: octet-stream），
 * 由 GitHub 302 到 release-assets.githubusercontent.com，实测可正常下载。
 */

const UPDATE_API = 'https://api.github.com/repos/YNM10086/JAVA-Accounting-system/releases/latest';
const SETUP_RE = /^BookkeepingSystem-.*-setup\.exe$/i;

let updateBusy = false;

function parseVersion(v) {
  return String(v || '').replace(/^v/i, '').split('.').map((n) => parseInt(n, 10) || 0);
}

function isNewer(remote, local) {
  const a = parseVersion(remote);
  const b = parseVersion(local);
  for (let i = 0; i < 3; i++) {
    if ((a[i] || 0) > (b[i] || 0)) return true;
    if ((a[i] || 0) < (b[i] || 0)) return false;
  }
  return false;
}

async function fetchLatestRelease() {
  const res = await net.fetch(UPDATE_API, { headers: { Accept: 'application/vnd.github+json' } });
  if (res.status === 404) return null;               // 还没发过 release
  if (!res.ok) throw new Error('GitHub API HTTP ' + res.status);
  const rel = await res.json();
  const version = String(rel.tag_name || '').replace(/^v/i, '');
  if (!version || !isNewer(version, app.getVersion())) return null;

  const assets = rel.assets || [];
  const setup = assets.find((a) => SETUP_RE.test(a.name));
  if (!setup) throw new Error('release 里没有安装包资产');
  const meta = assets.find((a) => a.name === 'latest.yml') || null;
  return { version, setup, meta };
}

async function readAssetText(asset) {
  const res = await net.fetch(asset.url, { headers: { Accept: 'application/octet-stream' } });
  if (!res.ok) throw new Error('读取 ' + asset.name + ' 失败 HTTP ' + res.status);
  return res.text();
}

/** 流式下载并计算 sha512（base64），返回 { size, sha512 } */
async function downloadAsset(asset, dest) {
  const res = await net.fetch(asset.url, { headers: { Accept: 'application/octet-stream' } });
  if (!res.ok) throw new Error('下载失败 HTTP ' + res.status);

  const total = Number(res.headers.get('content-length')) || asset.size || 0;
  const hash = crypto.createHash('sha512');
  const out = fs.createWriteStream(dest);
  const reader = res.body.getReader();
  let done = 0;

  for (;;) {
    const { done: end, value } = await reader.read();
    if (end) break;
    const chunk = Buffer.from(value);
    hash.update(chunk);
    done += chunk.length;
    if (!out.write(chunk)) await new Promise((r) => out.once('drain', r));

    if (total) {
      const pct = Math.min(1, done / total);
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.setProgressBar(pct);
        if (done % (4 * 1024 * 1024) < chunk.length) {
          barNotify(`正在下载更新 ${Math.round(pct * 100)}%`);
        }
      }
    }
  }

  await new Promise((resolve, reject) => out.end((err) => (err ? reject(err) : resolve())));
  return { size: done, sha512: hash.digest('base64') };
}

/** 退出后用独立脚本静默安装，装完再拉起新版本 */
function applyUpdate(file) {
  const safe = (s) => s.replace(/'/g, "''");
  const script = path.join(path.dirname(file), 'apply-update.ps1');
  const ps = [
    "$ErrorActionPreference='SilentlyContinue'",
    `for($i=0;$i -lt 240;$i++){ if(-not (Get-Process -Id ${process.pid} -ErrorAction SilentlyContinue)){ break }; Start-Sleep -Milliseconds 500 }`,
    `Start-Process -FilePath '${safe(file)}' -ArgumentList '/S' -Wait`,
    `Start-Process -FilePath '${safe(process.execPath)}'`,
  ].join('\r\n');
  fs.writeFileSync(script, ps, 'utf8');

  spawn('powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', script],
    { detached: true, stdio: 'ignore', windowsHide: true }).unref();

  quitting = true;
  killJava();
  app.quit();
}

function setupUpdater() {
  if (!app.isPackaged) return;

  setTimeout(async () => {
    if (updateBusy) return;
    updateBusy = true;
    try {
      const info = await fetchLatestRelease();
      if (!info) {
        log('update: no newer release');
        updateBusy = false;
        return;
      }
      log('update available: ' + info.version);

      const mb = Math.round((info.setup.size || 0) / (1024 * 1024));
      const ask = await dialog.showMessageBox(mainWindow, {
        type: 'info',
        buttons: ['下载并安装', '稍后'],
        defaultId: 0,
        cancelId: 1,
        title: '发现新版本',
        message: `发现新版本 v${info.version}`,
        detail: `当前 v${app.getVersion()}${mb ? `，安装包约 ${mb} MB` : ''}。下载完成后会提示重启安装，账本数据不受影响。`,
      });
      if (ask.response !== 0) {
        updateBusy = false;
        return;
      }

      const dir = path.join(app.getPath('temp'), 'loh-update');
      fs.mkdirSync(dir, { recursive: true });
      const target = path.join(dir, info.setup.name);

      log('downloading update to ' + target);
      const got = await downloadAsset(info.setup, target);
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.setProgressBar(-1);

      if (info.meta) {
        try {
          const yml = await readAssetText(info.meta);
          const m = /sha512:\s*([A-Za-z0-9+/=]+)/.exec(yml);
          if (m && m[1] !== got.sha512) {
            fs.rmSync(target, { force: true });
            throw new Error('安装包校验失败（sha512 不匹配）');
          }
        } catch (e) {
          if (String(e.message).indexOf('校验失败') >= 0) throw e;
          log('update: sha512 skipped: ' + e.message);
        }
      }

      log('update downloaded: ' + got.size + ' bytes');
      const done = await dialog.showMessageBox(mainWindow, {
        type: 'info',
        buttons: ['立即重启并安装', '下次启动再装'],
        defaultId: 0,
        cancelId: 1,
        title: '更新已就绪',
        message: `v${info.version} 下载完成`,
        detail: '立即重启会关闭程序并静默安装，账本数据不会丢失。',
      });
      if (done.response === 0) {
        applyUpdate(target);
      } else {
        updateBusy = false;
      }
    } catch (e) {
      log('update failed: ' + (e && e.message));
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.setProgressBar(-1);
      updateBusy = false;
    }
  }, 5000);
}

/** 用窗口标题临时提示（不引入额外 UI） */
function barNotify(text) {
  if (!mainWindow) return;
  const old = mainWindow.getTitle();
  mainWindow.setTitle(text);
  setTimeout(() => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.setTitle(old);
  }, 4000);
}

/* ---------------- 生命周期 ---------------- */

async function boot() {
  createWindow();
  try {
    const url = await startServer();
    if (!mainWindow) return;
    bindPageGuards(url);
    await mainWindow.loadURL(url);
    log('ready: ' + url);
    setupUpdater();
  } catch (err) {
    log('boot failed: ' + (err && err.message ? err.message : err));
    dialog.showErrorBox(APP_NAME + ' 启动失败', String(err && err.message ? err.message : err));
    app.quit();
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  });

  app.whenReady().then(boot);

  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => {
    quitting = true;
    killJava();
  });
  process.on('exit', killJava);
}
