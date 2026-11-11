from core.model import Canvas, Element, Layout, Node, Rect, Style, Warning


def test_rect_properties():
    r = Rect(10, 20, 100, 50)
    assert r.right == 110
    assert r.bottom == 70
    assert r.area == 5000


def test_rect_area_never_negative():
    assert Rect(0, 0, -5, 10).area == 0


def test_defaults_are_independent():
    a = Node(kind="box")
    b = Node(kind="box")
    a.hints.append("x")
    assert b.hints == []
    assert a.children is not b.children


def test_element_and_layout_construct():
    el = Element(kind="box", handle="1F", entity_type="LWPOLYLINE",
                 layer="UI-BOX", rect=Rect(0, 0, 10, 10), ref_rect=Rect(0, 0, 10, 10))
    layout = Layout(title="t", canvas=Canvas(1440, 900, 0.5), tree=[],
                    nodes=[], warnings=[], stats={})
    assert el.style.fill is None
    assert layout.canvas.width == 1440
