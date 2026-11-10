# page-preview — 不连数据库的页面预览

把 `WebServer` 的页面模板渲染成静态 HTML，再用 headless 浏览器截图。
改 UI 时不用起服务器、不用开 MySQL，几秒就能看到结果。

## 用法

```powershell
# 真实主菜单（不访问数据库）
.\tools\page-preview\preview.ps1 -Page main

# 查询页 / 表单页样式样例（内置假数据）
.\tools\page-preview\preview.ps1 -Page query -Open
.\tools\page-preview\preview.ps1 -Page form -Width 900 -Height 1200

# 用自己的 body 片段
.\tools\page-preview\preview.ps1 -Body .\my-body.html -Out .\shot.png
```

参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `-Page` | `main` | `main` / `query` / `form` |
| `-Body` | 空 | 自定义 body 片段文件，优先于 `-Page` |
| `-Out` | `.build_tmp\page-preview\shot-<Page>.png` | 截图输出路径 |
| `-Width` / `-Height` | `1280` / `1000` | 视口尺寸 |
| `-Open` | 关 | 截完直接打开图片 |

## 它做了什么

1. `javac` 编译 `src/` 到 `.build_tmp/page-preview/classes`
2. 编译 `PagePreview.java`，用反射调 `WebServer.pageRaw(...)` / `mainPage()`
3. 输出 HTML 时做三处处理，保证截图是「稳定帧」：
   - `/static/ascii-ocean.js` 换成 `file://` 绝对路径（不经过服务器）
   - 关闭 CSS 动画与过渡（否则会拍到卡片飞入一半的瞬间）
   - 海洋引擎 `fadeIn:false`（否则拍到背景还没浮现）
4. 调 Edge/Chrome 的 `--headless=new --screenshot` 出图

## 限制

- 只有 `main` 是真实页面；`query` / `form` 是假数据样例，用来检查排版和配色
- 需要 JDK 17+，以及本机装了 Edge 或 Chrome
- 交互（点按钮、提交表单）测不了，只验证静态外观

## 依赖

无第三方依赖，只用 JDK + 系统浏览器。
