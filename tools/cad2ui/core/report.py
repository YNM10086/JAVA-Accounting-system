from __future__ import annotations

from pathlib import Path

from core.model import Layout, Warning


def _cell(text: str) -> str:
    """Markdown 表格单元格：转义竖线并压掉换行，避免把行拆开。"""
    return str(text).replace("|", "\\|").replace("\r\n", " ").replace("\n", " ")


def _computed_cell(node) -> str:
    c = node.computed
    if not c:
        return "-"
    direction = c.get("direction") or "-"
    return f"{direction} gap={c.get('gap')} pad={c.get('padding')}"


def _warning_lines(warnings: list[Warning]) -> list[str]:
    if not warnings:
        return ["无。"]
    lines = ["| code | handle | 位置 | 说明 |", "|---|---|---|---|"]
    for w in warnings:
        rect = f"({w.rect.x:g},{w.rect.y:g},{w.rect.w:g},{w.rect.h:g})" if w.rect else "-"
        lines.append(f"| {w.code} | {w.handle or '-'} | {rect} | {_cell(w.message)} |")
    return lines


# warning code → 对应 CONVENTION.md 里的条目（spec 8.4 要求在报告结尾点名）
CONVENTION_RULES: dict[str, str] = {
    "UNCLOSED_POLYLINE": "控件必须是闭合矩形",
    "TEXT_OUTSIDE_BOX": "文字必须画在框里面",
    "CUT_CORNER_BOX": "异形框请用 5 顶点以上闭合多段线（已保留 points）",
    "OVERLAP": "控件之间不要部分重叠",
    "EMPTY_LABEL": "按钮/输入框内要有文字",
    "UNKNOWN_BLOCK": "块名用规范里的组件名，或用 --map 指定",
    "COLOR_APPROX": "颜色用 ACI 1-9 标准色",
}


def _convention_lines(warnings: list[Warning]) -> list[str]:
    seen: list[str] = []
    for w in warnings:
        rule = CONVENTION_RULES.get(w.code)
        if rule and rule not in seen:
            seen.append(rule)
    if not seen:
        return ["无。"]
    return [f"- {rule}" for rule in seen]


def render_report(layout: Layout, source: dict, path: Path) -> None:
    lines = [
        f"# {layout.title}",
        "",
        f"- 来源：`{source['file']}`（{source['via']}）",
        f"- 画布：{layout.canvas.width} × {layout.canvas.height} px"
        f"（scale {layout.canvas.scale}）",
        f"- 节点：{len(layout.nodes)}",
        f"- 实体：{source['entityCount']}",
        "",
        "## 统计",
        "",
    ]
    for key, value in sorted(layout.stats.items()):
        lines.append(f"- {key}: {value}")

    lines += ["", "## 元素清单", "",
              "| id | 类型 | 文字 | 位置 | 尺寸 | 置信度 | 布局约束 |",
              "|---|---|---|---|---|---|---|"]
    for node in layout.nodes:
        r = node.rect
        label = _cell(node.label or node.text or "")
        size = f"{r.w:g}×{r.h:g}"
        lines.append(
            f"| {node.id} | {_cell(node.type)} | {label} | ({r.x:g},{r.y:g}) | {size} "
            f"| {node.confidence:.2f} | {_computed_cell(node)} |"
        )

    lines += ["", "## 警告", ""] + _warning_lines(layout.warnings)
    lines += ["", "## 与 CONVENTION.md 的偏差", ""] + _convention_lines(layout.warnings)
    lines += ["", "画图规范见 `CONVENTION.md`。", ""]

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")
