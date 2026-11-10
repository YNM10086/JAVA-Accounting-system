---
name: page-preview
description: 改了这个项目的界面后，不连 MySQL、不启服务器，把页面渲染成 HTML 并用 headless 浏览器截图，用来核对排版/配色/背景。触发词：看界面、预览页面、截图检查、UI 改了要确认、没数据库怎么看效果。
---

# page-preview

## 什么时候用

- 改了 `WebServer.css()` / `pageRaw()` / 任一页面的 HTML 拼接，想确认实际渲染效果
- 本机没起 MySQL，`/query`、`/budget` 这类页面打不开
- 需要给改动留一张「改前/改后」对照图

## 怎么用

```powershell
.\tools\page-preview\preview.ps1 -Page main -Open
.\tools\page-preview\preview.ps1 -Page query
.\tools\page-preview\preview.ps1 -Body .\body.html -Out .\shot.png
```

输出 PNG 路径会打印在最后一行。

## 判据

- 图里背景海洋可见、卡片文字清晰、没有元素重叠 → 通过
- 拍到卡片半透明/背景全黑 → 检查 `PagePreview` 里的「关动画 + fadeIn:false」是否被改掉

## 不要用它做

- 验证交互（点击、表单提交、数据库读写）——这些必须起真服务器
- 替代真机浏览器测试（字体回退、滚动行为可能不同）
