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


def resolve_aci(doc, entity) -> tuple[str | None, int, str | None]:
    """返回 (十六进制, ACI, 降级原因)。BYLAYER/BYBLOCK 已解析，颜色为 None 时带原因。"""
    aci = abs(int(entity.dxf.get("color", 256)))

    if aci == 256:
        layer_name = entity.dxf.get("layer", "0")
        try:
            layer = doc.layers.get(layer_name)
        except Exception:
            # 实体引用了不在图层表里的图层（手改 DXF / 部分导出很常见）
            return None, 256, f"图层 {layer_name} 不在图层表里，颜色取近似灰"
        # 负值表示图层关闭，取绝对值才是索引色
        aci = abs(int(layer.dxf.color))

    if aci == 0:
        aci = 7

    color = ACI_PALETTE.get(aci)
    reason = None if color else f"ACI {aci} 超出 0-9 标准色，使用近似灰"
    return color, aci, reason
