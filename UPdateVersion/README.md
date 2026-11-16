# UPdateVersion — 本机更新脚本

给「已经安装过的桌面版」打本地补丁用：**改完 Java / 前端代码，双击一个 bat 就能把新版同步进已安装的软件**，
不用重新跑 `desktop\build.ps1` 打 145 MB 的安装包，也不用走控制面板卸载重装。

## 怎么用

双击 **`update-2026-11-16.bat`**（日期就是这份入口创建的日子）。

脚本会依次做：

1. 编译 `src\` 下所有 `.java`（`--release 17`，classpath 用项目 `lib\*`）
2. 暂存 `static\` 和 `lib\*.jar`
3. 从注册表卸载项里找安装目录（当前是 `E:\Bookkeeping System\The-main`），找不到就报错，不会瞎猜
4. 软件开着的话先正常关闭（8 秒没关掉才强杀，连它拉起的 java 一起）
5. 把旧的 `classes / static / lib` 备份到 `backup\<日期-时间>\`（约 22 MB，只留最近 5 份）
6. 镜像覆盖安装目录的 `resources\runtime\{classes,static,lib}`，校验 `WebServer.class` 在
7. 重新启动软件

控制台会打印每一步；同内容也写进 `logs\update-<日期-时间>.log`。

## 出问题怎么退回去

双击 **`rollback.bat`** —— 关掉软件、把最近一次备份原样还原、再启动。

手工回滚也行：把 `backup\<某个时间>\{classes,static,lib}` 拷回
`E:\Bookkeeping System\The-main\resources\runtime\` 对应目录。

## 管不到什么

| 改动内容 | 这个脚本能更新吗 |
|---|---|
| `src\` 里的 Java 代码 | ✅ 能 |
| `static\` 里前端资源（app.css / shell.js / overview.js / splash.html） | ✅ 能 |
| `lib\` 里换 jar | ✅ 能 |
| `desktop\` 里 Electron 外壳（`main.js`、`preload`、图标、窗口行为） | ❌ 不能——那部分打进 `app.asar`，要重跑 `desktop\build.ps1` 再装一次 |
| 内置 JRE | ❌ 不能（很少变） |

## 文件说明

```
UPdateVersion\
  update-2026-11-16.bat   双击入口（日期标记，逻辑全在同目录 update.ps1）
  rollback.bat            回滚到最近一次备份
  update.ps1              真正的脚本（编译 → 关闭 → 备份 → 覆盖 → 启动）
  README.md               本文件
  backup\                 每次更新前的旧版本快照（gitignore）
  logs\                   每次运行的日志（gitignore）
```

要再开一个日期的入口，复制 `update-2026-11-16.bat` 改名即可，逻辑不用动。

## 需要的环境

- 本机有 **JDK 17+**（`javac` 在 PATH 里，或设置了 `JAVA_HOME`）
- 已经装过一次桌面版（脚本靠注册表卸载项定位；也可以 `update.ps1 -InstallDir "D:\xxx"` 手动指定）
- 安装目录可写；不可写时脚本会自动请求管理员权限重跑

命令行用法：

```powershell
.\UPdateVersion\update.ps1            # 正常更新
.\UPdateVersion\update.ps1 -NoRestart # 更新完不启动
.\UPdateVersion\update.ps1 -Rollback  # 回滚
```
