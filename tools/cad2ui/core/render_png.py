from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from core.model import Layout, Rect, Warning

FONT_CANDIDATES = (
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)

CENTERED = {"button", "input", "select", "tab", "badge", "switch", "checkbox"}

_font_reported = False


@lru_cache(maxsize=64)
def _font(size: int):
    for path in FONT_CANDIDATES:
        try:
            if Path(path).exists():
                return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def load_font(size: int):
    return _font(max(1, int(size)))


def _has_real_font() -> bool:
    return any(Path(p).exists() for p in FONT_CANDIDATES)


def _rgba(hex_color: str | None, default: tuple[int, int, int, int]):
    if not hex_color:
        return default
    value = hex_color.lstrip("#")
    if len(value) != 6:
        return default
    try:
        return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)
    except ValueError:
        return default


def _text_rect(node) -> Rect:
    return node.rect


def render_png(layout: Layout, path: Path, warnings: list[Warning]) -> None:
    global _font_reported
    if not _font_reported and not _has_real_font():
        _font_reported = True
        warnings.append(Warning(
            "FONT_FALLBACK", "", "", None,
            "找不到中文字体，PNG 使用内置点阵字体，中文可能显示不全",
        ))

    img = Image.new("RGBA", (max(1, layout.canvas.width), max(1, layout.canvas.height)),
                    (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)

    for node in sorted(layout.nodes, key=lambda n: n.z):
        r = _text_rect(node)
        box = [r.x, r.y, r.right, r.bottom]
        if node.kind in ("box", "block", "circle", "fill"):
            fill = _rgba(node.style.fill, None) if node.style.fill else None
            outline = _rgba(node.style.stroke, None)
            if node.kind == "circle":
                draw.ellipse(box, fill=fill, outline=outline)
            elif fill or outline:
                draw.rectangle(box, fill=fill, outline=outline)
        elif node.kind == "line":
            color = _rgba(node.style.stroke, (144, 147, 153, 255))
            if node.points and len(node.points) >= 2:
                draw.line([tuple(p) for p in node.points], fill=color, width=1)
            else:
                draw.rectangle(box, fill=color)

        if node.label and node.kind != "text" and node.type in CENTERED:
            size = int(node.style.font_size or 14)
            draw.text((r.x + r.w / 2, r.y + r.h / 2), node.label,
                      fill=_rgba(node.style.color or node.style.stroke, (48, 49, 51, 255)),
                      font=load_font(size), anchor="mm")
        elif node.label and node.kind != "text":
            size = int(node.style.font_size or 14)
            draw.text((r.x + 8, r.y + 6), node.label,
                      fill=_rgba(node.style.color or node.style.stroke, (48, 49, 51, 255)),
                      font=load_font(size))

    for node in layout.nodes:
        if node.kind != "text" or not (node.text or node.label):
            continue
        size = int(node.style.font_size or 14)
        draw.text((node.rect.x, node.rect.y), node.text or node.label,
                  fill=_rgba(node.style.color, (48, 49, 51, 255)), font=load_font(size))

    path.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(path, format="PNG")
