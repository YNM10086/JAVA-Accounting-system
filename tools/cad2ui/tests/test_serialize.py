import json

from core.model import Canvas, Layout, Node, Rect, Style, Warning
from core.serialize import dump_json, layout_to_dict


def make_layout():
    child = Node(kind="box", type="button", label="确认", layer="UI-BOX",
                 rect=Rect(10.04, 20.05, 100.0, 40.0), z=2, confidence=0.5,
                 hints=["size:button"], id="n2", parent_id="n1", depth=1)
    child.computed = None
    root = Node(kind="box", type="card", label="登录", layer="UI-BOX",
                rect=Rect(0, 0, 300, 200), z=1, confidence=0.95, id="n1", depth=0)
    root.children = [child]
    root.computed = {"direction": "column", "justify": "start", "align": "stretch",
                     "gap": 8, "padding": [0, 0, 0, 0],
                     "childrenSpan": {"x": 0, "y": 0, "w": 300, "h": 100}}
    root.computed_confidence = 0.9
    return Layout(title="测试", canvas=Canvas(1440, 900, 0.5), tree=[root],
                  nodes=[root, child],
                  warnings=[Warning("OVERLAP", "1F", "LWPOLYLINE", Rect(0, 0, 1, 1), "重叠")],
                  stats={"boxes": 2})


def test_top_level_shape():
    data = layout_to_dict(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3})
    assert data["schema"] == "cad2ui/layout@1"
    assert data["source"]["via"] == "dxf"
    assert data["canvas"] == {"width": 1440, "height": 900, "scale": 0.5}
    assert "outline" not in data
    assert len(data["nodes"]) == 2
    assert data["nodes"][1]["parentId"] == "n1"


def test_rect_values_rounded_to_one_decimal():
    data = layout_to_dict(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3})
    assert data["nodes"][1]["rect"]["x"] == 10.0 or data["nodes"][1]["rect"]["x"] == 10.1


def test_empty_optional_fields_are_omitted():
    data = layout_to_dict(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3})
    child = data["nodes"][1]
    assert "computed" not in child
    assert "points" not in child


def test_warning_shape():
    data = layout_to_dict(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3})
    w = data["warnings"][0]
    assert set(w) == {"code", "handle", "entityType", "rect", "message"}


def test_dump_is_deterministic_and_readable(tmp_path):
    data = layout_to_dict(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3})
    p1, p2 = tmp_path / "a.json", tmp_path / "b.json"
    dump_json(data, p1)
    dump_json(data, p2)
    assert p1.read_bytes() == p2.read_bytes()
    text = p1.read_text(encoding="utf-8")
    assert "\n" in text and "测试" in text      # 有缩进、中文不转义
