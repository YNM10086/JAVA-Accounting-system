from __future__ import annotations

from statistics import median

from core.model import Node, Rect


def _intervals_overlap(a0, a1, b0, b1) -> bool:
    return min(a1, b1) - max(a0, b0) > 0


def _all_pairs_overlap(spans: list[tuple[float, float]]) -> bool:
    for i in range(len(spans)):
        for j in range(i + 1, len(spans)):
            if not _intervals_overlap(spans[i][0], spans[i][1], spans[j][0], spans[j][1]):
                return False
    return True


def _union(rects: list[Rect]) -> Rect:
    x0 = min(r.x for r in rects)
    y0 = min(r.y for r in rects)
    x1 = max(r.right for r in rects)
    y1 = max(r.bottom for r in rects)
    return Rect(x0, y0, x1 - x0, y1 - y0)


def _align_cross(parent: Rect, children: list[Rect], direction: str) -> str:
    if direction == "column":
        sizes = [c.w for c in children]
        extent = parent.w
        start_gap = [c.x - parent.x for c in children]
        end_gap = [parent.right - c.right for c in children]
    else:
        sizes = [c.h for c in children]
        extent = parent.h
        start_gap = [c.y - parent.y for c in children]
        end_gap = [parent.bottom - c.bottom for c in children]

    # 交叉尺寸一致且占到容器 80% 以上 → stretch（padding 是从子节点反推的，不能直接和它比）
    if extent > 0 and max(sizes) - min(sizes) <= 2 and min(sizes) >= 0.8 * extent:
        return "stretch"

    spread_start = max(start_gap) - min(start_gap)
    spread_end = max(end_gap) - min(end_gap)

    # 左右留白各自一致：留白为 0 → 贴边（start），留白相等且 > 0 → 居中
    if spread_start <= 1 and spread_end <= 1:
        if min(start_gap) > 2 and min(end_gap) > 2:
            return "center"
        return "start"

    best = min(spread_start, spread_end)
    if spread_start <= best + 1:
        return "start"
    if spread_end <= best + 1:
        return "end"
    return "center"


def _justify_main(parent: Rect, children: list[Rect], direction: str,
                  gap: float | None, gaps_uniform: bool) -> str | None:
    if direction is None:
        return None
    if direction == "row":
        lead = min(c.x for c in children) - parent.x
        trail = parent.right - max(c.right for c in children)
    else:
        lead = min(c.y for c in children) - parent.y
        trail = parent.bottom - max(c.bottom for c in children)

    lead, trail = max(0.0, lead), max(0.0, trail)
    distributed = bool(gap and gap > 0 and gaps_uniform)

    if lead <= 2 and trail <= 2:
        # 首尾都贴边且间距均匀 → 真实的 CSS space-between
        return "space-between" if distributed else "start"
    if distributed and abs(lead - gap) <= 2 and abs(trail - gap) <= 2:
        return "space-between"
    if lead <= 2:
        return "start"
    if trail <= 2:
        return "end"
    if abs(lead - trail) <= 2:
        return "center"
    return "start"


def compute_container(node: Node) -> tuple[dict | None, float | None]:
    # fill 是背景层（兄弟节点），参与 padding/childrenSpan，但不参与主轴方向判定
    children = [c.rect for c in node.children if c.kind != "fill"]
    if not children:
        return None, None

    span = _union(children)
    parent = node.rect

    padding = [
        int(round(max(0.0, span.y - parent.y))),
        int(round(max(0.0, parent.right - span.right))),
        int(round(max(0.0, parent.bottom - span.bottom))),
        int(round(max(0.0, span.x - parent.x))),
    ]
    children_span = {"x": round(span.x, 1), "y": round(span.y, 1),
                     "w": round(span.w, 1), "h": round(span.h, 1)}

    if len(children) < 2:
        return {
            "direction": None, "justify": None, "align": None, "gap": None,
            "padding": padding, "childrenSpan": children_span,
        }, 0.3

    y_overlap = _all_pairs_overlap([(c.y, c.bottom) for c in children])
    x_overlap = _all_pairs_overlap([(c.x, c.right) for c in children])
    if y_overlap and not x_overlap:
        direction = "row"
    elif x_overlap and not y_overlap:
        direction = "column"
    else:
        direction = None

    gap = None
    gaps_uniform = False
    conf = 0.9
    if direction:
        axis = 0 if direction == "row" else 1
        ordered = sorted(children, key=lambda c: (c.x if axis == 0 else c.y))
        gaps = []
        for prev, nxt in zip(ordered, ordered[1:]):
            if axis == 0:
                gaps.append(nxt.x - prev.right)
            else:
                gaps.append(nxt.y - prev.bottom)
        gap = max(0, int(round(median(gaps))))
        gaps_uniform = (max(gaps) - min(gaps)) <= 2
        if max(gaps) - min(gaps) > 4:
            conf -= 0.2
        align = _align_cross(parent, children, direction)
    else:
        conf = 0.3
        align = "start"

    justify = _justify_main(parent, children, direction, gap, gaps_uniform)

    return {
        "direction": direction,
        "justify": justify,
        "align": align,
        "gap": gap,
        "padding": padding,
        "childrenSpan": children_span,
    }, round(conf, 2)


def apply_computed(tree: list[Node]) -> None:
    stack = list(tree)
    while stack:
        node = stack.pop()
        node.computed, node.computed_confidence = compute_container(node)
        stack.extend(node.children)
