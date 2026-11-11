from __future__ import annotations

import argparse
from pathlib import Path

from core.errors import Cad2UiError, NoGeometry


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


def main(argv: list[str] | None = None) -> int:
    ns = parse_args(argv)
    try:
        raise NoGeometry(f"pipeline 尚未接入：{ns.input}")
    except Cad2UiError as exc:
        print(f"错误：{exc}")
        return exc.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
