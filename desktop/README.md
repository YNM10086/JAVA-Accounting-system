# 桌面版（Electron）

把现有的 Java Web 服务套进 Electron 窗口：外壳负责开窗、启动/关闭 Java 子进程，页面仍然是同一套 HTML，只是不再有浏览器地址栏。

## 目录

```
desktop/
├── main.js          # 主进程：起 Java、解析 LOH_URL、开窗口、退出时收进程
├── package.json     # electron / electron-builder 配置
├── build.ps1        # 一键构建：编译 Java → 复制资源 → jlink JRE → NSIS 安装包
├── build/
│   ├── icon.ico     # 应用图标（多尺寸）
│   └── icon.png     # 256px 预览
└── runtime/         # 构建产物（gitignore）：classes / lib / static / jre
```

## 开发运行

```powershell
# 1. 准备 runtime/（编译 + 复制 lib、static）
.\desktop\build.ps1 -SkipJre -SkipNpm   # 只做前两步也可以手动执行

# 2. 安装依赖（首次）
cd desktop
npm install

# 3. 起窗口
npm start
```

## 打包

```powershell
.\desktop\build.ps1                        # 版本号不变
.\desktop\build.ps1 -Version 1.0.1         # 改版本号后打包
.\desktop\build.ps1 -Version 1.0.1 -Publish  # 打包并发布到 GitHub Releases
```

产物在 `desktop/dist/`：

- `BookkeepingSystem-<版本>-setup.exe` — NSIS 安装包（可选安装目录、建桌面/开始菜单快捷方式）
- `win-unpacked/` — 免安装目录
- `latest.yml` — 自动更新用的版本清单（发布时一起上传）

## 更新

两种方式，互不冲突。

### 自动更新（应用内点两下）

应用启动 5 秒后会静默查询 GitHub Releases：

1. 发现新版本 → 弹窗「发现新版本 vX，下载并安装 / 稍后」
2. 选下载 → 显示进度（窗口标题 + 任务栏进度条）→ 下载完校验 sha512
3. 弹窗「立即重启并安装 / 下次启动再装」→ 选立即 → 程序退出、静默安装、自动拉起新版本

数据在 `%APPDATA%\BookkeepingSystem\data`，升级不碰。

**发布新版本**：

```powershell
$env:GH_TOKEN='ghp_xxx'          # Personal Access Token，勾 repo 权限
.\desktop\build.ps1 -Version 1.0.1 -Publish
```

`-Publish` 会自动在 `YNM10086/JAVA-Accounting-system` 建 release 并上传安装包、blockmap、`latest.yml`。装着的旧版本下次启动就能收到。

### 手动更新（覆盖安装）

拿到新的 `setup.exe`，双击 → 下一步 → 完成。安装目录会被记住，快捷方式和数据都保留。

### 为什么不用 electron-updater

electron-updater 从 `github.com/.../releases/download/...` 拉资产，该域名在部分网络下直连超时。这里改成走 `api.github.com` 的资产地址（`Accept: application/octet-stream`），由 GitHub 302 到 `release-assets.githubusercontent.com`，实测可正常下载。逻辑在 `main.js` 的「自动更新」段，无第三方依赖。

排查更新问题看日志：`%APPDATA%\BookkeepingSystem\desktop.log`。

## 运行机制

1. `main.js` 用 `java -cp runtime/classes;runtime/lib/* System.The_main` 起后台服务，环境变量 `LOH_NO_BROWSER=1` 阻止它自己开浏览器
2. 服务在 stdout 打印 `LOH_URL=http://localhost:<port>`，主进程解析后 `loadURL`
3. 工作目录设为 `%APPDATA%/BookkeepingSystem/data`，`The_Pic/`、`records/`、`static/` 都落在这里，避免写入安装目录
4. 关窗 → `taskkill /T` 收掉 Java 子进程；单实例锁防止开两个窗口抢端口

端口仍按 `8080 → 8081 → 8082 → 9090` 回退，所以即使有残留进程也能起来。

## 图标

图标由 `tools/icon/IconGen.java` 原创绘制（深海底色 + 暖色几何图形），不依赖任何外部素材。三种风格：

| 风格 | 图形 | 说明 |
|---|---|---|
| `a`（默认） | 实心圆 + 三道同心圆环 | 光晕 / 涟漪，最抽象 |
| `b` | 实心圆 + 下方三道同心弧 | 日落与水面倒影 |
| `c` | 实心圆 + 水平线 + 半圆弧 | 极简地平线 |

```powershell
javac -encoding UTF-8 -d .build_tmp/icon tools/icon/IconGen.java
java -cp .build_tmp/icon IconGen desktop/build/icon.ico a
```

生成 `icon.ico`（16/24/32/48/64/128/256）、`icon.png`（256px 预览）和 `icon-sizes.png`（各尺寸对照）。换风格只改最后一个参数。

## 已知限制

- 需要目标机有 WebView/浏览器？不需要——Electron 自带 Chromium
- 数据库仍是本机 MySQL（`localhost:3306/Cost_system`），桌面版不改变这一点
- 若 `-SkipJre` 构建，目标机必须装 JDK 17+；完整构建会把精简 JRE 一起打进去
