# CAD 转 UI 布局（cad2ui）— 设计文档

日期：2026-11-11
状态：设计已确认，待实现

## 1. 背景与问题

本项目正在做大版本前端改版。我习惯在 CAD（AutoCAD / 中望 / 浩辰）里画界面草图——
CAD 能精确对齐、标注尺寸、复用图块，比拖拽原型工具更适合表达"这里要挪多少、这块多宽"。
但 CAD 文件对 AI 是不可读的二进制（DWG），截图又只能表达"大概长这样"。

需要一个一次性工具，把 DWG/DXF 里的界面草图翻译成 AI 能精确读取的结构化布局，
配合截图一起喂给 AI，让改版需求表达无歧义。

## 2. 目标与成功标准

- 输入一张 DWG（或 DXF）界面草图，输出四份产物：
  `layout.json`（AI 读）、`preview.html`（人/AI 看）、`preview.png`（视觉对照）、`report.md`（识别摘要）。
- 三份可视化产物由**同一份解析结果**生成，位置/尺寸/文字/颜色互相对得上。
- 识别结果保留置信度，猜不准的地方显式标注，不静默瞎猜。
- 成功标准：拿一张真实草图运行，`preview.png` 与 CAD 里的布局肉眼一致；
  AI 只读 `layout.json` 就能说出"页面上有几个卡片、每个卡片里有哪些按钮、各在什么位置"。

## 3. 非目标（YAGNI）

- 不做 GUI，只做命令行。
- 不做批量/目录递归/文件监视。
- 不做 DWG 写回、不做 CAD 编辑。
- 不自动还原 CAD 里的精确字体、线宽、图案填充——只保留语义（颜色、字号、矩形）。
- 不把图块拆成子元素（一个 INSERT 视为一个组件；需要细拆就在图块外单独画）。
- 不生成 React/Vue 组件，只生成绝对定位的 HTML + 布局树。

## 4. 使用方式

```
python tools/cad2ui/cad2ui.py 图纸.dwg --width 1440 -o out/login
python tools/cad2ui/cad2ui.py 图纸.dxf --map tools/cad2ui/maps/bookkeeping.json
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `input` | 必填 | `.dwg` 或 `.dxf` |
| `-o, --out` | `out/<文件名>` | 输出目录 |
| `--width` | `1440` | 画布宽度（px），图纸范围等比映射 |
| `--map` | 无 | 外部映射表 JSON（块名/图层名 → 组件类型） |
| `--title` | 文件名 | 写入产物的页面标题 |
| `--no-png` | 关 | 跳过 PNG 渲染 |
| `--keep-dxf` | 关 | 保留 DWG 转换出的中间 DXF |
| `--quiet` | 关 | 只输出错误 |

退出码：`0` 成功（可有 warnings）/ `2` 不支持或无法转换的输入 / `3` DXF 解析失败 / `4` 无几何实体。

## 5. 架构与目录

```
tools/cad2ui/
  cad2ui.py                # CLI 入口：参数、流程编排、退出码
  core/
    __init__.py
    dxf_io.py              # 读 DXF；DWG 转换探测与调用
    model.py               # dataclass：RawElement / Node / Canvas / Warning
    extract.py             # DXF 实体 → RawElement（含坐标变换）
    layout.py              # 文字关联、包含关系、嵌套树
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
    test_infer.py
    test_cli.py
    fixtures.py            # 用 ezdxf 现场生成测试 DXF
