class Cad2UiError(Exception):
    exit_code = 1


class ConverterUnavailable(Cad2UiError):
    """DWG 转换器不存在或转换失败。"""
    exit_code = 2


class ParseError(Cad2UiError):
    """DXF 读不出来。"""
    exit_code = 3


class NoGeometry(Cad2UiError):
    """图纸里没有可用实体。"""
    exit_code = 4
