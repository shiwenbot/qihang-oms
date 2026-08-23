from __future__ import annotations

import re
import sys
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

from collector import SpiderCollector


IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9_-]{0,49}$")


class PlatformAdapter(ABC):
    provider: str
    display_name: str

    @abstractmethod
    def create_collector(self, cookie: str, minimum_interval: float,
                         maximum_interval: float) -> Any:
        raise NotImplementedError

    @abstractmethod
    def create_login_client(self) -> Any:
        raise NotImplementedError

    def capabilities(self) -> dict[str, bool]:
        return {"qr_login": True, "keyword_search": True, "profile_notes": True}


class XiaohongshuAdapter(PlatformAdapter):
    provider = "xiaohongshu"
    display_name = "小红书"

    def __init__(self, root: Path | None = None):
        self.root = root or Path(__file__).parent / "vendor" / "Spider_XHS"

    def create_collector(self, cookie: str, minimum_interval: float,
                         maximum_interval: float) -> SpiderCollector:
        return SpiderCollector(cookie, minimum_interval, maximum_interval)

    def create_login_client(self) -> Any:
        if not (self.root / "apis" / "xhs_pc_login_apis.py").exists():
            raise RuntimeError("Spider_XHS 未安装")
        root = str(self.root)
        if root not in sys.path:
            sys.path.insert(0, root)
        from apis.xhs_pc_login_apis import XHSLoginApi

        return XHSLoginApi()


class PlatformRegistry:
    def __init__(self, adapters: list[PlatformAdapter]):
        self._adapters = {adapter.provider: adapter for adapter in adapters}

    def get(self, provider: str) -> PlatformAdapter:
        key = validate_identifier(provider, "provider")
        try:
            return self._adapters[key]
        except KeyError as exc:
            raise ValueError(f"不支持的平台: {key}") from exc

    def public(self) -> list[dict[str, Any]]:
        return [
            {"provider": adapter.provider, "name": adapter.display_name,
             "capabilities": adapter.capabilities()}
            for adapter in self._adapters.values()
        ]


def validate_identifier(value: str, field: str) -> str:
    text = (value or "").strip().lower()
    if not IDENTIFIER.fullmatch(text):
        raise ValueError(f"{field}格式无效")
    return text


registry = PlatformRegistry([XiaohongshuAdapter()])