```

各模块单职责、可单独测试：`extract` 只认几何，`layout` 只认包含关系，
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
- 其余实体计入统计但不产出节点。

### 6.3 坐标映射

DXF 的 Y 轴向上，CSS 的 Y 轴向下：

```
bbox   = 全部几何实体的包围盒
scale  = --width / bbox.width
x_css  = (x - bbox.left) * scale
y_css  = (bbox.top - y) * scale
w/h    = 原尺寸 * scale
字形高 → fontSize = text.height * scale
```

不做单位换算（不关心图纸是 mm 还是 m），只做相对映射。

### 6.4 颜色

DXF 颜色索引（ACI）转十六进制，取常用前 9 个标准色 + 256 色近似表；
颜色为 `BYLAYER`(256) 时取图层颜色，`BYBLOCK`(0) 时取块定义颜色。
最终映射到 `style.fill / stroke / color`，不参与类型推断（只做提示）。

## 7. 识别规则

### 7.1 实体 → 元素

| 实体 | 元素 | 说明 |
|---|---|---|
| `INSERT` | block | 包围盒取图块引用范围；块内 `ATTRIB` 值作为文字 |
| 闭合 `LWPOLYLINE`/`POLYLINE`（4~5 顶点、近矩形） | box | 允许 1px 容差；非矩形闭合多段线仍按包围盒处理并记 warning |
| `LINE` | line | 记录方向（横/竖/斜）与长度 |
| `CIRCLE` / `ELLIPSE` | circle | 直径 < 48px 时类型倾向 icon |
| `TEXT` / `MTEXT` / `ATTRIB` | text | MTEXT 去格式码（`\P`→换行、`{\f...;}` 等） |
| `SOLID` / `HATCH` | fill | 取包围盒，作为背景层 |

未闭合多段线：不当作 box，按 `line` 处理并记 `UNCLOSED_POLYLINE`。

### 7.2 文字关联

1. 文字插入点落在某个 box 包围盒内 → 该 box 的 `label`（多个文字时，第一个作 label，其余成为子 text 节点）。
2. 未落在任何 box 内 → 独立 `text` 节点。
3. 与 box 部分重叠但不完全包含 → 独立 text 节点 + `TEXT_OUTSIDE_BOX` warning。

### 7.3 嵌套树

1. 所有 box 按面积降序排列。
2. 每个元素归入**包含它的最小 box**；无则挂根。
3. 同层按 y 再 x 排序，保证输出稳定、可 diff。
4. 包含判定容差 1px（缩放后）。

### 7.4 类型推断（按优先级）

1. 外部 `--map` 的精确匹配（块名 > 图层名）。
2. 内置关键词表（`style.py`）：
   `btn/button/按钮/确认/取消`→button，`input/field/输入/搜索`→input，
   `card/卡片`→card，`nav/菜单/导航`→nav，`header/标题栏`→header，
   `footer`→footer，`chart/图表/折线`→chart，`table/表格`→table，
   `icon/图标`→icon，`logo`→logo，`modal/dialog/弹窗`→modal，
   `list/列表`→list，`tab`→tab，`avatar/头像`→avatar，
   `badge`→badge，`switch`→switch，`checkbox`→checkbox，
   `select/dropdown/下拉`→select，`image/img/图片`→image。
3. 尺寸/位置启发式（记 `hints`）：
   - 宽 ≥ 90% 画布且贴顶 → header；贴底 → footer；
   - 高 < 40px 且 40 ≤ 宽 ≤ 200 且有文字 → button；
   - 宽 ≥ 300px 且高 ≥ 120px → card；
   - 宽高都 < 48px → icon。
4. 都不中 → `unknown`。

置信度：map 0.95 / 关键词 0.85 / 图层 0.8 / 尺寸启发 0.5 / unknown 0.2。

## 8. 输出契约

### 8.1 `layout.json`

```json
{
  "schema": "cad2ui/layout@1",
  "title": "登录页改版",
  "source": { "file": "login.dwg", "via": "dwg->dxf", "entityCount": 132 },
  "canvas": { "width": 1440, "height": 900, "scale": 0.5 },
  "tree": [ { "id": "n1", "type": "card", "...": "见下", "children": [] } ],
  "outline": ["card[登录卡片] > button[确认]", "..."],
  "warnings": [ { "code": "TEXT_OUTSIDE_BOX", "handle": "2F4", "message": "文字未落在任何框内" } ],
  "stats": { "blocks": 12, "boxes": 18, "texts": 40, "lines": 9 }
}
```

节点字段：

```json
{
  "id": "n7",
  "type": "button",
  "label": "确认",
  "block": "btn_primary",
  "layer": "UI-BOX",
  "rect": { "x": 120, "y": 80, "w": 240, "h": 64 },
  "style": { "fill": "#ffffff", "stroke": "#d0d0d0", "color": "#111111",
             "fontSize": 16, "radius": 0, "rotation": 0 },
  "confidence": 0.95,
  "hints": ["block:btn_primary", "size:wide-short"],
  "children": []
}
```

`rect` 单位 px，相对画布左上角。`outline` 是给 AI 的扁平脉络，省得它自己遍历。

`source.via` 取值：`dxf`（直接读）或 `dwg->dxf`（经转换器）。
节点 `id` 在嵌套树建好后按**先序**统一编号（`n1`、`n2`…），保证同一输入输出稳定。

### 8.2 `preview.html`

- 绝对定位 `div`，`left/top/width/height` 全部来自树。
- 每个节点带 `data-cad-node="n7"`、`data-type="button"`，便于 AI 与 JSON 对账。
- 画布上方一条元信息（来源文件、画布尺寸、warnings 数），不参与布局。
- 无外部依赖，双击即开。

### 8.3 `preview.png`

- Pillow 绘制，尺寸 = 画布尺寸。
- 字体：`C:/Windows/Fonts/msyh.ttc` → `msyhbd.ttc` → `simhei.ttf` 逐级回退（中文字体必须命中）。
- 与 HTML 同源同序，不额外加装饰。

### 8.4 `report.md`

- 识别摘要（实体数、各类节点数、warnings 列表）。
- 元素清单表格：id / 类型 / 文字 / 位置 / 尺寸 / 置信度。
- 结尾指向 `CONVENTION.md` 未被遵守的条目。

## 9. 画图规范（`CONVENTION.md`）

- **图层**：`UI-FRAME`（页面外框）、`UI-BOX`（控件框）、`UI-TEXT`（文字）、`UI-LINE`（分隔线）、`UI-FILL`（填充区）。
- **图块命名**：英文小写 + 下划线，直接当组件名，如 `btn_primary`、`card_task`、`input_search`。
- **固定色表**：主色 `#409EFF`、背景 `#FFFFFF`、次级背景 `#F5F7FA`、边框 `#DCDFE6`、正文 `#303133`、次要文字 `#909399`。
- **尺寸**：1 图纸单位 = 1px；画布宽度按 1440 画，导出时按 `--width` 等比缩放。
- **要求**：控件必须闭合；文字放在框内；不要用块内块表达交互层级。
- **禁止**：用尺寸标注/引线当界面元素（会被当成 line/text 噪声）。

