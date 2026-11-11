# cad2ui

把 CAD（DWG/DXF）画的界面草图转成 AI 能精确读取的布局：
`layout.json`（给 AI）、`preview.html`（给人和 AI 看）、`preview.png`（给人对照）、`report.md`（识别摘要）。

## 安装

```powershell
& "E:\python\python_address\python.exe" -m pip install -r tools/cad2ui/requirements.txt
```

DWG 需要 ODA File Converter（免费）；没有就在 CAD 里另存为 DXF。

## 使用

```powershell
& "E:\python\python_address\python.exe" tools/cad2ui/cad2ui.py 图纸.dxf -o out/login
& "E:\python\python_address\python.exe" tools/cad2ui/cad2ui.py 图纸.dwg --layers "UI-*" --width 1440
```

参数见 `cad2ui.py --help`。退出码：0 成功 / 2 输入不支持 / 3 DXF 损坏 / 4 没有几何。

## 输出

- `layout.json`：`tree` 嵌套 + `nodes` 扁平索引（含 `parentId`）+ 每个容器的
  `computed`（direction/gap/padding/align/justify）。
- `preview.html`：绝对定位预览，每个节点带 `data-cad-node` 便于和 JSON 对账。
- `preview.png`：只给人快速对照，不作为 AI 输入。
- `report.md`：节点清单与 warnings。

## 画图规范

见 `CONVENTION.md`。**按规范画，识别就是确定的；不按，工具只能猜。**

## 测试

```powershell
& "E:\python\python_address\python.exe" -m pytest tools/cad2ui/tests -q
```
