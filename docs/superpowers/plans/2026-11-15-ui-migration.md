# 新 UI 替换旧 UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `design_tmp/prototype/splash.html` + `app.html` 那套 CAD/HUD 界面替换 `WebServer.java` 里的旧界面，功能一个不丢，并让原型里的新东西（开屏、进场、双环、AI 小球）在真实数据上跑起来。

**Architecture:** 「壳 / 内容 / 数据」三层拆开。壳（顶栏、左栏、状态栏、背景、进场、AI 小球）= `static/app.css` + `static/shell.js` 一份真源，Java 侧 `shell(title, nav, body)` 统一套壳；各页面方法只返回 body 片段。旧 `css()` 与旧页面方法按页删除，删一页换一页，任何中间态都能起真服务器跑。

**Tech Stack:** Java 17 + JDK HttpServer、MySQL、原生 HTML/CSS/JS（不引框架）、Electron 壳。

**Spec / 设计源:** `design_tmp/prototype/splash.html`、`design_tmp/prototype/app.html`、`design_tmp/NOTES.md`

## Global Constraints

- 主色橙 `#ff8a1e`；背景走现有 `static/ascii-ocean.js`；面板 87% 不透明。直角、无阴影、等宽字距、暗底。
- **1440×900 一屏不滚**，`document.documentElement.scrollHeight === 900` 是硬指标；真实数据（行数/分类数变化）也要成立。
- 数据结构**只加不减**：`table_01~12(date, goods, price, num_1)` 不动 `num_1`（1=日常 / 2=特殊），新增 `cat VARCHAR(16) NOT NULL DEFAULT '其他'`，取值只有：`餐饮 / 交通 / 居住 / 娱乐 / 购物 / 教育 / AI API / 医疗 / 人情 / 其他`（AI 只能从这里选，不许自创）。
- 每月预算 **1500**（后端 `budgetPage` 口径），原型里的 5000 改成 1500；后续若要可配置，走设置页，不在代码里写死。
- 不引入前端框架、不引入打包器；`static/` 下只加普通 `.css/.js`。
- 页面模板仍由 Java 输出字符串，但**只拼 body**；整页骨架一律走 `shell()`。
- 每阶段结束必须能 `The_main.main()` 起真服务器跑通（不是只看截图）。
- Python / 截图脚本固定 `E:\python\python_address\python.exe`，Playwright 用 `channel="msedge"`。

## Review Focus

- **数字对得上**：总览的支出 / 分类 / 最近流水，必须和 `/query` 同月份的统计一致（同一份数据两个页面算两遍就是 bug）。
- **空态不崩**：本月 0 条记录、只有 1 个分类、分类合计为 0、收入为 0（预算按 1500 走）时，环图不能除零、柱图不能全 0 高度、KPI 不能 `NaN%`。
- **跨年归档不被破坏**：`Archiver.ensureRollover()` 挂在写入前，改造后必须仍然触发（导出 `records/<年>-<MM>.xlsx` 的行为不变）。
- **AI 链路不退化**：`/ai/parse` → 预览 → `/ai/save` 的字段（商品/金额/分类/日期）落库后，`cat` 和 `num_1` 两列都要正确。
- **桌面版可打包**：`desktop/build.ps1` 之后启动，壳、背景、开屏都正常（静态资源路径不能写死成 `file://`）。

---

### Task 1: 抽壳（CSS 设计系统）

**Files:**
- Create: `static/app.css`
- Read: `design_tmp/prototype/app.html`

- [x] 把 `app.html` 里 `<style>` 的变量、顶栏、左栏、状态栏、`.panel`、`.kpi`、表格、按钮、AI 小球/面板/气泡整段搬到 `static/app.css`，去掉原型专用的调试类（`.nofx`、定格相关）。
- [x] 只保留一套变量（`--accent --income --expense --ink --dim --faint --line --panel`），不保留原型里写死的一次性数值。
- [x] `:root` 之外不出现魔法色值。

**验收:** 新开一个空白 HTML 引用 `app.css`，手写顶栏 + 左栏 + 一个 `.panel`，1440×900 下与原型像素级接近（叠图 diff 允许 ≤2px 的字体差异）。

---

### Task 2: `shell.js`（骨架 + 进场 + 背景 + AI）

**Files:**
- Create: `static/shell.js`
- Read: `design_tmp/prototype/app.html`（进场时间轴、AI 小球）、`static/ai-widget.js`（真实 `/ai/*` 流程）

