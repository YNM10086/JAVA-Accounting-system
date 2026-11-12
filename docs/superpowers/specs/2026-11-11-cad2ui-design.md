# CAD 转 UI 布局（cad2ui）— 设计文档

日期：2026-11-11
状态：v2，已按评审意见修订，待实现
修订：增加容器布局约束推导（`computed`）、扁平节点索引、图层语义契约、
阈值归一化、文字包围盒关联、z-order、切角框顶点保留、图层过滤、警告定位信息。

## 1. 背景与问题

本项目正在做大版本前端改版。我习惯在 CAD（AutoCAD / 中望 / 浩辰）里画界面草图——
CAD 能精确对齐、标注尺寸、复用图块，比拖拽原型工具更适合表达"这里要挪多少、这块多宽"。
但 CAD 文件对 AI 是不可读的二进制（DWG），截图又只能表达"大概长这样"。

需要一个一次性工具，把 DWG/DXF 里的界面草图翻译成 AI 能精确读取的结构化布局，
配合截图一起喂给 AI，让改版需求表达无歧义。

## 2. 目标与成功标准

- 输入一张 DWG（或 DXF）界面草图，输出四份产物：
  `layout.json`（AI 读）、`preview.html`（人/AI 看）、`preview.png`（人眼对照）、`report.md`（识别摘要）。
- 三份可视化产物由**同一份解析结果**生成，位置/尺寸/文字/颜色互相对得上。
- 识别结果保留置信度，猜不准的地方显式标注，不静默瞎猜。
- 成功标准：拿一张真实草图运行，`preview.png` 与 CAD 里的布局肉眼一致；
  AI 只读 `layout.json` 就能说出"页面上有几个卡片、每个卡片里有哪些按钮、各在什么位置"，
  **并能直接给出 flex 方向/间距/内边距/对齐方式，不需要自己反推坐标差**。

## 3. 非目标（YAGNI）

- 不做 GUI，只做命令行。
- 不做批量/目录递归/文件监视。
- 不做 DWG 写回、不做 CAD 编辑。
- 不自动还原 CAD 里的精确字体、线宽、图案填充——只保留语义（颜色、字号、矩形）。
- 不把图块拆成子元素（一个 INSERT 视为一个组件；需要细拆就在图块外单独画）。
- 不生成 React/Vue 组件，只生成绝对定位 HTML + 布局树。
- 不做完整 ACI 256 色近似表（只用 0–9 标准色 + BYLAYER/BYBLOCK）。

## 4. 使用方式

```
python tools/cad2ui/cad2ui.py 图纸.dwg --width 1440 -o out/login
python tools/cad2ui/cad2ui.py 图纸.dxf --layers "UI-*"
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `input` | 必填 | `.dwg` 或 `.dxf` |
| `-o, --out` | `out/<文件名>` | 输出目录 |
| `--width` | `1440` | 画布宽度（px），图纸范围等比映射 |
| `--map` | 无 | 外部映射表 JSON（块名/图层名 → 组件类型） |
| `--layers` | 无 | **只读**这些图层，支持 `UI-*` 通配 |
| `--ignore-layers` | 无 | 跳过这些图层，同样支持通配 |
| `--title` | 文件名 | 写入产物的页面标题 |
| `--no-png` | 关 | 跳过 PNG 渲染 |
| `--keep-dxf` | 关 | 保留 DWG 转换出的中间 DXF |
| `--quiet` | 关 | 只输出错误 |

默认跳过 `DIMENSION` / `LEADER` / `MULTILEADER` / `ACAD_TABLE` 及
`Defpoints` 图层——尺寸标注和引线在界面草图里全是噪声。

退出码：`0` 成功（可有 warnings）/ `2` 不支持或无法转换的输入 / `3` DXF 解析失败 / `4` 无几何实体。

## 5. 架构与目录

```
tools/cad2ui/
  cad2ui.py                # CLI 入口：参数、流程编排、退出码
  core/
    __init__.py
    dxf_io.py              # 读 DXF；DWG 转换探测与调用；图层过滤
    model.py               # dataclass：RawElement / Node / Canvas / Warning
    extract.py             # DXF 实体 → RawElement（含块变换、坐标变换）
    layout.py              # 文字关联、包含关系、嵌套树、computed 约束
    infer.py               # 组件类型推断与置信度
    style.py               # 颜色/图层/块名语义表（内置默认 + 外部 map 合并）
    render_html.py         # 布局树 → preview.html
    render_png.py          # 布局树 → preview.png（Pillow）
    report.py              # → report.md
  CONVENTION.md            # 画图规范（交付物之一）
  requirements.txt
  README.md
  tests/
    test_extract.py
    test_layout.py
    test_computed.py
    test_infer.py
    test_cli.py
    fixtures.py            # 用 ezdxf 现场生成测试 DXF
