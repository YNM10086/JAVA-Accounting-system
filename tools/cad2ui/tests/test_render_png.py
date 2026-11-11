from PIL import Image

from core.model import Canvas, Layout, Node, Rect, Style
from core.render_png import render_png


def make_layout():
    text = Node(kind="text", type="text", label="登录", rect=Rect(20, 20, 80, 20),
                z=2, id="n2", style=Style(color="#303133", font_size=18))
    card = Node(kind="box", type="card", label="卡片", rect=Rect(0, 0, 200, 120),
                z=1, id="n1", style=Style(fill="#ffffff", stroke="#409EFF"))
    card.children = [text]
    return Layout(title="t", canvas=Canvas(200, 120, 1.0), tree=[card],
                  nodes=[card, text], warnings=[], stats={})


def test_png_size_matches_canvas(tmp_path):
    out = tmp_path / "p.png"
    render_png(make_layout(), out, [])
    with Image.open(out) as img:
        assert img.size == (200, 120)


def test_png_has_content(tmp_path):
    out = tmp_path / "p.png"
    render_png(make_layout(), out, [])
    with Image.open(out) as img:
        colors = img.convert("RGB").getcolors(maxcolors=100000)
    assert colors and len(colors) > 2


def test_missing_fonts_does_not_crash(tmp_path, monkeypatch):
    import core.render_png as rp
    monkeypatch.setattr(rp, "FONT_CANDIDATES", ())
    rp._font.cache_clear()
    out = tmp_path / "p.png"
    warnings = []
    from core.model import Warning
    render_png(make_layout(), out, warnings)
    assert out.exists()
