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
  - `UPdateVersion/` — 本机快速更新脚本（双击编译当前源码 → 覆盖已装 `resources/runtime`，带备份与回滚）
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
  - 总览图表加**双模式**（2026-11-15）：`本月每日`（1 日～今天，7 日均线，悬停读数）/ `全年每月`（1–12 月总额，高亮本月，无均线）；标题、图例、右上读数随模式切换。
  - **单页化完成（2026-11-15）**：`/main` 一页装 6 段（overview/entry/income/details/budget/settings），左栏改锚点 + `IntersectionObserver` 高亮，点导航平滑定位；表单 POST 后 `302 /main?ok=1#段`，顶部弹出提示；旧路由 `/save` `/income` `/query` `/budget` `/settings` 全部重定向到对应锚点。删除走 `/query` 的 action=delete（按 date+goods）。实测：提交→回锚点→提示→明细可见→删除→消失，全通；`view.scrollHeight 2779 / 视口 804`。
  - **开屏接真（2026-11-15）**：`static/splash.html` 接管 `/`（缺失退回旧内联），SKIP 提示 + 12.9s 自动进；换标题/版本号。快捷键：F2 定位记一笔、K 唤 AI、Esc 关面板/回顶部。
  - **旧代码清理（2026-11-15）**：删掉 `css()`/`page()`/`pageRaw()`/`savePage`/`incomePage`/`queryPage`/`budgetPage`/`specialPage`/`detailPage`/`chartPage`/`card()`/`serveChart` 与 `/chart-img` 路由，`WebServer.java` 1518 → 844 行；`errorPage` 改用 `shell()`。
  - **预算可按月改（2026-11-15）**：新增 `budgets(month, amount)` 表（幂等建表），`Overview.budget` 按月读、默认 1500；预算段自带表单（POST `/budget`，`ON DUPLICATE KEY UPDATE`），改完 KPI/进度条/每日可用全跟着变。
  - 下一步：分类清单可配置、明细按月翻页、桌面打包回归