- [x] `Shell.mount({nav, title, crumb})`：渲染顶栏（品牌 / 面包屑 / AI 小球 / 时钟 / 窗口按钮占位）、左栏 7 项导航（当前项高亮由 `nav` 决定）、状态栏。
- [x] 进场两拍照搬原型：0~.6s 骨架通电（顶栏/左栏 1px 橙光扫过 + 导航错峰），.6s 起内容 `rise`。
- [x] 挂 `ascii-ocean.js`（默认开，`?nbg=1` 关）。
- [x] AI 小球：造型照搬原型；面板/气泡的**交互与接口**照搬 `ai-widget.js`（`/ai/parse`、`/ai/save`、`/ai/advice`、`/ai/status`），面板位置 `top:56 right:356`、气泡 `top:8 right:302`。
- [x] `prefers-reduced-motion` 与 `?nofx=1` 关闭动效，但骨架保持可见（`::after` 基态 `opacity:0`）。

**验收:** 静态页里调 `Shell.mount()` 能出完整骨架；点小球 → 输入 → 解析 → 入库走真接口成功（需 MySQL）；无 console error。

---

### Task 3: Java 侧套壳，`/main` 试点

**Files:**
- Modify: `src/function/WebServer.java`（`page()` / `pageRaw()` / 新增 `shell(String title, String nav, String body)`、`mainPage()`）

- [x] 新增 `shell()`：输出 `<!DOCTYPE html>` + `<link app.css>` + `<script shell.js>` + `Shell.mount()` + body 容器。
- [x] `/` 与 `/splash-done` 路由保持原语义（先开屏，再 `/main`）。
- [x] `/main` 改成**总览的占位**（先接新壳，内容仍是旧主菜单的 4 个入口，作为“功能还在”的证明）。
- [x] 旧 `page()` 保留但不再被新页面调用；`pageRaw()` 暂时共存。

**验收:** 起真服务器，`/` → 开屏 → `/main` 显示新壳 + 旧四入口，四项都能点进旧页面且功能正常。

---

### Task 4: `cat` 列 + 一次性回填

**Files:**
- Modify: `src/function/WebServer.java`（`ensureSchema()`、`handleSavePost`、`handleAiSave`）
- Create: `tools/migrate-cat/` （一个可重复执行的小工具，Java 或 SQL 脚本二选一）

- [x] `ensureSchema()`：对 `table_01~12` 逐个查 `information_schema.columns`，缺 `cat` 就 `ALTER TABLE ... ADD COLUMN cat VARCHAR(16) NOT NULL DEFAULT '其他'`（幂等，跑两次不变）。
- [x] 关键词规则与原型一致：饭/面/餐/奶茶/咖啡/外卖 → 餐饮；电影/游戏/演出/门票/旅游 → 娱乐；课/网课/书/教程/考试 → 教育；api/claude/gpt/openai/token → AI API；买/京东/淘宝/超市/键盘 → 购物；其余 → 其他。
- [x] 回填老数据：按同一套规则更新 `cat`，并保证 `num_1` 与性质规则一致（餐饮→日常，其余→特殊；已有 `num_1` 不覆盖）。
- [x] `/save` 表单加分类下拉（6 项），提交写入 `cat`；`/ai/save` 把解析出的分类写进 `cat`。

**验收:** 回填后 `SELECT cat, SUM(price) FROM table_11 GROUP BY cat` 合计 = 该月总支出；`/save` 提交一条带分类的记录，`/query` 能看到。

---

### Task 5: 总览接真数据

**Files:**
- Create: `src/function/Overview.java`（聚合查询，纯数据）
- Modify: `src/function/WebServer.java`（`/main` → 总览页）

- [ ] 一份聚合结果同时喂 4 张 KPI、30 天柱 + 7 日均线、双环（内环日常/特殊、外环**金额前 5 类 + 其他**）、最近流水 6 条 + 合计。
- [ ] 分类由 AI 决定（`/ai/classify` 批量重分，`Cat.guess` 兜底），环图不能假定固定 6 类：按金额降序取前 5，其余合成「其他」一段，图例列出全部非零类。
- [ ] 预算读数取 1500（常量集中在 `Overview.BUDGET`，供设置页以后复用）。
- [ ] 分类与性质的一致性：内环由 `num_1` 汇总，外环由 `cat` 汇总，两者合计必须相等（不等就是数据脏，页面要能显示而不是崩）。

