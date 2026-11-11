from pathlib import Path

import ezdxf


def new_doc():
    doc = ezdxf.new("R2010")
    doc.layers.add("UI-FRAME", color=7)
    doc.layers.add("UI-BOX", color=5)
    doc.layers.add("UI-TEXT", color=7)
    doc.layers.add("UI-LINE", color=8)
    doc.layers.add("UI-FILL", color=9)
    return doc


def add_rect(msp, x, y, w, h, layer="UI-BOX", color=256):
    return msp.add_lwpolyline(
        [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
        close=True, dxfattribs={"layer": layer, "color": color},
    )


def add_text(msp, text, x, y, height=16.0, layer="UI-TEXT", halign=0):
    attrs = {"layer": layer, "height": height, "insert": (x, y)}
    if halign:
        attrs["halign"] = halign
    return msp.add_text(text, dxfattribs=attrs)


def make_login_dxf(path: Path) -> Path:
    """一个卡片 + 标题 + 按钮 + 一个背景填充。"""
    doc = new_doc()
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (1440, 0), (1440, 900), (0, 900)],
                       close=True, dxfattribs={"layer": "UI-FRAME"})
    add_rect(msp, 100, 100, 400, 300)                       # 卡片
    add_text(msp, "登录", 120, 350, height=28)               # 卡片标题
    add_rect(msp, 130, 200, 160, 40)                        # 按钮
    add_text(msp, "确认", 150, 210, height=16)               # 按钮文字
    msp.add_solid([(0, 0), (1440, 0), (1440, 900), (0, 900)],
                  dxfattribs={"layer": "UI-FILL"})          # 背景
    doc.saveas(path)
    return path


def make_block_dxf(path: Path) -> Path:
    """图块 + ATTRIB + 旋转插入。"""
    doc = new_doc()
    blk = doc.blocks.new(name="btn_primary")
    blk.add_lwpolyline([(0, 0), (120, 0), (120, 40), (0, 40)], close=True)
    blk.add_attdef("LABEL", (10, 12), "确定", dxfattribs={"height": 14})
    msp = doc.modelspace()
    ins = msp.add_blockref("btn_primary", (200, 200))
    ins.add_attrib("LABEL", "确定", dxfattribs={"height": 14})
    msp.add_blockref("btn_primary", (600, 100), dxfattribs={"rotation": -90, "xscale": 2})
    doc.saveas(path)
    return path


def make_warning_dxf(path: Path) -> Path:
    """制造 UNCLOSED_POLYLINE / TEXT_OUTSIDE_BOX / OVERLAP / CUT_CORNER_BOX / COLOR_APPROX。"""
    doc = new_doc()
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (100, 0), (100, 50)])                  # 未闭合
    add_text(msp, "孤立文字", 500, 500)                                  # 框外文字
    add_rect(msp, 700, 700, 200, 100)                                    # 重叠 A
    add_rect(msp, 800, 750, 200, 100)                                    # 重叠 B
    msp.add_lwpolyline([(0, 300), (100, 300), (100, 380), (80, 400), (0, 400)],
                       close=True, dxfattribs={"layer": "UI-BOX"})      # 5 顶点切角
    add_rect(msp, 300, 300, 50, 50, color=200)                           # 非标准色
    doc.saveas(path)
    return path


def make_text_only_dxf(path: Path) -> Path:
    doc = new_doc()
    msp = doc.modelspace()
    add_text(msp, "只有文字", 10, 10)
    doc.saveas(path)
    return path


def make_empty_dxf(path: Path) -> Path:
    doc = new_doc()
    doc.saveas(path)
    return path
