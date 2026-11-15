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
  - `tools/icon/IconGen.java` — 原创图标生成器（Java2D → 多尺寸 ICO；默认 f 黑白叠页，`build.ps1` 每次构建自动重生成）
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
- [ ] **把新 UI 替换进项目（进行中，2026-11-15 立项）** — 计划 `docs/superpowers/plans/2026-11-15-ui-migration.md`，分支 `feat/ui-v3`。已定：①**渐进换壳**；②月表加 `cat`（六细类），`num_1` 不动，老数据按关键词回填；③预算 **1500**。
  - P0 完成（commit `7e48cce`）：`static/app.css`（原型 414 行样式）+ `static/shell.js`（海洋/时钟/四角/进场/AI 小球，接真 `/ai/*`）+ `WebServer.shell()` 统一套壳，`/main` 试点为 4 张入口卡；旧页零改动、两套样式表不同页共存。自检：7 导航 / 总览高亮 / 设置置灰 / 4 卡 / 无滚动 / 小球面板开合 / 导航跳转全绿。
  - P1 半程完成（commit `40431ea`）：`Cat.java` 六类 + 关键词规则；`ensureCatColumn()` 幂等补列（启动时 + 入库前）；`/save` 表单加「消费细类」；AI 入库写 `cat`；`tools/migrate-cat` 回填 **362/539 行**，备份表 `table_XX_bak_20261115`（验证后可 drop）。写入链路实测：POST `/save` 落 `cat=餐饮, num_1=1`。
  - 分类改为 **AI 主导**（2026-11-15 定）：六类扩成 **10 类**（餐饮/交通/居住/娱乐/购物/教育/AI API/医疗/人情/其他）；`/ai/parse` 直接返回 `cat`（预览可改，换类别自动带性质）；新增 `POST /ai/classify` 按月 40 条一批重分（面板头部「重分本月」，46 行 8.6s / 改 15 条）；`Cat.guess()` 只做离线兜底。总览环图口径：**金额前 5 类 + 其他**。
  - Task 5 完成：`Overview.java` 一次连库算完 KPI/每日/分类/最近流水，`/main` 换成真总览（KPI、30 天柱 + 7 日均线、双环前 5 + 其他、最近 6 条），`static/overview.js` 只画不算。
  - **改版方向（用户定）**：不再一页一功能，改成**单页滚动**——左栏是锚点导航（scrollspy），滚轮浏览全部；表单 POST 后 302 回 `/main#section`，旧路由保留为重定向。Task 6 已按此改写。
  - 下一步：Task 6 单页化（先 `#entry`/`#details` 两个最重的），再 Task 7 开屏
- [x] 开屏动画（CAD 分镜）— 原型 `design_tmp/prototype/splash.html` 已定稿（v13，`?t=秒` 定格、`?debug=1`、`?labels=1`）；将来落到 `static/`（网页开屏）或 `desktop/`（Electron 启动画面）时直接搬
- [x] 操作界面设计 — `design_tmp/prototype/app.html`（开屏之后的主界面）v3：鲨鱼背景接 `static/ascii-ocean.js`（`?nbg=1` 关）、`?nofx=1` 关进场动效（截图必用）、`?t=秒` 定格进场（出分镜图）。四张 KPI（收入/支出/结余/预算进度）、30 天支出柱状 + 悬停读数、分类环图（图例联动）、最近流水 + 表尾合计、状态栏。主色橙 `#ff8a1e`（用户已定），背景维持现状（面板 87% 不透明）。1440×900 一屏装下。v3 三件事：①顶部 AI 小球（点开输入框 → 假解析 → 预览 → 入库 → 本月建议；挂机 30s 主动冒泡、之后 2min 一句），气泡/面板同一套直角发丝线语言；②进场拆两拍——顶栏/左栏 1px 橙光扫过 + 导航错峰亮起（0~.6s），再页头/KPI/图表/表格依次上浮（.6s 起，总长 1.5s）；③环图改成**同心双环**：内环日常/特殊、外环 6 细类（餐饮/其他/购物/教育/娱乐/AI API），按性质成组排序，悬停互相联动、中心读数跟着换。自检 `prototype/_check_ai.py`。**下一步待定**：记一笔 / 明细 / 预算页，或总览接真实后端数据
- [x] 接入本地 Ollama AI（技术验证版，2026-11-11）— 右下角挂件：单击输入自然语言 → 预览（商品/金额/日常或特殊/日期）→ 确认入库 → 自动给本月建议；双击直接要建议；空闲 60 秒冒泡提醒。模型默认 `qwen2.5:7b`，`LOH_AI_MODEL` / `LOH_AI_URL` 可覆盖
- [x] 分类默认规则（2026-11-11）— 餐饮类（早餐/午餐/晚餐/夜宵/饮料/零食/水果/外卖）归日常，其余一律默认特殊
- [ ] AI 记账后续调整 — 提醒频率与文案、撤销、更多入口（待使用后定）

### 通用工具想法
<!-- - [ ] 工具名 — 想解决什么（提出于 yyyy-mm-dd） -->