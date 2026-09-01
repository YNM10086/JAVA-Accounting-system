# The_Light_Of_Humanity — 会话记忆

## 项目总结
个人消费记录系统（JAVA-Accounting-system）：Java 17 + JDK HttpServer 后端，HTML/CSS/JS + Canvas 前端（内联于 WebServer.java），MySQL 数据库（Cost_system，每月一张 table_XX 表）。功能：消费存入、查询统计、折线图、预算管控、Excel 导出、深浅主题、时段问候封面。

## 工作流
- 技术栈/入口：Java 17，运行 `src/System/The_main.java` 的 main() 方法，启动后浏览器自动打开 http://localhost:8080
- 结构地图：
  - `src/System/The_main.java` — 程序入口
  - `src/function/WebServer.java` — Web 服务器 + 所有页面逻辑（前端代码内联）
  - `src/function/One.java` — 数据库连接工具（需配置 MySQL 连接信息）
  - `src/function/Date_time.java` — 日期时间工具
  - `src/function/Line.java` — 折线图生成（JDK Graphics2D）
  - `static/` — 静态资源目录
  - `lib/` — 依赖 jar 包（MySQL Connector 等）
  - `out/` — 编译输出
- 产出流程：
  1. IDEA 打开项目
  2. 将 lib/ 目录下所有 jar 添加为项目库
  3. 修改 src/function/One.java 中的数据库连接信息（URL、USER、PWD）
  4. 运行 The_main.java 的 main() 方法
  5. 浏览器访问 http://localhost:8080
- 常见产物位置：编译输出在 out/ 目录，截图在 screenshots/ 目录
