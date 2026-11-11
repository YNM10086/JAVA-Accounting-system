from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from fnmatch import fnmatchcase
from pathlib import Path

import ezdxf

from core.errors import ConverterUnavailable, ParseError

SKIP_TYPES = frozenset({"DIMENSION", "LEADER", "MULTILEADER", "ACAD_TABLE"})
SKIP_LAYERS = ("Defpoints",)

CONVERTER_HINT = (
    "未找到 DWG 转换器。任选其一：\n"
    "  1) 安装 ODA File Converter（免费）：https://www.opendesign.com/guestfiles/oda_file_converter\n"
    "  2) 在 CAD 里「另存为」DXF，然后直接传 .dxf 文件"
)


@dataclass
class LoadedDrawing:
    doc: ezdxf.document.Drawing
    via: str
    entity_count: int


def match_layer(name: str, patterns: list[str]) -> bool:
    # CAD 图层名大小写不敏感；统一转小写再匹配，保证 Windows/Linux 行为一致
    low = name.lower()
    return any(fnmatchcase(low, p.lower()) for p in patterns)


def _read_dxf(path: Path) -> ezdxf.document.Drawing:
    try:
        return ezdxf.readfile(str(path))
    except FileNotFoundError:
        raise ParseError(f"文件不存在：{path}") from None
    except ezdxf.DXFStructureError as exc:
        raise ParseError(f"DXF 结构损坏：{exc}") from exc


def _convert_dwg(src: Path, work_dir: Path, keep_dxf: bool):
    target = work_dir / f"{src.stem}.cad2ui.dxf"
    try:
        from ezdxf.addons import odafc
        if odafc.is_installed():
            doc = odafc.readfile(str(src))
            if keep_dxf:
                doc.saveas(str(target))
            return doc
    except ImportError:
        pass

    exe = shutil.which("dwg2dxf")
    if exe:
        target.parent.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run([exe, "-y", "-o", str(target), str(src)],
                              capture_output=True, text=True)
        if proc.returncode == 0 and target.exists():
            doc = _read_dxf(target)
            if not keep_dxf:
                target.unlink(missing_ok=True)
            return doc

    raise ConverterUnavailable(CONVERTER_HINT)


def load_drawing(path: Path, work_dir: Path, keep_dxf: bool = False) -> LoadedDrawing:
    path = Path(path)
    suffix = path.suffix.lower()
    work_dir.mkdir(parents=True, exist_ok=True)

    if suffix == ".dxf":
        doc, via = _read_dxf(path), "dxf"
    elif suffix == ".dwg":
        doc, via = _convert_dwg(path, work_dir, keep_dxf), "dwg->dxf"
    else:
        raise ConverterUnavailable(
            f"不支持的输入类型 {suffix or '(无扩展名)'}；只接受 .dwg / .dxf。\n{CONVERTER_HINT}"
        )

    return LoadedDrawing(doc=doc, via=via, entity_count=len(doc.modelspace()))


def select_entities(doc, layers: list[str] | None, ignore_layers: list[str] | None):
    keep = []
    skip_layers = list(SKIP_LAYERS) + list(ignore_layers or [])
    for e in doc.modelspace():
        etype = e.dxftype()
        if etype in SKIP_TYPES:
            continue
        layer = e.dxf.get("layer", "0")
        if match_layer(layer, skip_layers):
            continue
        if layers and not match_layer(layer, layers):
            continue
        keep.append(e)
    return keep
