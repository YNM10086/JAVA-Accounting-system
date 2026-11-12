from core.dxf_io import load_drawing
from core.extract import extract
from core.layout import assign_ids, build_tree
from tests.fixtures import make_login_dxf, make_warning_dxf


def build(tmp_path, name="login.dxf"):
    loaded = load_drawing(make_login_dxf(tmp_path / name), tmp_path)
    elements, warnings, _ = extract(loaded)
    tree = build_tree(elements, warnings)
    flat = assign_ids(tree)
    return tree, flat, warnings


def find(flat, label=None, kind=None, label_prefix=None):
    for n in flat:
        name = n.label or n.text
        if label is not None and name != label:
            continue
        if kind is not None and n.kind != kind:
            continue
        if label_prefix is not None and not (name or "").startswith(label_prefix):
            continue
        return n
    raise AssertionError(f"未找到节点 label={label} kind={kind}")


def test_ids_are_preorder_and_contiguous(tmp_path):
    tree, flat, _ = build(tmp_path)
    assert [n.id for n in flat] == [f"n{i}" for i in range(1, len(flat) + 1)]
    assert flat[0].parent_id is None
    assert flat[0].depth == 0


def test_frame_is_root_and_contains_card(tmp_path):
    tree, flat, _ = build(tmp_path)
    root = tree[0]
    assert root.layer == "UI-FRAME"
    card = find(flat, label="登录")
    assert card.parent_id == root.id


def test_button_is_child_of_card_not_fill(tmp_path):
    _, flat, _ = build(tmp_path)
    card = find(flat, label="登录")
    button = find(flat, label="确认")
    assert button.parent_id == card.id
    assert button.kind == "box"


def test_fill_is_never_a_parent(tmp_path):
    _, flat, _ = build(tmp_path)
    fills = [n for n in flat if n.kind == "fill"]
    assert fills
    for f in fills:
        assert f.children == []


def test_label_prefers_largest_font(tmp_path):
    from core.model import Element, Rect, Style

    def rect(x, y, w, h):
        return Rect(x, y, w, h)

    elements = [
        Element("box", "1", "LWPOLYLINE", "UI-BOX", rect(0, 0, 200, 200), rect(0, 0, 200, 200)),
        Element("text", "2", "TEXT", "UI-TEXT", rect(10, 20, 80, 10), rect(10, 20, 80, 10),
                text="描述", style=Style(font_size=10)),
        Element("text", "3", "TEXT", "UI-TEXT", rect(10, 150, 120, 20), rect(10, 150, 120, 20),
                text="标题", style=Style(font_size=20)),
    ]
    tree = build_tree(elements, [])
    node = tree[0]
    assert node.label == "标题"
    assert [c.text for c in node.children] == ["描述"]


def test_label_keeps_font_size_and_color(tmp_path):
    """label 的文字被消费掉了，字号/颜色必须留在容器上，否则渲染全变 14px。"""
    import pytest

    _, flat, _ = build(tmp_path)
    card = find(flat, label="登录")
    assert card.style.font_size == pytest.approx(28, abs=0.5)
    assert any(h.startswith("label:") for h in card.hints)


def test_text_outside_any_box_stays_root(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    tree = build_tree(elements, warnings)
    flat = assign_ids(tree)
    standalone = find(flat, label="孤立文字")
    assert standalone.parent_id is None


def test_overlap_warning_emitted(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    build_tree(elements, warnings)
    assert any(w.code == "OVERLAP" for w in warnings)


def test_overlap_message_does_not_claim_a_parent_is_chosen(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    build_tree(elements, warnings)
    message = [w.message for w in warnings if w.code == "OVERLAP"][0]
    assert "同级" in message


def test_standalone_text_gets_outside_warning(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    build_tree(elements, warnings)
    assert any(w.code == "TEXT_OUTSIDE_BOX" for w in warnings)


def test_children_sorted_by_z(tmp_path):
    tree, flat, _ = build(tmp_path)
    for node in flat:
        zs = [c.z for c in node.children]
        assert zs == sorted(zs)
