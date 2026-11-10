# JAVA-Accounting-system

个人消费记录系统 — 基于 Java 的 Web 应用，支持日常消费记账、消费趋势分析、预算管控等功能。

## 功能

- **存入消费** — 记录日常消费与特殊消费，支持自定义日期
- **消费查询** — 月度统计、日均消费、每日明细、折线图趋势（日常趋势 + 日常/特殊双线对比）
- **消费管控** — 预算监控、赤字概率评估、预计超额预警
- **Excel 导出** — 一键导出月度消费数据
- **动态背景** — ASCII 海洋（鲨鱼 / 鱼群）铺底，暗色单一主题，卡片玻璃质感
- **智能封面** — 根据时段自动切换问候语（凌晨/早上/上午/中午/下午/晚上/深夜）

## 技术栈

| 组件 | 使用 |
|------|------|
| 后端 | Java 17+, JDK HttpServer |
| 前端 | HTML / CSS / JavaScript + Canvas |
| 数据库 | MySQL |
| 图表 | JDK Graphics2D 自绘折线图 |
| 动画 | Canvas ASCII 海洋（`static/ascii-ocean.js`）+ CSS Animations |

## 快速开始

### 前置条件

- JDK 17+
- MySQL 8.0+

### 依赖 jar（仓库不提供，需自行准备）

跨年归档功能依赖 Apache POI，其中 **`commons-compress` 必须额外添加**，否则程序启动时检查跨年会报
`NoClassDefFoundError: org/apache/commons/compress/archivers/zip/ZipFile`。

把以下 jar 放入 `lib/` 并添加为项目库：

- `commons-compress-1.26.1.jar`
  （其余依赖 `poi-5.2.5.jar`、`poi-ooxml-5.2.5.jar`、`poi-ooxml-lite-5.2.5.jar`、
  `xmlbeans-5.1.1.jar`、`commons-collections4-4.4.jar`、`commons-io-2.15.1.jar`、
  `commons-math3-3.6.1.jar`、`commons-codec-1.16.1.jar`、`log4j-api-2.21.1.jar`、
  `log4j-core-2.21.1.jar`、`mysql-connector-j-9.7.0.jar` 同样需自行准备）

### 数据库配置

1. 创建数据库：

```sql
CREATE DATABASE Cost_system;
```

2. 为每个月创建数据表（格式 `table_01` ~ `table_12`）：

```sql
CREATE TABLE table_01 (
  id INT AUTO_INCREMENT PRIMARY KEY,
  date INT NOT NULL,
  goods VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  num_1 INT DEFAULT 1
);
```

按月份创建 `table_01` 至 `table_12` 共 12 张表。

### 配置数据库连接

修改 [src/function/One.java](src/function/One.java) 中的数据库连接信息：

```java
private static final String URL = "jdbc:mysql://localhost:3306/Cost_system?useSSL=false&serverTimezone=UTC";
private static final String USER = "root";
private static final String PWD = "你的密码";
```

### 运行

1. 用 IDEA 打开项目
2. 将 `lib/` 目录下的所有 jar 添加为项目库
3. 运行 [src/System/The_main.java](src/System/The_main.java) 的 `main()` 方法
4. 浏览器自动打开 `http://localhost:8080`

> 首次访问会展示欢迎封面，点击后进入主菜单。

## 项目结构

```
src/
├── System/
│   └── The_main.java     # 程序入口
└── function/
    ├── One.java           # 数据库连接工具
    ├── Date_time.java     # 日期时间工具
    ├── Line.java          # 折线图生成
    ├── Menu.java          # 终端菜单（保留兼容）
    └── WebServer.java     # Web 服务器 + 所有页面逻辑
static/
├── ascii-ocean.js         # ASCII 海洋背景引擎（移植自 dsh-ascii-ocean，MIT）
└── ...                    # 导出 Excel 等前端依赖
desktop/                   # Electron 桌面版（见 desktop/README.md）
├── main.js                # 主进程：起 Java 服务、开窗口、退出收进程
├── build.ps1              # 一键构建 NSIS 安装包
└── build/icon.ico         # 应用图标（tools/icon/IconGen.java 生成）
tools/
├── icon/                  # 原创图标生成器
└── page-preview/          # 不连数据库的页面预览截图
```

## 桌面版

```powershell
.\desktop\build.ps1     # 产出 desktop\dist\TheLightOfHumanity-1.0.0-setup.exe
```

安装包自带精简 JRE 与全部依赖，装完双击即可使用，不需要另装 Java、也不需要开浏览器。

## 致谢与许可

- 背景引擎 `static/ascii-ocean.js` 移植自 DSH 插件 **dsh-ascii-ocean**（MIT 许可），已剥离其模块加载器与 React 挂载，仅保留 `OceanEngine` / `World` / `AsciiRenderer` 三个渲染类。原插件版权归原作者所有，本仓库仅作本地界面使用。