## 10. 错误处理

不中断，全部记入 `warnings` 并继续：

| code | 场景 |
|---|---|
| `NO_GEOMETRY` | 没有任何可用实体（退出码 4） |
| `UNCLOSED_POLYLINE` | 多段线未闭合，按线处理 |
| `TEXT_OUTSIDE_BOX` | 文字与框部分重叠或完全在框外 |
| `OVERLAP` | 两个 box 互相部分重叠（非包含） |
| `UNKNOWN_BLOCK` | 块名没有任何映射与关键词命中 |
| `EMPTY_LABEL` | 推断为 button/input 但框内无文字 |
| `DEGENERATE_RECT` | 缩放后宽或高 < 1px，丢弃 |

DWG 转换失败 → 退出码 2，不产出半成品。

## 11. 依赖

```
ezdxf>=1.3
Pillow>=10
```

测试：`pytest`（仅开发期）。装在项目 Python 3.12（`E:\python\python_address\python.exe`），
不碰全局其它解释器。

## 12. 测试策略

- **fixtures**：用 ezdxf 程序化生成 DXF——矩形+居中文字、INSERT 图块、嵌套卡片、
  未闭合多段线、MTEXT 格式码、旋转文字、中文块名。
- **单元**：`extract` 的坐标变换与实体映射；`layout` 的包含/排序/文字归位；
  `infer` 的优先级与置信度。
- **CLI 端到端**：退出码、四份产物存在、`layout.json` 字段齐全、
  HTML 节点数与树节点数一致、PNG 尺寸等于画布。
- **稳定性**：同一输入跑两次，`layout.json` 除 `source.file` 外逐字节一致。
- **验收**：真实草图跑一遍，肉眼比对 `preview.png`；读 `layout.json` 复述布局是否准确。
