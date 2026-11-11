from pathlib import Path

from core.model import Canvas, Layout, Node, Rect, Style, Warning
from core.report import render_report


def make_layout():
    child = Node(kind="box", type="button", label="确认", layer="UI-BOX",
                 rect=Rect(10, 20, 100, 40), z=2, id="n2", confidence=0.5,
                 hints=["size:button"], parent_id="n1", depth=1)
    root = Node(kind="box", type="card", label="登录", layer="UI-BOX",
                rect=Rect(0, 0, 300, 200), z=1, id="n1", confidence=0.95, depth=0)
    root.children = [child]
    root.computed = {"direction": "column", "justify": "start", "align": "stretch",
                     "gap": 8, "padding": [0, 0, 0, 0],
                     "childrenSpan": {"x": 0, "y": 0, "w": 300, "h": 100}}
    root.computed_confidence = 0.9
    return Layout(title="登录页", canvas=Canvas(1440, 900, 0.5), tree=[root],
                  nodes=[root, child],
                  warnings=[Warning("OVERLAP", "1F", "LWPOLYLINE", Rect(0, 0, 1, 1), "重叠了")],
                  stats={"boxes": 2, "texts": 1})


def test_report_has_summary_and_table(tmp_path):
    out = tmp_path / "r.md"
    render_report(make_layout(), {"file": "a.dxf", "via": "dxf", "entityCount": 3}, out)
    text = out.read_text(encoding="utf-8")
    assert "# 登录页" in text
    assert "| n1 |" in text and "| n2 |" in text
    assert "OVERLAP" in text
    assert "column" in text


def test_convention_file_exists_and_covers_layers():
    path = Path(__file__).resolve().parents[1] / "CONVENTION.md"
    text = path.read_text(encoding="utf-8")
    for layer in ("UI-FRAME", "UI-BOX", "UI-TEXT", "UI-LINE", "UI-FILL"):
        assert layer in text
