from core.computed import apply_computed, compute_container
from core.model import Node, Rect


def container(x, y, w, h, children):
    node = Node(kind="box", rect=Rect(x, y, w, h))
    node.children = [Node(kind="box", rect=r) for r in children]
    return node


def test_column_direction_gap_padding_align():
    node = container(0, 0, 200, 200, [Rect(0, 0, 100, 20), Rect(0, 40, 100, 20),
                                      Rect(0, 80, 100, 20)])
    got, conf = compute_container(node)
    assert got["direction"] == "column"
    assert got["gap"] == 20
    assert got["padding"] == [0, 100, 100, 0]
    assert got["align"] == "start"
    assert got["childrenSpan"] == {"x": 0, "y": 0, "w": 100, "h": 100}
    assert 0.5 <= conf <= 1.0


def test_row_direction():
    node = container(0, 0, 300, 100, [Rect(0, 0, 50, 50), Rect(70, 0, 50, 50),
                                      Rect(140, 0, 50, 50)])
    got, _ = compute_container(node)
    assert got["direction"] == "row"
    assert got["gap"] == 20


def test_stretch_align():
    node = container(0, 0, 200, 100, [Rect(0, 0, 200, 30), Rect(0, 40, 200, 30)])
    got, _ = compute_container(node)
    assert got["align"] == "stretch"


def test_center_justify():
    node = container(0, 0, 300, 100, [Rect(62, 0, 50, 50), Rect(125, 0, 50, 50),
                                      Rect(188, 0, 50, 50)])
    got, _ = compute_container(node)
    assert got["justify"] == "center"


def test_space_between_justify():
    node = container(0, 0, 400, 100, [Rect(62.5, 0, 50, 50), Rect(175, 0, 50, 50),
                                      Rect(287.5, 0, 50, 50)])
    got, _ = compute_container(node)
    assert got["justify"] == "space-between"


def test_single_child_has_no_direction():
    node = container(0, 0, 200, 200, [Rect(20, 20, 50, 20)])
    got, conf = compute_container(node)
    assert got["direction"] is None
    assert got["gap"] is None
    assert got["padding"] == [20, 130, 160, 20]
    assert conf < 0.5


def test_irregular_layout_returns_none_direction():
    node = container(0, 0, 300, 300, [Rect(0, 0, 50, 50), Rect(200, 200, 50, 50)])
    got, _ = compute_container(node)
    assert got["direction"] is None


def test_align_stretch_respects_padding():
    """子节点铺满"内容区"（而不是外框）时就是 stretch。"""
    node = container(0, 0, 300, 400, [Rect(16, 16, 268, 100), Rect(16, 140, 268, 100)])
    got, _ = compute_container(node)
    assert got["padding"] == [16, 16, 160, 16]
    assert got["align"] == "stretch"


def test_align_center_when_children_are_inset_symmetrically():
    """左右留白相等的居中表单，不该报 start。"""
    node = container(0, 0, 300, 200, [Rect(50, 0, 200, 50), Rect(50, 80, 200, 50)])
    got, _ = compute_container(node)
    assert got["align"] == "center"


def test_justify_space_between_when_flush_at_both_edges():
    """真实 CSS space-between：首尾贴边、间距相等。"""
    node = container(0, 0, 300, 100, [Rect(0, 0, 50, 50), Rect(125, 0, 50, 50),
                                      Rect(250, 0, 50, 50)])
    got, _ = compute_container(node)
    assert got["gap"] == 75
    assert got["justify"] == "space-between"


def test_justify_stays_center_for_a_padded_group():
    """内边距对称但间距与留白不等时，仍是居中（不代表 space-between）。"""
    node = container(0, 0, 400, 100, [Rect(16, 0, 50, 50), Rect(175, 0, 50, 50),
                                      Rect(334, 0, 50, 50)])
    got, _ = compute_container(node)
    assert got["padding"] == [0, 16, 50, 16]
    assert got["justify"] == "center"


def test_no_children_returns_none():
    node = Node(kind="box", rect=Rect(0, 0, 10, 10))
    assert compute_container(node) == (None, None)


def test_apply_computed_only_on_containers():
    leaf = Node(kind="text", rect=Rect(0, 0, 10, 10))
    parent = Node(kind="box", rect=Rect(0, 0, 100, 100))
    parent.children = [leaf]
    apply_computed([parent])
    assert parent.computed is not None
    assert leaf.computed is None
