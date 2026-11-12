from __future__ import annotations

from ezdxf import bbox as ezbbox

from core.colors import FALLBACK, resolve_aci
from core.dxf_io import LoadedDrawing, select_entities
from core.errors import NoGeometry
from core.model import Canvas, Element, Rect, Style, Warning

KIND_BY_TYPE = {
    "INSERT": "block",
    "LWPOLYLINE": "box",
    "POLYLINE": "box",
    "LINE": "line",
    "CIRCLE": "circle",
    "ELLIPSE": "circle",
    "POINT": "circle",
    "TEXT": "text",
    "MTEXT": "text",
    "ATTRIB": "text",
    "SOLID": "fill",
    "HATCH": "fill",
}


def plain_text(entity) -> str:
    fn = getattr(entity, "plain_text", None)
    if callable(fn):
        try:
            return (fn() or "").strip()
        except Exception:
            pass
    return (entity.dxf.get("text", "") or "").strip()


def _entity_bbox(entity) -> tuple[float, float, float, float] | None:
    try:
        bb = ezbbox.extents([entity])
    except Exception:
        return None
    if not bb.has_data:
        return None
    return (bb.extmin.x, bb.extmin.y, bb.extmax.x, bb.extmax.y)


def _text_height(entity) -> float:
    try:
        if entity.dxftype() == "MTEXT":
            return float(entity.dxf.char_height)
        return float(entity.dxf.height)
    except Exception:
        return 0.0


def _text_bbox(entity) -> tuple[float, float, float, float] | None:
    bb = _entity_bbox(entity)
    if bb is not None:
        return bb
    try:
        p = entity.dxf.insert
    except Exception:
        return None
    h = _text_height(entity) or 2.5
    width = max(h, 0.6 * h * max(1, len(plain_text(entity))))
    return (p.x, p.y, p.x + width, p.y + h)


def _polygon_points(entity) -> list[tuple[float, float]]:
    if entity.dxftype() == "LWPOLYLINE":
        return [(p[0], p[1]) for p in entity.get_points("xy")]
    return [(p.x, p.y) for p in entity.points()]


def _is_axis_rect(points: list[tuple[float, float]], tol: float) -> bool:
    """顶点是否只落在两条竖边、两条横边上；tol 是参考坐标下的 1px。"""
    if len(points) not in (4, 5):
        return False

    def only_two(values: list[float]) -> bool:
        first = values[0]
        rest = [v for v in values if abs(v - first) > tol]
        if not rest:
            return False
        second = rest[0]
        return abs(second - first) > tol and all(
            abs(v - first) <= tol or abs(v - second) <= tol for v in values
        )

    return only_two([p[0] for p in points]) and only_two([p[1] for p in points])


