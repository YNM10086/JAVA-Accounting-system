from __future__ import annotations

from core.model import Element, Node, Rect, Warning


def contains(outer: Rect, inner: Rect, tol: float = 1.0) -> bool:
    return (inner.x >= outer.x - tol and inner.y >= outer.y - tol
            and inner.right <= outer.right + tol
            and inner.bottom <= outer.bottom + tol)


def _overlap_area(a: Rect, b: Rect) -> float:
    w = min(a.right, b.right) - max(a.x, b.x)
    h = min(a.bottom, b.bottom) - max(a.y, b.y)
    return max(0.0, w) * max(0.0, h)


def build_tree(elements: list[Element], warnings: list[Warning]) -> list[Node]:
    nodes: dict[int, Node] = {}
    for e in elements:
        nodes[id(e)] = Node(
            kind=e.kind, layer=e.layer, block=e.block, rect=e.rect,
            style=e.style, points=e.points, z=e.z,
            text=e.text, handle=e.handle, entity_type=e.entity_type,
        )

    # 面积升序：第一个"包含该元素"的 box 就是最小的父框（spec 7.3.2）
    boxes = sorted((e for e in elements if e.kind == "box"),
                   key=lambda e: (e.ref_rect.area, e.z))

    # 1) 父节点只能是 box
    parent: dict[int, int] = {}
    for e in elements:
        for b in boxes:
            if b is e:
                continue
            if e.kind == "box" and b.ref_rect.area <= e.ref_rect.area:
                continue
            if contains(b.ref_rect, e.ref_rect):
                parent[id(e)] = id(b)
                break

    # 2) 部分重叠 → OVERLAP（包含关系不算）
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            if _overlap_area(a.ref_rect, b.ref_rect) <= 0:
                continue
            if contains(a.ref_rect, b.ref_rect) or contains(b.ref_rect, a.ref_rect):
                continue
            warnings.append(Warning(
                "OVERLAP", a.handle, a.entity_type, a.rect,
                f"框 {a.handle} 与 {b.handle} 部分重叠，两者都保持同级、互不当父节点",
            ))

    # 2.5) 没落进任何框的文字 → TEXT_OUTSIDE_BOX
    for e in elements:
        if e.kind != "text" or id(e) in parent:
            continue
        partial = any(_overlap_area(e.ref_rect, b.ref_rect) > 0 for b in boxes)
        suffix = "（与框部分重叠）" if partial else ""
        warnings.append(Warning(
            "TEXT_OUTSIDE_BOX", e.handle, e.entity_type, e.rect,
            f"文字未完全落在任何框内{suffix}",
        ))

    # 3) 挂树
    roots: list[Node] = []
    for e in elements:
        pid = parent.get(id(e))
        if pid is None:
            roots.append(nodes[id(e)])
        else:
            nodes[pid].children.append(nodes[id(e)])

    # 4) 文字归位：每个 box 取字号最大的文字当 label，其余留作子节点
    best: dict[int, tuple[tuple[float, float, float], Element]] = {}
    for e in elements:
        if e.kind != "text" or not e.text:
            continue
        pid = parent.get(id(e))
        if pid is None:
            continue
        key = (e.style.font_size or 0.0, -e.rect.y, -e.rect.x)
        if pid not in best or key > best[pid][0]:
            best[pid] = (key, e)

    for pid, (_, e) in best.items():
        node = nodes[pid]
        node.label = e.text
        # 文字节点被消费掉了，字号/颜色必须留在容器上，否则渲染全按默认 14px
        if e.style.font_size:
            node.style.font_size = e.style.font_size
        if e.style.color:
            node.style.color = e.style.color
        node.hints.append(f"label:{e.handle}")
        consumed = nodes[id(e)]
        node.children = [c for c in node.children if c is not consumed]

    # 5) 同级按 z 排序
    for node in nodes.values():
        node.children.sort(key=lambda n: n.z)
    roots.sort(key=lambda n: n.z)

    return roots


def assign_ids(tree: list[Node]) -> list[Node]:
    flat: list[Node] = []
    counter = 0

    def walk(children: list[Node], parent_id: str | None, depth: int) -> None:
        nonlocal counter
        for node in sorted(children, key=lambda n: (n.z, n.rect.y, n.rect.x)):
            counter += 1
            node.id = f"n{counter}"
            node.parent_id = parent_id
            node.depth = depth
            flat.append(node)
            walk(node.children, node.id, depth + 1)

    walk(tree, None, 0)
    return flat