**验收:** 同月份下，总览的「支出」「日常/特殊」「6 类合计」三组数字与 `/query` 页一致；0 条记录时显示空态。

---

### Task 6: 改成单页滚动：一块屏装全部功能（2026-11-15 用户定）

**Files:**
- Modify: `src/function/WebServer.java`（`savePage`/`incomePage`/`queryPage`/`budgetPage`、路由）
- Delete: 对应旧方法 + `css()` 里对应段落

全部功能落在 `/main` 一页，左栏 = 锚点导航（scrollspy），鼠标滚轮浏览：

- [ ] `#overview` 总览（已完成 Task 5）：KPI / 30 天柱 / 双环 / 最近流水。
- [ ] `#entry` 记一笔：商品 / 价格 / 类别（10 选 1）/ 日常·特殊 / 月份 / 日期，落库字段与现在一致 + `cat`。
- [ ] `#income` 收入：月份 / 日期 / 金额 / 说明 + 本月收入列表。
- [ ] `#details` 明细：月度统计、日均、按日明细（可删）、特殊消费；长表用「表内滚动 + 每页 20 条」，不吃整页高度。
- [ ] `#budget` 预算：已用 / 剩余 / 每日可用 / 概率评估，预算 1500。
- [ ] `#settings` 设置：预算额、AI 模型状态、数据库状态；先只读。
- [ ] 锚点与跳转：左栏 `href="/main#entry"`，顶栏/左栏 sticky，`scroll-margin-top` 给顶栏留位；
      表单 POST 后 `302 → /main#<section>`（不依赖 JS，F2 等旧入口仍可用）；旧路由 `/save`、`/query`、`/budget`、`/income` 全部 302 到对应锚点。
- [ ] scrollspy：`IntersectionObserver` 高亮当前 section，点击导航平滑滚动（`prefers-reduced-motion` 时直接跳）。
- [ ] 导出不进锚点：`/export` 仍是直接下载，导航项单独处理（不高亮、不占 section）。

**验收:** 每页 Playwright 脚本：进页面 → 提交一条 → 回列表能看到 → 删除生效；截图归档 `design_tmp/shots/`。

---

### Task 7: 开屏接进真实入口

**Files:**
- Create: `static/splash.html`
- Modify: `src/function/WebServer.java`（`/` → `serveStatic /splash.html` 或内联）、`desktop/main.js`（可选启动画面）

- [ ] `splash.html` 从 `design_tmp/prototype/splash.html` 搬，路径改为 `/static/*`，结束跳 `/splash-done`（保持现有 `splashPending` 语义：本次进程只播一次）。
- [ ] 开屏动画结束到 `/main` 的切换不能白屏（新壳在第一帧就要有骨架）。

**验收:** 启动 → 开屏 → 主界面，二次刷新（同进程）不再播开屏；打包版同样成立。

---

### Task 8: 清理与回归

- [ ] 删除 `css()`、`page()`、`pageRaw()` 与所有旧页面方法、旧 `static/*` 中不再引用的文件。
- [ ] `grep -n "hero-card\|query-section\|filter-pill"` 应为 0。
- [ ] 回归清单：`/`、`/main`、`/save`、`/income`、`/query`、`/budget`、`/export`、`/ai/*`、跨年归档、桌面打包。
- [ ] `preview.ps1` 改造为走新壳（或标注仅供旧页对照）。

**验收:** 全量 Playwright 脚本绿、`desktop/build.ps1` 出包能启动、`_session_context.md` 与 `NOTES.md` 更新。

## 风险与回退

| 风险 | 表现 | 对策 |
|---|---|---|
| 一屏不滚被真实数据撑破 | 1440×900 出现滚动条 | 最近流水固定 6 条、分类图例固定行高、超长分类名省略号 |
| `cat` 回填口径不一致 | 总览分类合计 ≠ 总支出 | 回填脚本先备份（`table_XX_bak`），再校验合计 |
| 旧 CSS 与新壳同名类冲突 | 页面串味 | 新壳类名全部带 `sh-` 前缀，旧 `css()` 在过渡期只服务旧页 |
| 开屏与壳加载竞速 | 开屏结束白屏 1 帧 | `Shell.mount()` 同步执行，骨架先出 |
| Electron 路径 | 打包后白屏 | 静态资源一律相对 `/static/`，不写 `file://` |
