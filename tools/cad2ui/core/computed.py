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
        if all(abs(c.w - parent.w) <= 2 for c in children):
            return "stretch"
        left = [c.x - parent.x for c in children]
        right = [parent.right - c.right for c in children]
    else:
        if all(abs(c.h - parent.h) <= 2 for c in children):
            return "stretch"
        left = [c.y - parent.y for c in children]
        right = [parent.bottom - c.bottom for c in children]

    spread_start = max(left) - min(left)
    spread_end = max(right) - min(right)
    spread_center = max(abs(l - r) for l, r in zip(left, right))

    candidates = [(spread_start, 0, "start"), (spread_end, 1, "end"),
                  (spread_center, 2, "center")]
    return min(candidates, key=lambda c: (c[0], c[1]))[2]


def _justify_main(parent: Rect, children: list[Rect], direction: str,
                  gap: float | None) -> str | None:
    if direction is None:
        return None
    if direction == "row":
        start, end = parent.x, parent.right
        lead = min(c.x for c in children) - start
        trail = end - max(c.right for c in children)
    else:
        start, end = parent.y, parent.bottom
        lead = min(c.y for c in children) - start
        trail = end - max(c.bottom for c in children)

    lead, trail = max(0.0, lead), max(0.0, trail)
    if gap and gap > 0 and abs(lead - gap) <= 2 and abs(trail - gap) <= 2:
        return "space-between"
    if lead <= 2:
        return "start"
    if trail <= 2:
        return "end"
    if abs(lead - trail) <= 2:
        return "center"
    return "start"


def compute_container(node: Node) -> tuple[dict | None, float | None]:
    children = [c.rect for c in node.children]
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
        if max(gaps) - min(gaps) > 4:
            conf -= 0.2
        align = _align_cross(parent, children, direction)
    else:
        conf = 0.3
        align = "start"

    justify = _justify_main(parent, children, direction, gap)

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
