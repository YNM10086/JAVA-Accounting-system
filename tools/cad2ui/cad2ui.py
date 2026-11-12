from __future__ import annotations

import argparse
from pathlib import Path

from core.computed import apply_computed
from core.dxf_io import load_drawing
from core.errors import Cad2UiError
from core.extract import extract
from core.infer import apply_types
from core.layout import assign_ids, build_tree
from core.model import Layout
from core.render_html import render_html
from core.render_png import render_png
from core.report import render_report
from core.serialize import dump_json, layout_to_dict
from core.style import load_mapping


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="cad2ui",
        description="把 CAD（DWG/DXF）界面草图转成 AI 可读的布局树与 HTML 预览",
    )
    p.add_argument("input", type=Path, help="输入文件（.dwg 或 .dxf）")
    p.add_argument("-o", "--out", type=Path, default=None, help="输出目录，默认 out/<文件名>")
    p.add_argument("--width", type=int, default=1440, help="画布宽度 px，默认 1440")
    p.add_argument("--map", dest="map_file", type=Path, default=None,
                   help="外部映射表 JSON（块名/图层名 → 组件类型）")
    p.add_argument("--layers", default=None, help="只读这些图层，逗号分隔，支持 UI-* 通配")
    p.add_argument("--ignore-layers", default=None, help="跳过这些图层，逗号分隔")
    p.add_argument("--title", default=None, help="页面标题，默认取文件名")
    p.add_argument("--no-png", action="store_true", help="跳过 PNG 渲染")
    p.add_argument("--keep-dxf", action="store_true", help="保留 DWG 转换出的中间 DXF")
    p.add_argument("--quiet", action="store_true", help="只输出错误")
    return p


def _split(value: str | None) -> list[str] | None:
    if not value:
        return None
    return [part.strip() for part in value.split(",") if part.strip()]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ns = build_parser().parse_args(argv)
    ns.layers = _split(ns.layers)
    ns.ignore_layers = _split(ns.ignore_layers)
    return ns


def _stats(nodes) -> dict:
    counts: dict[str, int] = {}
    for node in nodes:
        counts[node.kind] = counts.get(node.kind, 0) + 1
    counts["total"] = len(nodes)
    return counts


def run(ns: argparse.Namespace) -> int:
    src = Path(ns.input)
    out_dir = Path(ns.out) if ns.out else Path("out") / src.stem

    loaded = load_drawing(src, out_dir, keep_dxf=ns.keep_dxf)
    elements, warnings, canvas = extract(loaded, width=ns.width,
                                         layers=ns.layers,
                                         ignore_layers=ns.ignore_layers)
    tree = build_tree(elements, warnings)
    flat = assign_ids(tree)

    mapping = load_mapping(ns.map_file) if ns.map_file else {"blocks": {}, "layers": {}}
    apply_types(tree, mapping, canvas.width, warnings)
    apply_computed(tree)

    layout = Layout(
        title=ns.title or src.stem,
        canvas=canvas,
        tree=tree,
        nodes=flat,
        warnings=warnings,
        stats=_stats(flat),
    )
    source = {"file": src.name, "via": loaded.via, "entityCount": loaded.entity_count}

    dump_json(layout_to_dict(layout, source), out_dir / "layout.json")
    render_html(layout, source, out_dir / "preview.html")
    if not ns.no_png:
        render_png(layout, out_dir / "preview.png", warnings)
    render_report(layout, source, out_dir / "report.md")

    if not ns.quiet:
        print(f"完成：{out_dir}")
        print(f"  节点 {len(flat)} / 实体 {loaded.entity_count} / warnings {len(warnings)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ns = parse_args(argv)
    try:
        return run(ns)
    except Cad2UiError as exc:
        print(f"错误：{exc}")
        return exc.exit_code
    except Exception as exc:                     # 兜底，不让用户看裸 traceback
        print(f"未预期错误：{type(exc).__name__}: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
