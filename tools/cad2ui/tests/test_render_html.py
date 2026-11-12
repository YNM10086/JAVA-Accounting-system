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


def _node_div(html: str, node_id: str) -> str:
    m = re.search(r'<div class="node" data-cad-node="%s".*?</div>' % node_id, html)
    assert m, f"节点 {node_id} 未渲染"
    return m.group(0)


def test_open_polyline_is_not_painted_as_a_box(tmp_path):
    line = Node(kind="line", type="line", rect=Rect(0, 0, 100, 50), z=1, id="n1",
                points=[(0, 50), (100, 50), (100, 0)], style=Style(stroke="#303133"))
    layout = Layout(title="t", canvas=Canvas(100, 50, 1.0), tree=[line],
                    nodes=[line], warnings=[], stats={})
    out = tmp_path / "p.html"
    render_html(layout, {"file": "a.dxf", "via": "dxf", "entityCount": 1}, out)
    div = _node_div(out.read_text(encoding="utf-8"), "n1")
    assert "<polyline" in div
    assert "background:" not in div        # 否则折线会被画成实心矩形


def test_rotation_is_not_reapplied_to_bbox(tmp_path):
    """rect 已是旋转后的 AABB，再套 CSS rotate 会二次旋转且方向相反。"""
    block = Node(kind="block", type="card", rect=Rect(0, 0, 40, 240), z=1, id="n1",
                 style=Style(stroke="#409EFF", rotation=90))
    layout = Layout(title="t", canvas=Canvas(100, 300, 1.0), tree=[block],
                    nodes=[block], warnings=[], stats={})
    out = tmp_path / "p.html"
    render_html(layout, {"file": "a.dxf", "via": "dxf", "entityCount": 1}, out)
    html = out.read_text(encoding="utf-8")
    assert "rotate(" not in html