```

各模块单职责、可单独测试：`extract` 只认几何，`layout` 只认包含关系与几何统计，
`infer` 只认命名/尺寸线索，两个 `render_*` 只认最终树。渲染层不碰 DXF 类型。

## 6. 输入处理

### 6.1 DWG → DXF

按顺序探测，任一可用即用：

1. `ezdxf.addons.odafc`：本机装了 ODA File Converter（免费）时可用。
2. PATH 里的 `dwg2dxf.exe`（LibreDWG）。

都不可用 → 退出码 `2`，提示：
「未找到 DWG 转换器。装 ODA File Converter，或在 CAD 里另存为 DXF 后直接传 .dxf」。
转换结果写到输出目录的 `<stem>.cad2ui.dxf`，默认成功后删除，`--keep-dxf` 保留。

### 6.2 DXF 读取

- 默认只读模型空间（`modelspace`）。
- 读取实体：`INSERT` / `LWPOLYLINE` / `POLYLINE` / `LINE` / `CIRCLE` / `ELLIPSE` /
  `TEXT` / `MTEXT` / `ATTRIB` / `SOLID` / `HATCH` / `POINT`。
- 默认跳过：`DIMENSION` / `LEADER` / `MULTILEADER` / `ACAD_TABLE`、`Defpoints` 图层。
- 其余实体计入统计但不产出节点。

### 6.3 坐标映射

DXF 的 Y 轴向上，CSS 的 Y 轴向下：

```
bbox   = 全部保留实体的包围盒
scale  = --width / bbox.width
x_css  = (x - bbox.left) * scale
y_css  = (bbox.top - y) * scale
w/h    = 原尺寸 * scale
fontSize = text.height * scale
height = bbox.height * scale          # 画布高度，按宽等比、高度自适应
```

不做单位换算（不关心图纸是 mm 还是 m），只做相对映射。
所有输出 `rect` 数值 **round 到 1 位小数**，保证同输入可逐字节复现。

### 6.4 颜色

只处理 ACI `0–9` 标准色 + 256 色近似值不做；`BYLAYER`(256) 取图层色，
`BYBLOCK`(0) 取块定义色。其余索引色统一给 `#888888` 并记 `COLOR_APPROX` warning。
颜色只进 `style`，不参与类型推断。

## 7. 识别规则

### 7.1 实体 → 元素

| 实体 | 元素 | 说明 |
|---|---|---|
| `INSERT` | block | 用 `matrix44()` / `virtual_entities()` 取变换后的包围盒；块内 `ATTRIB` 位置一并变换 |
| 闭合 `LWPOLYLINE`/`POLYLINE`（4 顶点且近矩形） | box | 顶点容差 1px（参考坐标） |
| 闭合多段线但**不是** 4 顶点矩形 | box | 保留原始点列到 `points`，记 `CUT_CORNER_BOX`（切角/异形框前端可用 `clip-path` 还原） |
| 非闭合 `LWPOLYLINE`/`POLYLINE` | line | 记 `UNCLOSED_POLYLINE` |
| `LINE` | line | 记录方向（横/竖/斜）与长度 |
| `CIRCLE` / `ELLIPSE` | circle | 直径 < 48px（参考坐标）时类型倾向 icon |
| `TEXT` / `MTEXT` / `ATTRIB` | text | MTEXT 去格式码（`\P`→换行、`{\f...;}` 等） |
| `SOLID` / `HATCH` | fill | 取包围盒，`z` 最低 |

