from __future__ import annotations

from pathlib import Path

from core.model import Layout, Warning


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
        lines.append(f"| {w.code} | {w.handle or '-'} | {rect} | {w.message} |")
    return lines


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
        label = (node.label or node.text or "").replace("|", "\\|")
        size = f"{r.w:g}×{r.h:g}"
        lines.append(
            f"| {node.id} | {node.type} | {label} | ({r.x:g},{r.y:g}) | {size} "
            f"| {node.confidence:.2f} | {_computed_cell(node)} |"
        )

    lines += ["", "## 警告", ""] + _warning_lines(layout.warnings)
    lines += ["", "画图规范见 `CONVENTION.md`。", ""]

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")
