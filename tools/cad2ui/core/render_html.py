from __future__ import annotations

import html as htmllib
from pathlib import Path

from core.model import Layout, Node

CENTERED_TYPES = {"button", "input", "select", "tab", "badge", "switch", "checkbox"}

CSS = """
* { box-sizing: border-box; }
body { margin: 0; padding: 24px; background: #eef1f6;
       font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif; }
.meta { max-width: 1440px; margin: 0 auto 12px; color: #606266; font-size: 13px; }
.meta b { color: #303133; }
.canvas { position: relative; margin: 0 auto; background: #fff;
          outline: 1px solid #c8cdd6; overflow: hidden; }
.node { position: absolute; }
.label { white-space: pre-wrap; }
.label.center { position: absolute; inset: 0; display: flex;
                align-items: center; justify-content: center; }
.label.corner { position: absolute; left: 8px; top: 6px; }
.points { position: absolute; left: 0; top: 0; overflow: visible; }
"""


def _px(v: float) -> str:
    return f"{round(v, 1):g}px"


def _style_attr(node: Node) -> str:
    s = node.style
    parts = [f"left:{_px(node.rect.x)}", f"top:{_px(node.rect.y)}",
             f"width:{_px(max(node.rect.w, 1))}", f"height:{_px(max(node.rect.h, 1))}",
             f"z-index:{node.z}"]
    if s.fill:
        parts.append(f"background:{s.fill}")
    if s.stroke and node.kind in ("box", "block", "circle"):
        parts.append(f"border:1px solid {s.stroke}")
    if node.kind == "line" and not node.points:
        # 两点直线才用背景色块画；有 points 的折线交给内联 SVG，否则会变成实心矩形
        parts.append(f"background:{s.stroke or '#909399'}")
    if node.kind == "circle":
        parts.append("border-radius:50%")
    return ";".join(parts)


def _points_svg(node: Node) -> str:
    w = max(node.rect.w, 1)
    h = max(node.rect.h, 1)
    local = " ".join(f"{round(x - node.rect.x, 1):g},{round(y - node.rect.y, 1):g}"
                     for x, y in node.points or [])
    color = node.style.stroke or node.style.fill or "#909399"
    if node.kind == "line":
        shape = f'<polyline points="{local}" fill="none" stroke="{color}" stroke-width="1"/>'
    else:
        fill = node.style.fill or "none"
        shape = (f'<polygon points="{local}" fill="{fill}" '
                 f'stroke="{node.style.stroke or color}" stroke-width="1"/>')
    return (f'<svg class="points" width="{_px(w)}" height="{_px(h)}" '
            f'viewBox="0 0 {round(w, 1):g} {round(h, 1):g}">{shape}</svg>')


def _label_html(node: Node) -> str:
    if not node.label or node.kind == "text":
        return ""
    size = node.style.font_size or 14
    color = node.style.color or "#303133"
    if node.type in CENTERED_TYPES:
        return (f'<div class="label center" style="font-size:{_px(size)};color:{color}">'
                f'{htmllib.escape(node.label)}</div>')
    return (f'<div class="label corner" style="font-size:{_px(size)};color:{color}">'
            f'{htmllib.escape(node.label)}</div>')


def _text_html(node: Node) -> str:
    size = node.style.font_size or 14
    color = node.style.color or "#303133"
    return (f'<div class="node" data-cad-node="{node.id}" data-type="{node.type}" '
            f'data-label-source="1" style="{_style_attr(node)}">'
            f'<span class="label" style="font-size:{_px(size)};color:{color}">'
            f'{htmllib.escape(node.text or node.label or "")}</span></div>')


def _node_html(node: Node) -> str:
    if node.kind == "text":
        return _text_html(node)
    parent = node.parent_id or ""
    inner = _points_svg(node) if node.points else ""
    inner += _label_html(node)
    return (f'<div class="node" data-cad-node="{node.id}" data-type="{node.type}" '
            f'data-parent="{parent}" style="{_style_attr(node)}">'
            f'{inner}</div>')


def render_html(layout: Layout, source: dict, path: Path) -> None:
    title = htmllib.escape(layout.title)
    meta = (
        f"<b>{title}</b> · 来源 {htmllib.escape(str(source['file']))} "
        f"({htmllib.escape(str(source['via']))}) · 画布 "
        f"{layout.canvas.width}×{layout.canvas.height}px · 节点 {len(layout.nodes)} · "
        f"warnings {len(layout.warnings)}"
    )
    body = "".join(_node_html(n) for n in layout.nodes)
    doc = (
        "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n"
        f"<title>{title}</title>\n<style>{CSS}</style>\n</head>\n<body>\n"
        f'<div class="meta">{meta}</div>\n'
        f'<div class="canvas" style="width:{layout.canvas.width}px;'
        f'height:{layout.canvas.height}px">\n{body}\n</div>\n</body>\n</html>\n'
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(doc, encoding="utf-8", newline="\n")
