from __future__ import annotations

import json
from pathlib import Path

LAYER_CONTRACT: dict[str, str] = {
    "UI-FRAME": "frame",
    "UI-TEXT": "text",
    "UI-LINE": "line",
    "UI-FILL": "fill",
}

# 顺序即优先级：靠前的先命中
KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("button", ("btn", "button", "按钮", "确认", "取消", "submit")),
    ("input", ("input", "field", "输入", "搜索", "search", "password")),
    ("select", ("select", "dropdown", "下拉")),
    ("checkbox", ("checkbox", "复选")),
    ("switch", ("switch", "开关")),
    ("slider", ("slider", "滑块")),
    ("tab", ("tab", "标签页")),
    ("table", ("table", "表格", "grid")),
    ("chart", ("chart", "图表", "折线", "chart")),
    ("nav", ("nav", "菜单", "导航", "sidebar")),
    ("header", ("header", "标题栏", "topbar")),
    ("footer", ("footer", "底栏")),
    ("modal", ("modal", "dialog", "弹窗")),
    ("card", ("card", "卡片", "panel")),
    ("list", ("list", "列表")),
    ("avatar", ("avatar", "头像")),
    ("badge", ("badge", "角标")),
    ("logo", ("logo",)),
    ("icon", ("icon", "图标")),
    ("image", ("image", "img", "图片", "banner")),
)


def load_mapping(path: Path) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise ValueError("映射表必须是 JSON 对象")
    if "blocks" in data or "layers" in data:
        return {
            "blocks": dict(data.get("blocks") or {}),
            "layers": dict(data.get("layers") or {}),
        }
    return {"blocks": dict(data), "layers": {}}


def keyword_type(name: str) -> str | None:
    if not name:
        return None
    low = name.lower()
    for type_name, words in KEYWORDS:
        for word in words:
            if word.lower() in low:
                return type_name
    return None
