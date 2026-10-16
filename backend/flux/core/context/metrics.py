"""投喂度量（FLUX_CONTEXT_MECHANISM §5.6）。

§5.6 列了六个指标，其中两个是**硬红线**，其余是"越省越好"的观测值：

| 指标 | 目标 |
| --- | --- |
| 压缩率 | —（投喂体积 / 原文体积） |
| 必留项命中率 | 100%（永不压缩清单一条不漏） |
| 重建一致率 | 恒 100%（S5 校验通过率，非 100% 即 bug） |
| fetch 回补率 | 越低越好（运行时观测，见 §6.7 / C4） |
| 目录行长度 | 受控（§6.4，在目录渲染处量） |
| 去重命中率 | 越高越省（§3.5 内容寻址的实际收益） |

本模块接的是前三个**能在打包时算出来**的：调用方只报原始计数，换算与红线判定收在
一处，免得"压缩率"在打包器、观测端、测试里各算一遍除法而口径漂移。

后三个要么在别处量（目录行长度在 `catalog`），要么要运行时累积（回补率、去重命中率），
本模块不假装能算它们。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class FeedMetrics:
    """一次投喂的原始计数（字段单位一律为字符数，与 §6.1 的预算同口径）。"""

    source_chars: int
    fed_chars: int
    required_total: int
    required_kept: int
    verified: int
    verified_ok: int
    dropped_count: int

    @property
    def compression_ratio(self) -> float:
        """投喂体积 / 原文体积；原文为空时记 1.0（没压也没胀）。"""
        if self.source_chars <= 0:
            return 1.0
        return self.fed_chars / self.source_chars

    @property
    def required_hit_rate(self) -> float:
        """必留项命中率：永不压缩清单一条不漏（§5.4）。"""
        if self.required_total <= 0:
            return 1.0
        return self.required_kept / self.required_total

    @property
    def reconstruction_rate(self) -> float:
        """重建一致率：S5 校验通过率，非 100% 即 bug（§5.6）。"""
        if self.verified <= 0:
            return 1.0
        return self.verified_ok / self.verified

    @property
    def healthy(self) -> bool:
        """两条硬红线都达标：必留项一条不漏、S5 一次不差。"""
        return self.required_hit_rate >= 1.0 and self.reconstruction_rate >= 1.0

    def as_dict(self) -> dict[str, float | int | bool]:
        """拍平成可直接塞进 `context.get` 返回值 / 日志的观测字段。"""
        return {
            "source_chars": self.source_chars,
            "fed_chars": self.fed_chars,
            "compression_ratio": round(self.compression_ratio, 4),
            "required_total": self.required_total,
            "required_kept": self.required_kept,
            "required_hit_rate": round(self.required_hit_rate, 4),
            "verified": self.verified,
            "verified_ok": self.verified_ok,
            "reconstruction_rate": round(self.reconstruction_rate, 4),
            "dropped_count": self.dropped_count,
            "healthy": self.healthy,
        }