### 7.2 文字关联

用**文字包围盒**判定，不用插入点（插入点随 `halign/valign` 漂移，会大面积误报）：

1. 文字包围盒完全落在某个 box 内 → 该 box 的候选 label。
2. 与 box 部分重叠 / 完全在外 → 独立 `text` 节点 + `TEXT_OUTSIDE_BOX`。
3. 文字包围盒不可用（极老式实体）→ 退回插入点判定。

一个 box 有多个候选文字时：**字号最大者**作 `label`；同字号取最靠上，再同取最靠左。
其余成为该 box 的子 `text` 节点。

### 7.3 嵌套树与层级

1. 候选父节点**只能是 box**（fill / line / text / circle 都不能当父节点），
   避免按钮被塞进同区域的背景填充块里。
2. 所有 box 按面积降序；每个元素归入**包含它的最小 box**；无则挂根。
3. 同级按 `z` 升序排（背景在下），`z` 取自实体在 DXF 中的出现顺序。
4. 包含判定容差 1px（参考坐标）。
5. 包含关系非全序（互相部分重叠）→ 记 `OVERLAP`，按面积大者当父节点，不做传递。

### 7.4 类型推断（按优先级）

**所有尺寸阈值都在"参考坐标"下比较**：`ref = 1440 / bbox.width`，
即把图纸先归一化到 1440 宽再套阈值，`--width` 只影响输出画布，不影响判定。

1. **图层契约**（最高优先级，写死）：
   `UI-FRAME`→`frame`、`UI-TEXT`→`text`、`UI-LINE`→`line`、`UI-FILL`→`fill`。
   `UI-BOX` 只做标记，不锁类型，继续走第 2–4 步（`UI-BOX` 上的 `btn_primary` 仍是 button）。
2. 外部 `--map` 精确匹配（块名 > 图层名）。
3. 内置关键词表（`style.py`），**只匹配块名和图层名，不匹配 label 文本**
   （否则"确认密码"的输入框会被判成按钮）：
   `btn/button/按钮/确认/取消`→button，`input/field/输入/搜索`→input，
   `card/卡片`→card，`nav/菜单/导航`→nav，`header/标题栏`→header，
   `footer`→footer，`chart/图表/折线`→chart，`table/表格`→table，
   `icon/图标`→icon，`logo`→logo，`modal/dialog/弹窗`→modal，
   `list/列表`→list，`tab`→tab，`avatar/头像`→avatar，
   `badge`→badge，`switch`→switch，`checkbox`→checkbox，
   `select/dropdown/下拉`→select，`image/img/图片`→image。
4. 尺寸/位置启发式（记 `hints`）：
   - 宽 ≥ 1260（参考坐标 ≈ 90% 画布）且贴顶 → header；贴底 → footer；
   - 高 ≤ 56 且 40 ≤ 宽 ≤ 320 且有文字 → button；
   - 宽 ≥ 300 且高 ≥ 120 → card；
   - 宽高都 < 48 → icon。
5. 都不中 → `unknown`。

置信度：图层契约 1.0 / map 0.95 / 关键词 0.85 / 尺寸启发 0.5 / unknown 0.2。

### 7.5 容器布局约束（`computed`）

对每个**有至少 1 个子节点**的容器，由子节点 rect 算出布局意图，写进 `computed`：

```json
"computed": {
  "direction": "column",
  "justify": "start",
  "align": "stretch",
  "gap": 8,
  "padding": [12, 16, 12, 16],
  "childrenSpan": { "x": 120, "y": 80, "w": 240, "h": 200 }
}
```

- `direction`：子节点 ≥ 2 时，若所有子节点 **y 区间两两重叠** → `row`；
  若所有 **x 区间两两重叠** → `column`；否则 `null`（自由布局/网格，不强推）。
  只有 1 个子节点时 `null`。
