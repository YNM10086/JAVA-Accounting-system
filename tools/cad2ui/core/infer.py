from __future__ import annotations

from core.model import Node, Warning
from core.style import LAYER_CONTRACT, keyword_type

CONF_LABEL_MAP = {"layer": 1.0, "map": 0.95, "keyword": 0.85, "size": 0.5, "unknown": 0.2}


def _lookup(table: dict, key: str | None) -> str | None:
    if not key:
        return None
    if key in table:
        return table[key]
    low = key.lower()
    for k, v in table.items():
        if k.lower() == low:
            return v
    return None


def _size_type(node: Node, k: float, canvas_height: float = 900.0) -> str | None:
    r = node.rect
    if r.w >= 1260 * k:
        if r.y <= 80 * k:
            return "header"
        if r.bottom >= (canvas_height - 80) * k:
            return "footer"
    if r.h <= 56 * k and 40 * k <= r.w <= 320 * k and node.label:
        return "button"
    if r.w >= 300 * k and r.h >= 120 * k:
        return "card"
    if r.w < 48 * k and r.h < 48 * k:
        return "icon"
    return None


def infer_type(node: Node, mapping: dict, canvas_width: int = 1440,
               canvas_height: float = 900.0) -> tuple[str, float, list[str]]:
    k = canvas_width / 1440.0
    hints: list[str] = []

    # 图层契约大小写不敏感，和 match_layer 的行为保持一致
    contract = LAYER_CONTRACT.get(node.layer.upper())
    if contract:
        return contract, 1.0, [f"layer:{node.layer}"]

    blocks = (mapping or {}).get("blocks") or {}
    layers = (mapping or {}).get("layers") or {}

    hit = _lookup(blocks, node.block)
    if hit:
        return hit, 0.95, [f"map:block:{node.block}"]
    hit = _lookup(layers, node.layer)
    if hit:
        return hit, 0.95, [f"map:layer:{node.layer}"]

    if node.block:
        hints.append(f"block:{node.block}")
        hit = keyword_type(node.block)
        if hit:
            return hit, 0.85, hints + [f"keyword:block:{node.block}"]
    hit = keyword_type(node.layer)
    if hit:
        return hit, 0.85, hints + [f"keyword:layer:{node.layer}"]

    hit = _size_type(node, k, canvas_height)
    if hit:
        return hit, 0.5, hints + [f"size:{hit}"]

    return "unknown", 0.2, hints


def apply_types(tree: list[Node], mapping: dict, canvas_width: int,
                warnings: list[Warning], canvas_height: float = 900.0) -> None:
    stack = list(tree)
    while stack:
        node = stack.pop()
        kept = list(node.hints)
        node.type, node.confidence, hints = infer_type(node, mapping, canvas_width,
                                                       canvas_height)
        node.hints = kept + [h for h in hints if h not in kept]

        if node.type == "unknown" and node.block:
            warnings.append(Warning(
                "UNKNOWN_BLOCK", node.handle, node.entity_type, node.rect,
                f"块 {node.block} 没有命中任何映射或关键词",
            ))
        if node.type in ("button", "input", "select") and not node.label:
            warnings.append(Warning(
                "EMPTY_LABEL", node.handle, node.entity_type, node.rect,
                f"{node.type} 内没有文字，无法确定用途",
            ))

        stack.extend(node.children)
