from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Rect:
    x: float
    y: float
    w: float
    h: float

    @property
    def right(self) -> float:
        return self.x + self.w

    @property
    def bottom(self) -> float:
        return self.y + self.h

    @property
    def area(self) -> float:
        return max(0.0, self.w) * max(0.0, self.h)


@dataclass
class Style:
    fill: str | None = None
    stroke: str | None = None
    color: str | None = None
    font_size: float | None = None
    radius: float = 0.0
    rotation: float = 0.0


@dataclass
class Element:
    kind: str                       # box | line | text | circle | fill | block
    handle: str
    entity_type: str
    layer: str
    rect: Rect                      # 画布坐标（px）
    ref_rect: Rect                  # 参考坐标（归一化到 1440 宽）
    text: str | None = None
    block: str | None = None
    points: list[tuple[float, float]] | None = None
    style: Style = field(default_factory=Style)
    z: int = 0
    is_rect: bool = True


@dataclass
class Warning:
    code: str
    handle: str
    entity_type: str
    rect: Rect | None
    message: str


@dataclass
class Canvas:
    width: int
    height: int
    scale: float


@dataclass
class Node:
    kind: str
    type: str = "unknown"
    label: str | None = None
    text: str | None = None          # 原始文字内容（text 节点必填）
    handle: str = ""                 # DXF 实体句柄，warning 定位用
    entity_type: str = ""
    layer: str = ""
    block: str | None = None
    rect: Rect = field(default_factory=lambda: Rect(0, 0, 0, 0))
    style: Style = field(default_factory=Style)
    points: list[tuple[float, float]] | None = None
    z: int = 0
    confidence: float = 0.0
    hints: list[str] = field(default_factory=list)
    computed: dict | None = None
    computed_confidence: float | None = None
    children: list["Node"] = field(default_factory=list)
    parent_id: str | None = None
    depth: int = 0
    id: str = ""


@dataclass
class Layout:
    title: str
    canvas: Canvas
    tree: list[Node]
    nodes: list[Node]
    warnings: list[Warning]
    stats: dict
