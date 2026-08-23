from __future__ import annotations

import random
import sys
import threading
import time
from urllib.parse import parse_qs, urlparse
from datetime import datetime
from pathlib import Path
from typing import Any, Callable


def parse_count(value: Any) -> int:
    if value is None or value == "":
        return 0
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        return max(0, int(value))
    text = str(value).strip().replace(",", "").replace("+", "")
    multiplier = 1
    if text.endswith("万"):
        multiplier, text = 10_000, text[:-1]
    elif text.lower().endswith("w"):
        multiplier, text = 10_000, text[:-1]
    elif text.endswith("亿"):
        multiplier, text = 100_000_000, text[:-1]
    try:
        return max(0, int(float(text) * multiplier))
    except (TypeError, ValueError):
        return 0


def _first(mapping: dict[str, Any], *paths: str, default: Any = None) -> Any:
    for path in paths:
        value: Any = mapping
        for part in path.split("."):
            if not isinstance(value, dict) or part not in value:
                value = None
                break
            value = value[part]
        if value not in (None, "", []):
            return value
    return default


def _cover(card: dict[str, Any]) -> str | None:
    direct = _first(card, "cover.url_default", "cover.url", "display_title")
    if isinstance(direct, str) and direct.startswith("http"):
        return direct
    images = _first(card, "image_list", "note_card.image_list", default=[])
    if isinstance(images, list) and images:
        image = images[0] if isinstance(images[0], dict) else {}
        infos = image.get("info_list") or []
        if infos and isinstance(infos[-1], dict):
            return infos[-1].get("url")
        return image.get("url_default") or image.get("url")
    return None


def _published(value: Any) -> str | None:
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):
        seconds = float(value) / 1000 if value > 10_000_000_000 else float(value)
        return datetime.fromtimestamp(seconds).strftime("%Y-%m-%dT%H:%M:%S")
    text = str(value).strip().replace(" ", "T")
    return text[:19] if len(text) >= 19 else None


def normalize_note(raw: dict[str, Any], source: str, keywords: list[str]) -> dict[str, Any]:
    card = raw.get("note_card") if isinstance(raw.get("note_card"), dict) else raw
    interact = card.get("interact_info") if isinstance(card.get("interact_info"), dict) else {}
    user = card.get("user") if isinstance(card.get("user"), dict) else {}
    note_id = _first(raw, "id", "note_id", "note_card.note_id", "note_card.id")
    user_id = _first(card, "user.user_id", "user.userid", "user_id")
    nickname = _first(card, "user.nickname", "nickname")
    if not note_id or not user_id or not nickname:
        raise ValueError("note_id/user_id/nickname missing")
    xsec_token = _first(raw, "xsec_token", "note_card.xsec_token")
    note_url = _first(raw, "url", "note_url") or f"https://www.xiaohongshu.com/explore/{note_id}"
    if xsec_token and "xsec_token=" not in note_url:
        note_url += ("&" if "?" in note_url else "?") + f"xsec_token={xsec_token}&xsec_source=pc_search"
    return {
        "note_id": str(note_id),
        "source": source,
        "keywords": keywords,
        "title": str(_first(card, "display_title", "title", "desc", default=""))[:500],
        "note_url": note_url,
        "cover_url": _cover(card),
        "user_id": str(user_id),
        "nickname": str(nickname),
        "liked_count": parse_count(_first(interact, "liked_count", "like_count", default=0)),
        "collected_count": parse_count(_first(interact, "collected_count", "collect_count", default=0)),
        "comment_count": parse_count(_first(interact, "comment_count", default=0)),
        "published_at": _published(_first(card, "time", "last_update_time", "upload_time")),
    }


def normalize_user(raw: dict[str, Any], user_id: str) -> dict[str, Any]:
    data = raw.get("data") if isinstance(raw.get("data"), dict) else raw
    basic = data.get("basic_info") if isinstance(data.get("basic_info"), dict) else data
    interactions = data.get("interactions") if isinstance(data.get("interactions"), list) else []
    by_type = {str(x.get("type", "")): x.get("count", 0) for x in interactions if isinstance(x, dict)}
    follows = by_type.get("follows", interactions[0].get("count", 0) if len(interactions) > 0 else 0)
    fans = by_type.get("fans", interactions[1].get("count", 0) if len(interactions) > 1 else 0)
    return {
        "user_id": user_id,
        "nickname": _first(basic, "nickname", default=""),
        "red_id": _first(basic, "red_id", "redId"),
        "avatar_url": _first(basic, "imageb", "images", "avatar"),
        "fans": parse_count(fans),
        "follows": parse_count(follows),
        "last_note_id": None,
    }


class SpiderCollector:
    def __init__(self, cookie: str, minimum_interval: float, maximum_interval: float):
        self.cookie = cookie
        self.minimum_interval = max(3.0, minimum_interval)
        self.maximum_interval = max(self.minimum_interval, maximum_interval)
        self._api: Any = None
        self._last_request = 0.0
        self._lock = threading.Lock()

    def update_cookie(self, cookie: str) -> None:
        with self._lock:
            old_api = self._api
            self.cookie = cookie
            self._api = None
            if old_api is not None:
                try:
                    old_api.auth.close()
                except Exception:
                    pass

    def _client(self) -> Any:
        if self._api is None:
            if not self.cookie:
                raise RuntimeError("请先连接小红书账号")
            vendor = Path(__file__).parent / "vendor" / "Spider_XHS"
            if not (vendor / "apis" / "xhs_pc_apis.py").exists():
                raise RuntimeError("Spider_XHS missing; run install-spider.bat")
            sys.path.insert(0, str(vendor))
            from apis.xhs_pc_apis import XHS_Apis
            from xhs_utils.xhs_pc import XHSPcAuth

            auth = XHSPcAuth.from_cookie(self.cookie)
            self._api = XHS_Apis(auth).bootstrap()
        return self._api

    def _call(self, function: Callable[..., Any], *args: Any, **kwargs: Any) -> tuple[Any, ...]:
        with self._lock:
            wait = self.minimum_interval - (time.monotonic() - self._last_request)
            if wait > 0:
                time.sleep(wait + random.uniform(0, self.maximum_interval - self.minimum_interval))
            try:
                return function(*args, **kwargs)
            finally:
                self._last_request = time.monotonic()

    def search(self, keyword: str, sort_type: int) -> list[dict[str, Any]]:
        api = self._client()
        ok, message, notes = self._call(api.search_some_note, keyword, 20, sort_type_choice=sort_type)
        if not ok:
            raise RuntimeError(message)
        return [normalize_note(note, "search", [keyword]) for note in notes]

    def profile(self, user_id: str) -> dict[str, Any]:
        api = self._client()
        ok, message, user = self._call(api.get_user_info, user_id)
        if not ok:
            raise RuntimeError(message)
        return normalize_user(user or {}, user_id)

    def competitor(self, user_id: str, profile_url: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        api = self._client()
        profile = self.profile(user_id)
        parsed = urlparse(profile_url)
        params = parse_qs(parsed.query)
        xsec_token = (params.get("xsec_token") or [""])[0]
        xsec_source = (params.get("xsec_source") or ["pc_search"])[0]
        ok, message, response = self._call(
            api.get_user_note_info, user_id, "", xsec_token, xsec_source
        )
        if not ok:
            raise RuntimeError(message)
        notes = ((response or {}).get("data") or {}).get("notes") or []
        normalized = [normalize_note(note, "user", []) for note in (notes or [])[:30]]
        if normalized:
            profile["last_note_id"] = normalized[0]["note_id"]
        return profile, normalized
