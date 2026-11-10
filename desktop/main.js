'use strict';

const { app, BrowserWindow, dialog, shell } = require('electron');
const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');

const APP_ID = 'com.lightofhumanity.desktop';
const isDev = !app.isPackaged;

let javaProc = null;
let mainWindow = null;
let quitting = false;

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

function resolveJava(runtime) {
  const bundled = path.join(runtime, 'jre', 'bin', 'java.exe');
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
<div class="box"><div class="dot"></div><p>正在启动人间烟火…</p></div>`;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 940,
    minHeight: 620,
    backgroundColor: '#07101d',
    show: false,
    autoHideMenuBar: true,
    title: '人间烟火',
    icon: path.join(__dirname, 'build', 'icon.ico'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  });

  mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(LOADING_HTML));
  mainWindow.once('ready-to-show', () => mainWindow.show());

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

/* ---------------- 生命周期 ---------------- */

async function boot() {
  createWindow();
  try {
    const url = await startServer();
    if (!mainWindow) return;
    bindPageGuards(url);
    await mainWindow.loadURL(url);
  } catch (err) {
    dialog.showErrorBox('人间烟火启动失败', String(err && err.message ? err.message : err));
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
