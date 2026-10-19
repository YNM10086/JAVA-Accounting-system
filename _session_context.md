# The_Light_Of_Humanity — 会话记忆

## 项目简述
个人消费记录系统（JAVA-Accounting-system）：Java 17 + JDK HttpServer 后端，控制台菜单与 HTML/CSS/JS + Canvas 前端（前端内联于 WebServer.java），MySQL 存 `Cost_system` 库（每月一张 `table_XX`）。功能：消费存入、查询统计、折线图、预算管控、Excel 导出、深浅主题、时段问候封面、跨年自动归档。

## 工作流
- 技术栈/入口：Java 17（MySQL Connector + Apache POI 在 `lib/`），IDEA 打开后运行 `src/System/The_main.java` 的 `main()`，启动后浏览器自动打开 http://localhost:8080
- 结构地图：
  - `src/System/The_main.java` — 程序入口，串联各模块并挂载 `Archiver.ensureRollover()`
  - `src/function/WebServer.java` — Web 服务器 + 全部页面逻辑（前端代码内联）
  - `src/function/Menu.java` — 控制台主菜单 / 查询子菜单 / 消费类型子菜单调度
  - `src/function/One.java` — 数据库连接工具（需配置 MySQL 连接信息）
  - `src/function/Date_time.java` — 日期时间工具
  - `src/function/Line.java` — 折线图生成（JDK Graphics2D）
  - `src/function/Archiver.java` — 跨年自动归档（标记文件 `records/.current_year`，跨年导出 Excel 后清表）
  - `static/` — 静态资源目录
  - `lib/` — 依赖 jar（MySQL Connector、Apache POI；POI 供归档用）
  - `out/` — 编译输出
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
- 常见产物位置：编译输出 `out/`，截图 `screenshots/`，归档导出 `records/`

## 代办事项

### 项目功能
<!-- - [ ] 功能名 — 背景（提出于 yyyy-mm-dd） -->

### 通用工具想法
<!-- - [ ] 工具名 — 想解决什么（提出于 yyyy-mm-dd） -->