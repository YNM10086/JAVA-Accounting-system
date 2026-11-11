from __future__ import annotations

import json
from pathlib import Path

from core.model import Layout, Node, Rect, Warning

SCHEMA = "cad2ui/layout@1"


def round1(value: float) -> float:
    return round(float(value), 1)


def _rect_dict(rect: Rect) -> dict:
    return {"x": round1(rect.x), "y": round1(rect.y),
            "w": round1(rect.w), "h": round1(rect.h)}


def _style_dict(node: Node) -> dict:
    s = node.style
    out = {}
    if s.fill:
        out["fill"] = s.fill
    if s.stroke:
        out["stroke"] = s.stroke
    if s.color:
        out["color"] = s.color
    if s.font_size:
        out["fontSize"] = round1(s.font_size)
    if s.radius:
        out["radius"] = round1(s.radius)
    if s.rotation:
        out["rotation"] = round1(s.rotation)
    return out


def _node_dict(node: Node) -> dict:
    out = {
        "id": node.id,
        "parentId": node.parent_id,
        "type": node.type,
        "kind": node.kind,
        "label": node.label,
        "layer": node.layer,
        "rect": _rect_dict(node.rect),
        "z": node.z,
        "depth": node.depth,
        "confidence": round(node.confidence, 2),
    }
    if node.block:
        out["block"] = node.block
    if node.text:
        out["text"] = node.text
    style = _style_dict(node)
    if style:
        out["style"] = style
    if node.points:
        out["points"] = [[round1(x), round1(y)] for x, y in node.points]
    if node.hints:
        out["hints"] = list(node.hints)
    if node.computed:
        out["computed"] = node.computed
        out["computedConfidence"] = node.computed_confidence
    if node.children:
        out["children"] = [_node_dict(c) for c in node.children]
    return out


def _warning_dict(w: Warning) -> dict:
    return {
        "code": w.code,
        "handle": w.handle,
        "entityType": w.entity_type,
        "rect": _rect_dict(w.rect) if w.rect else None,
        "message": w.message,
    }


def layout_to_dict(layout: Layout, source: dict) -> dict:
    return {
        "schema": SCHEMA,
        "title": layout.title,
        "source": {
            "file": source["file"],
            "via": source["via"],
            "entityCount": source["entityCount"],
        },
        "canvas": {
            "width": layout.canvas.width,
            "height": layout.canvas.height,
            "scale": layout.canvas.scale,
        },
        "tree": [_node_dict(n) for n in layout.tree],
        "nodes": [_node_dict(n) for n in layout.nodes],
        "warnings": [_warning_dict(w) for w in layout.warnings],
        "stats": dict(sorted(layout.stats.items())),
    }


def dump_json(data: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, sort_keys=True, ensure_ascii=False, indent=2)
    path.write_text(text + "\n", encoding="utf-8", newline="\n")