- `gap`：沿主轴相邻子节点的间距，取**中位数**并取整；`direction` 为 `null` 时 `null`。
- `padding`：`[上, 右, 下, 左]`，由容器 rect 减去子节点并集得到，取整，负值归 0。
- `align`：交叉轴上子节点对齐方式——全贴左→`start`、全贴右→`end`、
  居中偏差最小→`center`、交叉尺寸彼此一致且都 ≥ 容器 80%→`stretch`
  （`padding` 是从子节点反推的，因此不能拿"内容区宽度"当 `stretch` 的判据）。
- `justify`：主轴上首/尾留白都小→`start`（此时间距均匀且 > 0 → `space-between`，
  这是 CSS 里最常见的贴边均分）；都大→`center`；只有尾留白大→`start`；
  只有首留白大→`end`；间距均匀且首尾留白≈间距→`space-between`。
- `childrenSpan`：子节点并集包围盒（给 AI 校验用）。
- `computed` 是**启发式结论**，节点上另带 `computedConfidence`（0–1），
  由判据一致度给出，供 AI 判断要不要信。

## 8. 输出契约

### 8.1 `layout.json`

```json
{
  "schema": "cad2ui/layout@1",
  "title": "登录页改版",
  "source": { "file": "login.dwg", "via": "dwg->dxf", "entityCount": 132 },
  "canvas": { "width": 1440, "height": 900, "scale": 0.5 },
  "tree": [ { "id": "n1", "type": "card", "children": [] } ],
  "nodes": [
    { "id": "n1", "parentId": null, "type": "card", "label": "登录卡片",
      "rect": {"x": 120, "y": 80, "w": 400, "h": 300}, "z": 3, "depth": 0 }
  ],
  "warnings": [ { "code": "TEXT_OUTSIDE_BOX", "handle": "2F4",
                  "entityType": "TEXT", "rect": {"x": 10, "y": 12, "w": 60, "h": 16},
                  "message": "文字未落在任何框内" } ],
  "stats": { "blocks": 12, "boxes": 18, "texts": 40, "lines": 9, "fills": 3 }
}
```

- `tree`：完整嵌套树，用于渲染和层级理解。
- `nodes`：**扁平索引**，每项含 `id / parentId / type / label / rect / depth`，
  给 AI 省遍历成本；label 里有 `>`、换行都不会坏结构。
- `source.via`：`dxf` 或 `dwg->dxf`。
- 节点 `id` 建树后按**先序**统一编号（`n1`、`n2`…），同输入稳定。

节点完整字段：

```json
{
  "id": "n7",
  "type": "button",
  "label": "确认",
  "block": "btn_primary",
  "layer": "UI-BOX",
  "rect": { "x": 120, "y": 80, "w": 240, "h": 48 },
  "style": { "fill": "#ffffff", "stroke": "#d0d0d0", "color": "#111111",
             "fontSize": 16, "radius": 0, "rotation": 0 },
  "points": [[0, 0], [240, 0], [240, 48], [220, 48], [0, 48]],
  "z": 12,
  "confidence": 0.95,
  "computedConfidence": 0.8,
  "computed": { "direction": "row", "justify": "start", "align": "center",
                "gap": 12, "padding": [0, 0, 0, 0],
                "childrenSpan": { "x": 120, "y": 90, "w": 240, "h": 30 } },
  "hints": ["block:btn_primary", "size:wide-short"],
  "children": []
}
```

`rect` 单位 px，相对画布左上角；`points` 仅非矩形闭合多段线才有。
`computed` / `computedConfidence` 仅有子节点的容器才有。

### 8.2 `preview.html`

- 绝对定位 `div`，`left/top/width/height` 全部来自树；`points` 存在时用 `clip-path: polygon(...)`。
- 每个节点带 `data-cad-node="n7"`、`data-type="button"`，便于 AI 与 JSON 对账。
- 画布上方一条元信息（来源文件、画布尺寸、warnings 数），不参与布局。
- 无外部依赖，双击即开。

### 8.3 `preview.png`

- Pillow 绘制，尺寸 = 画布尺寸。
- **定位：给人快速对照用，不作为 AI 输入**（AI 读 JSON + HTML 就够，看图反而回到"猜"）。
- 字体回退：`msyh.ttc` → `msyhbd.ttc` → `simhei.ttf`（Win）；
  `/System/Library/Fonts/PingFang.ttc`（macOS）；`DejaVuSans.ttf`（Linux）。
  全失败就用默认位图字体并记 warning，不为此加依赖。

