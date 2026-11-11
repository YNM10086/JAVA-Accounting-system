import re

from core.model import Canvas, Layout, Node, Rect, Style
from core.render_html import render_html


def make_layout(label="登录"):
    child = Node(kind="box", type="button", label=label, layer="UI-BOX",
                 rect=Rect(10, 20, 100, 40), z=2, id="n2", parent_id="n1", depth=1)
    root = Node(kind="box", type="card", label="卡片", layer="UI-BOX",
                rect=Rect(0, 0, 300, 200), z=1, id="n1", depth=0)
    root.children = [child]
    return Layout(title="测试页", canvas=Canvas(1440, 900, 0.5), tree=[root],
                  nodes=[root, child], warnings=[], stats={})


def test_every_node_rendered(tmp_path):
    out = tmp_path / "p.html"
    render_html(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 2}, out)
    html = out.read_text(encoding="utf-8")
    assert len(re.findall(r"data-cad-node=", html)) == 2
    assert 'data-type="card"' in html
    assert 'data-type="button"' in html


def test_label_is_escaped(tmp_path):
    out = tmp_path / "p.html"
    render_html(make_layout(label="<script>alert(1)</script>"),
                {"file": "a.dxf", "via": "dxf", "entityCount": 2}, out)
    html = out.read_text(encoding="utf-8")
    assert "<script>alert(1)</script>" not in html
    assert "&lt;script&gt;" in html


def test_canvas_size_and_meta(tmp_path):
    out = tmp_path / "p.html"
    render_html(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 2}, out)
    html = out.read_text(encoding="utf-8")
    assert "width:1440px" in html
    assert "height:900px" in html
    assert "a.dxf" in html


def test_points_shape_uses_svg_polygon(tmp_path):
    cut = Node(kind="box", type="card", rect=Rect(0, 0, 100, 100), z=1, id="n1",
               points=[(0, 0), (100, 0), (100, 80), (80, 100), (0, 100)])
    layout = Layout(title="t", canvas=Canvas(100, 100, 1.0), tree=[cut],
                    nodes=[cut], warnings=[], stats={})
    out = tmp_path / "p.html"
    render_html(layout, {"file": "a.dxf", "via": "dxf", "entityCount": 1}, out)
    html = out.read_text(encoding="utf-8")
    assert "<polygon" in html
