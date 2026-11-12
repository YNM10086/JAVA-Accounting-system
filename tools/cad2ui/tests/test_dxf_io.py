from pathlib import Path

import pytest

from core.dxf_io import load_drawing, match_layer, select_entities
from core.errors import ConverterUnavailable, ParseError
from tests.fixtures import make_empty_dxf, make_login_dxf


def test_match_layer_supports_glob():
    assert match_layer("UI-BOX", ["UI-*"]) is True
    assert match_layer("CARD", ["UI-*"]) is False
    assert match_layer("Defpoints", ["defpoints"]) is True    # CAD 图层名大小写不敏感
    assert match_layer("UI-BOX", []) is False


def test_load_dxf_sets_via(tmp_path):
    path = make_login_dxf(tmp_path / "login.dxf")
    loaded = load_drawing(path, tmp_path)
    assert loaded.via == "dxf"
    assert loaded.entity_count > 0


def test_missing_file_raises_parse_error(tmp_path):
    with pytest.raises((ParseError, FileNotFoundError)):
        load_drawing(tmp_path / "nope.dxf", tmp_path)


def test_non_dxf_content_raises_parse_error(tmp_path):
    p = tmp_path / "bad.dxf"
    p.write_bytes(b"\x00\x01not a dxf")
    with pytest.raises(ParseError):
        load_drawing(p, tmp_path)


def test_unsupported_suffix(tmp_path):
    p = tmp_path / "a.txt"
    p.write_text("x", encoding="utf-8")
    with pytest.raises(ConverterUnavailable):
        load_drawing(p, tmp_path)


def test_select_entities_skips_default_layers_and_types(tmp_path):
    path = make_empty_dxf(tmp_path / "empty.dxf")
    loaded = load_drawing(path, tmp_path)
    assert select_entities(loaded.doc, None, None) == []


def test_select_entities_layer_whitelist(tmp_path):
    path = make_login_dxf(tmp_path / "login.dxf")
    loaded = load_drawing(path, tmp_path)
    ents = select_entities(loaded.doc, ["UI-FRAME"], None)
    assert len(ents) == 1
    assert ents[0].dxf.layer == "UI-FRAME"
