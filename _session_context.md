# Bookkeeping System — 会话记忆

## 项目简述
个人消费记录系统（Bookkeeping System）：Java 17 + JDK HttpServer 后端，控制台菜单与 HTML/CSS/JS + Canvas 前端（前端内联于 WebServer.java），MySQL 存 `Cost_system` 库（每月一张 `table_XX`）。功能：消费存入、查询统计、折线图、预算管控、Excel 导出、ASCII 海洋动态背景、时段问候封面、跨年自动归档。已用 Electron 套壳成可双击安装的桌面版。

## 工作流
- 技术栈/入口：Java 17（MySQL Connector + Apache POI 在 `lib/`），IDEA 打开后运行 `src/System/The_main.java` 的 `main()`，启动后浏览器自动打开 http://localhost:8080
- 结构地图：
  - `src/System/The_main.java` — 程序入口，串联各模块并挂载 `Archiver.ensureRollover()`
  - `src/function/WebServer.java` — Web 服务器 + 全部页面逻辑（前端代码内联）
  - `src/function/Menu.java` — 控制台主菜单 / 查询子菜单 / 消费类型子菜单调度
  - `src/function/One.java` — 数据库连接工具（需配置 MySQL 连接信息）
  - `src/function/Date_time.java` — 日期时间工具
  - `src/function/Line.java` — 折线图生成（JDK Graphics2D）
  - `src/function/AiAssistant.java` — 本地 Ollama 调用（自然语言解析消费 / 生成本月建议 / 汇总本月数据）
  - `src/function/Json.java` — 极简 JSON 解析与转义（无第三方依赖）
  - `src/function/Archiver.java` — 跨年自动归档（标记文件 `records/.current_year`，跨年导出 Excel 后清表）
  - `static/` — 静态资源目录（`xlsx.full.min.js` 导出库、`html2canvas`、`gsap`、`ascii-ocean.js` 海洋背景引擎、`soft-nav.js` 软导航、`ai-widget.js` AI 记账挂件）
  - `lib/` — 依赖 jar（MySQL Connector、Apache POI；POI 供归档用）
  - `out/` — 编译输出
  - `desktop/` — Electron 桌面版（`main.js` 起 Java 子进程并开窗口、`build.ps1` 出 NSIS 安装包、`build/icon.ico` 图标）
  - `tools/icon/IconGen.java` — 原创图标生成器（Java2D → 多尺寸 ICO）
  - `tools/page-preview/` — 不连 MySQL 的页面预览截图工具
- 跨年归档机制：
  - 12 张表 `table_01`~`table_12` 按月循环、**无年份字段**；`records/.current_year` 记住当前数据属于哪年
  - 触发：`Archiver.ensureRollover()`（幂等），挂在 `The_main.main()`、控制台 `saveConsumption()`、Web `handleSavePost`/`handleIncomePost`
  - 跨年时先导出 `records/<旧年>-<MM>.xlsx`（消费）与 `records/<旧年>-income.xlsx`（收入），全部成功才清表，最后更新标记；失败保留数据下次重试
  - `records/` 已 gitignore（按本机年份状态生成）
- 产出流程：
  1. IDEA 打开项目
  2. 将 `lib/` 下所有 jar 添加为项目库
  3. 改 `src/function/One.java` 的数据库连接信息（URL、USER、PWD）
  4. 运行 `The_main.java` 的 `main()`
  5. 浏览器访问 http://localhost:8080
- 桌面版构建：`.\desktop\build.ps1`（`-Version 1.0.1` 改版本号，`-Publish` 发布到 GitHub Releases，需 `GH_TOKEN`）→ `desktop\dist\BookkeepingSystem-<版本>-setup.exe`（内置精简 JRE，装完双击即用）。jlink 模块必须含 `java.net.http`（AI 调用依赖），漏了会在打包版里报 `NoClassDefFoundError: java/net/http/HttpClient`
- 自动更新：应用启动 5s 后查 `api.github.com/.../releases/latest`，有新版弹窗确认 → 下载（校验 sha512）→ 退出后静默安装并重启。日志 `%APPDATA%\BookkeepingSystem\desktop.log`。不走 electron-updater（github.com 直连超时），改用 API 资产地址 + `release-assets.githubusercontent.com`
- 常见产物位置：编译输出 `out/`，桌面安装包 `desktop/dist/`，归档导出 `records/`，图表 `The_Pic/`

## 代办事项

### 项目功能
- [x] 接入本地 Ollama AI（技术验证版，2026-11-11）— 右下角挂件：单击输入自然语言 → 预览（商品/金额/日常或特殊/日期）→ 确认入库 → 自动给本月建议；双击直接要建议；空闲 60 秒冒泡提醒。模型默认 `qwen2.5:7b`，`LOH_AI_MODEL` / `LOH_AI_URL` 可覆盖
- [ ] AI 记账后续调整 — 分类粒度（现仅日常/特殊）、提醒频率与文案、撤销、更多入口（待使用后定）

### 通用工具想法
<!-- - [ ] 工具名 — 想解决什么（提出于 yyyy-mm-dd） -->