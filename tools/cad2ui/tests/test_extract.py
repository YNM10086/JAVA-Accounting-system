import pytest

from core.dxf_io import load_drawing
from core.errors import NoGeometry
from core.extract import extract
from tests.fixtures import (make_empty_dxf, make_login_dxf, make_text_only_dxf,
                            make_warning_dxf)


def test_canvas_width_matches_request(tmp_path):
    loaded = load_drawing(make_login_dxf(tmp_path / "login.dxf"), tmp_path)
    elements, warnings, canvas = extract(loaded, width=720)
    assert canvas.width == 720
    assert canvas.height == pytest.approx(450, abs=1)
    assert canvas.scale == pytest.approx(0.5, abs=1e-3)


def test_kinds_are_mapped(tmp_path):
    loaded = load_drawing(make_login_dxf(tmp_path / "login.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    kinds = {e.kind for e in elements}
    assert {"box", "text", "fill"} <= kinds


def test_text_has_font_size_and_content(tmp_path):
    loaded = load_drawing(make_login_dxf(tmp_path / "login.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    texts = [e for e in elements if e.kind == "text"]
    assert any(e.text == "登录" and e.style.font_size == pytest.approx(28, abs=0.5)
               for e in texts)


def test_rect_uses_canvas_coordinates(tmp_path):
    loaded = load_drawing(make_login_dxf(tmp_path / "login.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    frame = [e for e in elements if e.layer == "UI-FRAME"][0]
    assert frame.rect.x == pytest.approx(0, abs=0.5)
    assert frame.rect.y == pytest.approx(0, abs=0.5)
    assert frame.rect.w == pytest.approx(1440, abs=1)


def test_unclosed_polyline_becomes_line_with_warning(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    assert any(e.kind == "line" for e in elements)
    assert any(w.code == "UNCLOSED_POLYLINE" for w in warnings)


def test_cut_corner_keeps_points(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    cut = [e for e in elements if e.points and len(e.points) == 5]
    assert cut, "5 顶点闭合多段线应保留 points"
    assert any(w.code == "CUT_CORNER_BOX" for w in warnings)


def test_non_standard_color_falls_back(tmp_path):
    loaded = load_drawing(make_warning_dxf(tmp_path / "w.dxf"), tmp_path)
    elements, warnings, _ = extract(loaded)
    approx = [e for e in elements if e.style.stroke == "#888888" or e.style.fill == "#888888"]
    assert approx
    assert any(w.code == "COLOR_APPROX" for w in warnings)


def test_block_keeps_name_and_attrib_text(tmp_path):
    from tests.fixtures import make_block_dxf
    loaded = load_drawing(make_block_dxf(tmp_path / "b.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    blocks = [e for e in elements if e.kind == "block"]
    assert len(blocks) == 2
    assert blocks[0].block == "btn_primary"
    assert "确定" in (blocks[0].text or "")


def test_rotated_insert_bbox_is_not_at_origin(tmp_path):
    from tests.fixtures import make_block_dxf
    loaded = load_drawing(make_block_dxf(tmp_path / "b.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    rotated = [e for e in elements if e.kind == "block"][1]
    assert rotated.rect.x > 100 and rotated.rect.y > 100


def test_text_only_drawing_does_not_crash(tmp_path):
    loaded = load_drawing(make_text_only_dxf(tmp_path / "t.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    assert [e.kind for e in elements] == ["text"]


def test_fill_gets_lowest_z(tmp_path):
    """spec 7.1：SOLID/HATCH 的 z 最低，否则背景会盖住整张图。"""
    loaded = load_drawing(make_login_dxf(tmp_path / "login.dxf"), tmp_path)
    elements, _, _ = extract(loaded)
    fill_z = [e.z for e in elements if e.kind == "fill"]
    other_z = [e.z for e in elements if e.kind != "fill"]
    assert fill_z and other_z
    assert max(fill_z) < min(other_z)


def test_empty_drawing_raises_no_geometry(tmp_path):
    loaded = load_drawing(make_empty_dxf(tmp_path / "e.dxf"), tmp_path)
    with pytest.raises(NoGeometry):
        extract(loaded)