def extract(loaded: LoadedDrawing, width: int = 1440,
            layers: list[str] | None = None,
            ignore_layers: list[str] | None = None
            ) -> tuple[list[Element], list[Warning], Canvas]:
    entities = select_entities(loaded.doc, layers, ignore_layers)
    if not entities:
        raise NoGeometry("图纸里没有可用实体（检查图层过滤或图纸是否为空）")

    bounds = []
    for e in entities:
        bb = _text_bbox(e) if e.dxftype() in ("TEXT", "MTEXT", "ATTRIB") else _entity_bbox(e)
        if bb is not None:
            bounds.append(bb)
    if not bounds:
        raise NoGeometry("所有实体都没有可计算的包围盒")

    bx0 = min(b[0] for b in bounds)
    by0 = min(b[1] for b in bounds)
    bx1 = max(b[2] for b in bounds)
    by1 = max(b[3] for b in bounds)
    dw = max(bx1 - bx0, 1e-6)
    dh = max(by1 - by0, 1e-6)
    scale = width / dw
    ref_scale = 1440.0 / dw
    # spec 7.1：顶点容差 1px（参考坐标） = dw/1440 图纸单位
    tol = dw / 1440.0

    def to_rect(x0, y0, x1, y1, s) -> Rect:
        return Rect(
            round((min(x0, x1) - bx0) * s, 1),
            round((by1 - max(y0, y1)) * s, 1),
            round(abs(x1 - x0) * s, 1),
            round(abs(y1 - y0) * s, 1),
        )

    elements: list[Element] = []
    warnings: list[Warning] = []

    for z, entity in enumerate(entities):
        etype = entity.dxftype()
        kind = KIND_BY_TYPE.get(etype)
        if kind is None:
            continue

        bb = _text_bbox(entity) if kind == "text" else _entity_bbox(entity)
        if bb is None:
            continue

        rect = to_rect(*bb, scale)
        ref_rect = to_rect(*bb, ref_scale)

        if rect.w < 1 and rect.h < 1:
            warnings.append(Warning("DEGENERATE_RECT", entity.dxf.handle, etype, rect,
                                    "缩放后宽高都小于 1px，已丢弃"))
            continue

        layer = entity.dxf.get("layer", "0")
        color, aci, reason = resolve_aci(loaded.doc, entity)
        if color is None:
            color = FALLBACK
            warnings.append(Warning("COLOR_APPROX", entity.dxf.handle, etype, rect, reason))

        style = Style()
        text = None
        block = None
        points = None
        is_rect = True

        if kind == "text":
            text = plain_text(entity)
            height = _text_height(entity)
            style.color = color
            style.font_size = round(height * scale, 1) or None
            style.rotation = float(entity.dxf.get("rotation", 0) or 0)
        elif kind == "line":
            style.stroke = color
            pts = _polygon_points(entity) if etype in ("LWPOLYLINE", "POLYLINE") else None
            if pts is None:
                pts = [(entity.dxf.start.x, entity.dxf.start.y),
                       (entity.dxf.end.x, entity.dxf.end.y)]
            points = [(round((p[0] - bx0) * scale, 1), round((by1 - p[1]) * scale, 1))
                      for p in pts]
        elif kind == "fill":
            style.fill = color
        elif kind == "block":
            block = entity.dxf.get("name")
            texts = [a.dxf.text.strip() for a in entity.attribs if a.dxf.text.strip()]
            text = " / ".join(texts) or None
            style.stroke = color
            style.rotation = float(entity.dxf.get("rotation", 0) or 0)
        elif kind in ("box", "circle"):
            style.stroke = color
            if kind == "circle":
                style.radius = round(max(rect.w, rect.h) / 2, 1)
            if etype in ("LWPOLYLINE", "POLYLINE"):
                pts = _polygon_points(entity)
                closed = entity.closed if etype == "LWPOLYLINE" else entity.is_closed
                if not closed or len(pts) < 3:
                    kind = "line"
                    warnings.append(Warning("UNCLOSED_POLYLINE", entity.dxf.handle, etype,
                                            rect, "多段线未闭合，按线处理"))
                    points = [(round((p[0] - bx0) * scale, 1),
                               round((by1 - p[1]) * scale, 1)) for p in pts]
                elif not _is_axis_rect(pts, tol):
                    is_rect = False
                    points = [(round((p[0] - bx0) * scale, 1),
                               round((by1 - p[1]) * scale, 1)) for p in pts]
                    warnings.append(Warning("CUT_CORNER_BOX", entity.dxf.handle, etype,
                                            rect, "非 4 顶点矩形，已保留原始点列 points"))

        elements.append(Element(
            kind=kind, handle=entity.dxf.handle or "", entity_type=etype, layer=layer,
            rect=rect, ref_rect=ref_rect, text=text, block=block, points=points,
            style=style, z=z, is_rect=is_rect,
        ))

    if not elements:
        raise NoGeometry("图纸里没有可识别的界面实体")

    # spec 7.1：fill 的 z 最低，否则铺满画布的背景填充会盖住整张图
    fills = [e for e in elements if e.kind == "fill"]
    others = [e for e in elements if e.kind != "fill"]
    for i, element in enumerate(fills + others):
        element.z = i

    canvas = Canvas(width=width, height=round(dh * scale), scale=round(scale, 4))
    return elements, warnings, canvas