### 8.4 `report.md`

- 识别摘要（实体数、各类节点数、warnings 列表）。
- 元素清单表格：id / 类型 / 文字 / 位置 / 尺寸 / 置信度 / `computed` 摘要。
- 结尾列出 `CONVENTION.md` 里本次未遵守的条目。

## 9. 画图规范（`CONVENTION.md`）

- **图层**：`UI-FRAME`（页面外框）、`UI-BOX`（控件框）、`UI-TEXT`（文字）、`UI-LINE`（分隔线）、`UI-FILL`（填充区）。
- **图块命名**：英文小写 + 下划线，直接当组件名，如 `btn_primary`、`card_task`、`input_search`。
- **固定色表**：主色 `#409EFF`、背景 `#FFFFFF`、次级背景 `#F5F7FA`、边框 `#DCDFE6`、正文 `#303133`、次要文字 `#909399`。
- **尺寸**：1 图纸单位 = 1px（按 1440 宽画）；导出时按 `--width` 等比缩放，判定阈值不受影响。
- **要求**：控件必须闭合；文字放在框内；不要用块内块表达交互层级。
- **禁止**：用尺寸标注/引线当界面元素（会被跳过）；把辅助线放在界面图层里（用 `--layers` 兜底）。

## 10. 错误处理

不中断，全部记入 `warnings` 并继续。每条 warning 必须带 `code / handle / entityType / rect / message`，
用户能凭 `rect` 在 CAD 里定位实体：

| code | 场景 |
|---|---|
| `NO_GEOMETRY` | 没有任何可用实体（退出码 4） |
| `UNCLOSED_POLYLINE` | 多段线未闭合，按线处理 |
| `TEXT_OUTSIDE_BOX` | 文字包围盒与框部分重叠或完全在框外 |
| `OVERLAP` | 两个 box 互相部分重叠（非包含） |
| `UNKNOWN_BLOCK` | 块名没有任何映射与关键词命中 |
| `EMPTY_LABEL` | 推断为 button/input 但框内无文字 |
| `DEGENERATE_RECT` | 缩放后宽或高 < 1px，丢弃 |
| `CUT_CORNER_BOX` | 非矩形闭合多段线，已保留 `points` |
| `COLOR_APPROX` | 颜色索引超出 0–9，给了近似灰 |

DWG 转换失败 → 退出码 2，不产出半成品。

## 11. 依赖

```
ezdxf>=1.3
Pillow>=10
```

测试：`pytest`（仅开发期）。装在项目 Python 3.12（`E:\python\python_address\python.exe`），
不碰全局其它解释器。

## 12. 测试策略

- **fixtures**：用 ezdxf 程序化生成 DXF——矩形+居中文字、INSERT 图块（含旋转/缩放）、
  嵌套卡片、未闭合多段线、MTEXT 格式码、旋转文字、中文块名、5 顶点切角框、重叠盒子。
- **单元**：`extract` 的坐标变换/块变换/实体映射；`layout` 的包含、排序、文字归位；
  `infer` 的优先级与置信度；`computed` 的方向/间距/内边距/对齐。
- **警告路径**：`UNCLOSED_POLYLINE`、`TEXT_OUTSIDE_BOX`、`OVERLAP`、`CUT_CORNER_BOX`、
  `COLOR_APPROX` 各至少一例，断言 warning 的 code 与 rect 都在。
- **CLI 端到端**：退出码、四份产物存在、`layout.json` 字段齐全、
  `nodes` 数量 = 树节点数、`parentId` 全部可解析、HTML 节点数与树一致、PNG 尺寸 == `canvas`。
- **稳定性**：同一输入跑两次，`layout.json` 逐字节一致
  （数值 round 到 1 位小数，`json.dumps(..., sort_keys=True, ensure_ascii=False, indent=2)`）。
- **验收**：真实草图跑一遍，肉眼比对 `preview.png`；读 `layout.json` 复述布局并给出 flex 约束是否准确。