- [x] **本机快速更新脚本（2026-11-16）** — `UPdateVersion\update-2026-11-16.bat` 双击即续：编译 `src`（`--release 17`）→ 暂存 `static`/`lib` → 从注册表卸载项定位安装目录 → 关掉正在跑的软件 → 备份旧 `classes/static/lib` 到 `backup\<时间>`（留 5 份）→ robocopy `/MIR` 覆盖 → 重启。`rollback.bat` 一键还原最近备份。实测：关 5 个进程 → 备份 22.1MB → 覆盖 → 重启后 `/main` 200、品牌/壳/AI 球齐全；回滚后 class 时间戳回到 11/15 14:41。只管 Java + 静态资源，改 `desktop\`（app.asar）仍要跑 `build.ps1` 重装。
- [x] **第一期补丁（2026-11-16）** — ①挂机冒泡 5 句 → 20 句，随机且不连重；②`.btn` 加 `text-decoration:none`，导出按钮不再带下划线；③导出改成页面内 `fetch → blob → a[download]`，按钮显示「正在生成…」+ 顶部提示，不再发生跳转；`ascii-ocean` 加单帧异常自愈（`tick` 包 try/catch 且 `finally` 续帧）与回到前台自动续跑，解决点导出后背景停住；④设置页新增「AI 模型」下拉，列出 Ollama 已装模型，存 `app_settings(ai_model)`，默认 `qwen3.5:9b`，qwen3 系列自动 `think:false`，JSON 不合法自动重试一次；⑤建议输入加「最近 8 笔（含刚记的）」，prompt 要求原样引用商品名（不许把「橙子」写成「橘子」）。实测：本月/全年导出都成功且背景照跑（raf 100/700ms）、Electron 内同样；切 qwen3:8b↔qwen3.5:9b 立即生效并落库；建议里出现 deepseek API / 橙子 / 打印费。
- [x] **下拉白底 + 版本号（2026-11-16，v3.0.1）** — 模型下拉「拉出来全白」的根因是原生 `<select>` 弹层由系统绘制：只设了 `color:var(--ink)` 浅色字、没给 `option` 底色，等于白底白字。`.app.css` 加全局 `select{color-scheme:dark}` + `select option{background:#0a1220;color:#e6ebf7}` + `option:checked{background:#ff8a1e;color:#000}`（全站下拉一起修）。版本号 v3.0.0 → **v3.0.1 · 补丁 01（2026-11-16）**：`Sections.java` 设置行、`WebServer.java` 左栏 BUILD 徽标、`splash.html` 两处、`desktop/package.json` 1.0.1。实证：更新脚本部署后真机 Electron 里打开下拉截图（`design_tmp/shots/p12_model_dropdown.png`）为深底浅字；Playwright 读 `option` 计算样式 `rgb(10,18,32)`、`color-scheme=dark`。
- [x] **版本号自动递增（2026-11-16）** — `UPdateVersion\bump-version.ps1`：把 `Sections.java` 的 `v3.0.x · 补丁 NN（日期）` 往上推一格，同步改 `WebServer.java` 的 BUILD 徽标、`splash.html` 两处、`desktop/package.json` 的 `1.0.x`。入口 `update-and-bump.bat`（`update.ps1 -Bump`），普通入口不推版本。**防误涨**：`UPdateVersion\.last-bump` 存 bump 后的 `src+static` 指纹，源码没改就跳过（同一天反复更新不会 3.0.2/3.0.3 一路涨）；`-Stamp` 只记录不推，`-DryRun` 只打印，`-Force` 强制。已用临时目录副本验证 3.0.1→3.0.2→(跳过)→3.0.3，并 `-Stamp` 把当前 v3.0.1 记为基线。
- [x] **第三期补丁（2026-11-16，v3.0.2）** — ①AI 记账后页面不刷新（`/ai/save` 只更新面板文字，总览/明细/预算仍是旧数据，要重启软件才看到）：`doSave` 成功后整页 `location.reload()`，刷新前把「已记几笔 + 建议 + 滚动位置」写进 `sessionStorage.bk_after_save`，`aiInit` 末尾读回 → 重开面板、显示「已记 N 笔 · 数据已刷新」、建议原样保留；`doReclass` 有变更时同样刷新（带「已更新 N 条分类」）。选整页而不是局部换 DOM 的原因：`#flow/#ring` 是 `overview.js` 用内嵌 `window.OV` 画的 SVG，换 innerHTML 会变空。②记错账没法改：月表新增自增主键 `id`（`ensureIdColumn`，启动时自愈；原来只按 `date+goods` 删，同天同名会一次删两条），明细行尾加「改 / 删」——改走 `/main?edit=ID&m=MM` 预填记一笔（月份锁死、橙色 `editBar` + 取消），提交走 `UPDATE ... WHERE id`；删走 `POST /del` 按 id 精确删。`backToOk()` 让成功提示能带具体文案（已记一笔/已修改 1 笔/已删除 1 笔）。实测：AI 面板记牛肉面 25 元 → 自动刷新（57→58 行、¥824→¥849）、面板重开且建议还在；同天同名造两条删一条只剩 1 条；改价格后行内容同步、总数不变。截图 `design_tmp/shots/p13_app_details.png` / `p13_app_edit.png`。
- [x] **第四期补丁（2026-11-18，v3.0.3）** — 用户要「记完账静默刷新，别整页重载」，并给收入补「改」。做法：`shell.js` 新增 `applyPage()`/`softRefresh()`/`silentSubmit()`/`openPage()`——把 `/main` 的新 HTML 用 `DOMParser` 解析后逐段替换 `#overview/#entry/#income/#details/#budget/#settings` 的 innerHTML，再调 `window.OVRefresh(ov)` 用 `/api/overview`（新增，返回 `Overview.json()` 含全年每月）重画 SVG 图表；表单（`/save` `/del` `/income` `/budget`，改 `document` 级 submit 拦截 + `URLSearchParams`，**不能用 FormData，服务端只解析 urlencoded**）后台 POST，用返回的那份 /main 直接换 DOM，不导航；「改」「取消修改」等同站链接也走 `openPage()` 软打开；`history.replaceState` 清掉 `?ok=/?edit=`，保留锚点。`overview.js` 把 IIFE 尾部改成 `boot()` + `window.OVRefresh`（`OV` 改 `let`、`state`/`flow` 可重建），否则换完 DOM 图表是空的。收入：`income_records` 加 `_action=update`（按 id+month 更新）、`/main?iedit=ID` 预填、行尾「改/删」。实证：Edge 与真机 Electron 均 `window.__alive` 存活（无重载）、记一笔 57→58 行且 KPI/环图/最近流水同步、删回 57、收入增改删全静默、预算改 1500→1777→1500（测完已还原）。截图 `design_tmp/shots/p14_after_silent.png`、`p14_income_actions.png`、`p14_app_silent.png`。
- [x] **第五期补丁（2026-11-22，v3.0.4）** — ①**建议口径**：`AiAssistant.MEAL_DAILY = 30`（一天伙食标准，只算「餐饮」），`monthSummary()` 多算一行「伙食合计/日均/标准 30×已过天数 → 在标准内 or 超出 ¥X」，prompt 明确「吃饭是刚需」——**禁止**说少吃/少点外卖/减少餐饮频率/控制吃饭开销，只有日均明显超 30 才提，且只能给同价位更划算的搭配；实测 3 次建议都写成「伙食在标准内，继续保持」这类。②**静默刷新更隐蔽**：真正的「重载感」来自 `.pagehead/.kpis .kpi/.row2 .panel` 的进场动画（rise，延迟 .6s+）——每次换 DOM 都会重播一遍。现在 `body.settled`（首屏 1.6s 后加）把这些动画全部 `animation:none`，`applyPage` 也先加这个类；同时总览不再整段替换：只换新增的 `#ovKpis`（KPI 卡片）与 `#ovRecent`（最近流水），图表 SVG 的 DOM 保留、由 `OVRefresh` 就地重画；设置段不再整段换（避免模型下拉重建/「读取中…」闪一下），只更新 `#settingsBudget` 一行；`#entry` 只在「新增↔修改」切换时替换，平时保留用户打了一半的字（实测：输入框有字时去删明细，字还在）。③顺带把 `Overview` 的流水排序改成 `date DESC, id DESC`，刚记的那笔能出现在最近流水第一行（之前同一天多条按旧顺序）。④收入编辑的 `_action=update` 已在上期，本期只做视觉/口径。截图 `design_tmp/shots/p15_app_silent.png`。
- [x] **开屏声音二改：更响、更金属（2026-11-22，v3.0.7）** — 用户反馈「还是小、缺金属感、只要前两段嗒嗒」。改法：①响度——`master` 后面接 `DynamicsCompressor`（阈值 -16、比 4:1、attack 3ms），音量映射从 ×0.8 提到 ×1.0、默认 60%（用户已把拉条拉到 100%，说明旧版确实不够）；滑音/自检/收尾的 gain 从 0.05~0.08 提到 0.14~0.16。②金属感——棘轮改成 `metalTick()`：12ms 高频带通噪声冲击（Q=12、f×1.55）+ 两条快速衰减正弦余韵（基音 + 1.73 倍泛音，28/45ms），4.9~5.4kHz，tick-tock 交替音高（×1.055 / ×0.95）再叠随机，目标就是机械表芯那种清脆钢声。③范围——`RATCHETS` 只留外环生长 2700–4450ms 与内弧 4450–5700ms（每 82ms 一记），自转两段与最后快速旋转的嗒声全部去掉，那三段只保留滑音/自检音。实测缓冲源 3.5s=10、6.2s=36 后不再增长（9s=38 只多两次自检），振荡器 1→73→75→77→80。部署后真机 `AudioContext.state=running`、版本 v3.0.7。
- [x] **开屏声音可调（2026-11-22，v3.0.6）** — 设置段新增「开屏声音」行：`#splashSnd` 复选框（开机时播放，默认开）+ `#splashVol` 拉条（0–100，默认 40，松手 `previewBeep()` 试听一声），两者都写 `localStorage`（`bk_splash_sound` / `bk_splash_volume`），开屏页读同一份 → `master.gain = 音量% × 0.8`；副标从「SETTINGS / 只读」改成「/ 偏好」。开屏音效按用户要求重排：**开头的通电闷响与起笔滴答撤掉，换成原来「倒数第二个」的低频滑音**（170→300Hz，与末尾旋转滑音同款）；圆弧/转盘移动期间加**机械棘轮声**——`RATCHETS` 按动画时间连续触发带通噪声（Q=9、2.5–3.7kHz），外环 2700–4450ms、内弧 4450–5700ms 每 58ms 一记，两段自转 7250–8060/8700–9500ms 每 46ms，最后快速旋转 10250–12140ms 每 38ms 并随进度加快到 ×0.6；`tickAt` 改用预生成的噪声 buffer + 每记随机 ±900Hz，避免节拍器感。实测（Edge，autoplay 放开）：缓冲源计数 3.5s=14、6s=48、9s=75、11s=110、12.4s=151，振荡器 1→3→5→8 对应滑音/自检/收尾全部按点触发；设置页拉条与开关持久化正常，Electron `AudioContext.state = running`。截图 `design_tmp/shots/p16_settings_sound2.png`。
- [x] 开屏声音（2026-11-22，v3.0.5） — 用户选「合成音效·克制版」：`static/splash.html` 内用 WebAudio 现场合成，**零音频文件**。8 个提示点：780ms 通电（96→42Hz 闷响 + 一声轻咔）、外环/内弧起笔各一记带通噪声滴答、三条自检落位（520/620/740Hz 三角波，音高逐条抬）、10250ms 旋转低频滑音、12150ms 收尾两音上行（660→990）+ 低频托底。`play()` 里 `sndInit()/sndResume()`，`loop()` 按动画时间 `soundAt(t)` 触发（`firedCues` 去重，重播自动清空），`?t=` 定格模式不出声。左下角「♪ 声音 开/关」按钮（捕获阶段事件委托，`stopPropagation` 防止点到就跳转，**按钮在脚本之后才存在，不能用 `$()` 直接绑**），记忆在 `localStorage.bk_splash_sound`，键盘 M 也可切。浏览器拦截自动播放时静默跳过、首次点击/按键再 resume；Electron 实测 `AudioContext.state === "running"`（默认允许自动播放）。实测 Edge：3s 时 1 osc/2 buf、7s 2/4、10s 4/6、12s 5/6，cue 全部按点触发；按钮切换不触发「点击任意处进入」，13s 后正常进 `/main`。截图 `design_tmp/shots/p15_app_splash.png`。
- [x] 开屏动画（CAD 分镜）— 原型 `design_tmp/prototype/splash.html` 已定稿（v13，`?t=秒` 定格、`?debug=1`、`?labels=1`）；将来落到 `static/`（网页开屏）或 `desktop/`（Electron 启动画面）时直接搬
- [x] 操作界面设计 — `design_tmp/prototype/app.html`（开屏之后的主界面）v3：鲨鱼背景接 `static/ascii-ocean.js`（`?nbg=1` 关）、`?nofx=1` 关进场动效（截图必用）、`?t=秒` 定格进场（出分镜图）。四张 KPI（收入/支出/结余/预算进度）、30 天支出柱状 + 悬停读数、分类环图（图例联动）、最近流水 + 表尾合计、状态栏。主色橙 `#ff8a1e`（用户已定），背景维持现状（面板 87% 不透明）。1440×900 一屏装下。v3 三件事：①顶部 AI 小球（点开输入框 → 假解析 → 预览 → 入库 → 本月建议；挂机 30s 主动冒泡、之后 2min 一句），气泡/面板同一套直角发丝线语言；②进场拆两拍——顶栏/左栏 1px 橙光扫过 + 导航错峰亮起（0~.6s），再页头/KPI/图表/表格依次上浮（.6s 起，总长 1.5s）；③环图改成**同心双环**：内环日常/特殊、外环 6 细类（餐饮/其他/购物/教育/娱乐/AI API），按性质成组排序，悬停互相联动、中心读数跟着换。自检 `prototype/_check_ai.py`。**下一步待定**：记一笔 / 明细 / 预算页，或总览接真实后端数据
- [x] 接入本地 Ollama AI（技术验证版，2026-11-11）— 右下角挂件：单击输入自然语言 → 预览（商品/金额/日常或特殊/日期）→ 确认入库 → 自动给本月建议；双击直接要建议；空闲 60 秒冒泡提醒。模型默认 `qwen2.5:7b`，`LOH_AI_MODEL` / `LOH_AI_URL` 可覆盖
- [x] 分类默认规则（2026-11-11）— 餐饮类（早餐/午餐/晚餐/夜宵/饮料/零食/水果/外卖）归日常，其余一律默认特殊
- [ ] AI 记账后续调整 — 提醒频率与文案、撤销、更多入口（待使用后定）

### 通用工具想法
<!-- - [ ] 工具名 — 想解决什么（提出于 yyyy-mm-dd） -->