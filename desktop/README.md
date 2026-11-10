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
.\desktop\build.ps1
```

产物在 `desktop/dist/`：

- `TheLightOfHumanity-1.0.0-setup.exe` — NSIS 安装包（可选安装目录、建桌面/开始菜单快捷方式）
- `win-unpacked/` — 免安装目录

## 运行机制

1. `main.js` 用 `java -cp runtime/classes;runtime/lib/* System.The_main` 起后台服务，环境变量 `LOH_NO_BROWSER=1` 阻止它自己开浏览器
2. 服务在 stdout 打印 `LOH_URL=http://localhost:<port>`，主进程解析后 `loadURL`
3. 工作目录设为 `%APPDATA%/人间烟火/data`，`The_Pic/`、`records/`、`static/` 都落在这里，避免写入安装目录
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
