from __future__ import annotations

# ACI 1-9 → 与 CONVENTION.md 一致的语义色（不是原始 RGB，便于前端直接复用）
ACI_PALETTE: dict[int, str] = {
    1: "#F56C6C",   # 红：危险/删除
    2: "#E6A23C",   # 橙：警告
    3: "#67C23A",   # 绿：成功
    4: "#17BECF",   # 青：图表
    5: "#409EFF",   # 蓝：主色
    6: "#B37FEB",   # 紫：强调
    7: "#303133",   # 白/黑：正文
    8: "#909399",   # 灰：次要文字
    9: "#C0C4CC",   # 浅灰：边框/填充
}

FALLBACK = "#888888"


def resolve_aci(doc, entity) -> tuple[str | None, int]:
    """返回 (十六进制, ACI)；BYLAYER/BYBLOCK 已解析。0-9 之外返回 (None, aci)。"""
    aci = int(entity.dxf.get("color", 256))
    if aci == 256:
        layer = doc.layers.get(entity.dxf.get("layer", "0"))
        aci = int(layer.dxf.color)
    if aci == 0:
        aci = 7
    return ACI_PALETTE.get(aci), aci
