import json
from pathlib import Path

from cad2ui import main
from tests.fixtures import make_empty_dxf, make_login_dxf


def run_login(tmp_path, *extra):
    src = make_login_dxf(tmp_path / "login.dxf")
    out = tmp_path / "out"
    code = main([str(src), "-o", str(out), *extra])
    return code, out


def test_success_produces_four_files(tmp_path):
    code, out = run_login(tmp_path)
    assert code == 0
    for name in ("layout.json", "preview.html", "preview.png", "report.md"):
        assert (out / name).exists(), name


def test_json_tree_and_flat_index_agree(tmp_path):
    code, out = run_login(tmp_path)
    data = json.loads((out / "layout.json").read_text(encoding="utf-8"))

    def count(nodes):
        return sum(1 + count(n.get("children", [])) for n in nodes)

    assert count(data["tree"]) == len(data["nodes"])
    ids = {n["id"] for n in data["nodes"]}
    for n in data["nodes"]:
        assert n["parentId"] is None or n["parentId"] in ids


def test_runs_are_byte_identical(tmp_path):
    src = make_login_dxf(tmp_path / "login.dxf")
    out1, out2 = tmp_path / "o1", tmp_path / "o2"
    assert main([str(src), "-o", str(out1)]) == 0
    assert main([str(src), "-o", str(out2)]) == 0
    assert (out1 / "layout.json").read_bytes() == (out2 / "layout.json").read_bytes()


def test_no_png(tmp_path):
    code, out = run_login(tmp_path, "--no-png")
    assert code == 0
    assert not (out / "preview.png").exists()


def test_layers_whitelist(tmp_path):
    src = make_login_dxf(tmp_path / "login.dxf")
    out = tmp_path / "out"
    assert main([str(src), "-o", str(out), "--layers", "UI-FRAME"]) == 0
    data = json.loads((out / "layout.json").read_text(encoding="utf-8"))
    assert [n["layer"] for n in data["nodes"]] == ["UI-FRAME"]


def test_empty_drawing_exit_code_4(tmp_path):
    src = make_empty_dxf(tmp_path / "e.dxf")
    assert main([str(src), "-o", str(tmp_path / "out")]) == 4


def test_missing_file_exit_code_3(tmp_path):
    assert main([str(tmp_path / "nope.dxf"), "-o", str(tmp_path / "out")]) == 3


def test_unsupported_suffix_exit_code_2(tmp_path):
    p = tmp_path / "a.pdf"
    p.write_text("x", encoding="utf-8")
    assert main([str(p), "-o", str(tmp_path / "out")]) == 2


def test_canvas_width_flag(tmp_path):
    code, out = run_login(tmp_path, "--width", "720")
    data = json.loads((out / "layout.json").read_text(encoding="utf-8"))
    assert data["canvas"]["width"] == 720
    assert data["canvas"]["height"] == 450


def test_default_output_dir(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    src = make_login_dxf(tmp_path / "login.dxf")
    assert main([str(src)]) == 0
    assert (tmp_path / "out" / "login" / "layout.json").exists()
