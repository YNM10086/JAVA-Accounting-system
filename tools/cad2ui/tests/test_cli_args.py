from pathlib import Path

from cad2ui import parse_args


def test_defaults():
    ns = parse_args(["plan.dxf"])
    assert ns.input == Path("plan.dxf")
    assert ns.width == 1440
    assert ns.out is None
    assert ns.no_png is False
    assert ns.layers is None


def test_layer_lists_are_split_and_stripped():
    ns = parse_args(["plan.dxf", "--layers", " UI-* , CARD ", "--ignore-layers", "DIM"])
    assert ns.layers == ["UI-*", "CARD"]
    assert ns.ignore_layers == ["DIM"]


def test_all_flags():
    ns = parse_args(["a.dwg", "-o", "out/x", "--width", "720", "--map", "m.json",
                     "--title", "登录", "--no-png", "--keep-dxf", "--quiet"])
    assert ns.width == 720
    assert ns.title == "登录"
    assert ns.no_png and ns.keep_dxf and ns.quiet
    assert ns.map_file == Path("m.json")
