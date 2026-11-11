from core.infer import apply_types
from core.layout import assign_ids, build_tree
from core.model import Element, Rect, Style
from core.style import keyword_type, load_mapping


def box(x, y, w, h, layer="UI-BOX", block=None, text=None):
    r = Rect(x, y, w, h)
    return Element("box", "1", "LWPOLYLINE", layer, r, r, text=text, block=block)


def text_el(x, y, w, h, content, size=16.0):
    r = Rect(x, y, w, h)
    return Element("text", "9", "TEXT", "UI-TEXT", r, r, text=content,
                   style=Style(font_size=size))


def infer_one(elements, mapping=None, width=1440):
    warnings = []
    tree = build_tree(elements, warnings)
    assign_ids(tree)
    apply_types(tree, mapping or {}, width, warnings)
    return tree[0], warnings


def test_layer_contract_beats_keywords():
    frame = box(0, 0, 1440, 900, layer="UI-FRAME", block="card_hero")
    node, _ = infer_one([frame])
    assert node.type == "frame"
    assert node.confidence == 1.0


def test_mapping_beats_keywords():
    b = box(100, 100, 120, 40, block="btn_primary")
    node, _ = infer_one([b], mapping={"blocks": {"btn_primary": "custom_button"}})
    assert node.type == "custom_button"
    assert node.confidence == 0.95


def test_keyword_on_block_name():
    b = box(100, 100, 120, 40, block="card_task")
    node, _ = infer_one([b])
    assert node.type == "card"
    assert any(h.startswith("keyword:block") for h in node.hints)


def test_keyword_on_layer_name():
    b = box(100, 100, 120, 40, layer="NAV-BAR")
    node, _ = infer_one([b])
    assert node.type == "nav"


def test_label_text_is_not_used_for_keywords():
    b = box(100, 100, 150, 100)                    # 非按钮尺寸，避免尺寸启发式掩盖结论
    t = text_el(110, 110, 100, 20, "确认密码")
    node, _ = infer_one([b, t])
    assert node.type == "unknown"
    assert not any(h.startswith("keyword") for h in node.hints)


def test_size_heuristic_button():
    b = box(100, 100, 120, 40)
    t = text_el(110, 110, 60, 16, "确认")
    node, _ = infer_one([b, t])
    assert node.type == "button"
    assert node.confidence == 0.5


def test_threshold_scales_with_width():
    b = box(50, 50, 60, 20)
    t = text_el(55, 55, 40, 10, "确认")
    node, warnings = infer_one([b, t], width=720)   # 60px 在 720 宽下 = 120 参考 px
    assert node.type == "button"


def test_unknown_gets_low_confidence_and_warning():
    b = box(600, 600, 260, 60, text="随便")
    node, warnings = infer_one([b])
    assert node.type == "unknown"
    assert node.confidence == 0.2


def test_unknown_block_warning():
    b = box(600, 600, 260, 60, block="weird_thing")
    _, warnings = infer_one([b])
    assert any(w.code == "UNKNOWN_BLOCK" for w in warnings)


def test_empty_label_warning_for_button():
    b = box(100, 100, 120, 40, block="btn_primary")
    _, warnings = infer_one([b])
    assert any(w.code == "EMPTY_LABEL" for w in warnings)


def test_mapping_file_formats(tmp_path):
    import json
    p = tmp_path / "m.json"
    p.write_text(json.dumps({"blocks": {"b": "button"}, "layers": {"C": "card"}}),
                 encoding="utf-8")
    m = load_mapping(p)
    assert m["blocks"]["b"] == "button"
    assert m["layers"]["C"] == "card"

    p2 = tmp_path / "flat.json"
    p2.write_text(json.dumps({"btn_x": "button"}), encoding="utf-8")
    assert load_mapping(p2)["blocks"]["btn_x"] == "button"


def test_keyword_type_is_case_insensitive():
    assert keyword_type("BTN_PRIMARY") == "button"
    assert keyword_type("随便") is None
