from __future__ import annotations

import ctypes
import json
import os
import re
from ctypes import wintypes
from datetime import datetime
from pathlib import Path
from typing import Any


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _blob(data: bytes) -> tuple[_DataBlob, Any]:
    buffer = ctypes.create_string_buffer(data)
    return _DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte))), buffer


def _protect(data: bytes) -> bytes:
    if os.name != "nt":
        raise RuntimeError("Credential encryption requires Windows DPAPI")
    source, source_buffer = _blob(data)
    entropy, entropy_buffer = _blob(b"QihangOMS.MarketIntel.v1")
    output = _DataBlob()
    if not ctypes.windll.crypt32.CryptProtectData(
        ctypes.byref(source), "Qihang OMS", ctypes.byref(entropy), None, None, 0x1,
        ctypes.byref(output),
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(output.pbData)
        del source_buffer, entropy_buffer


def _unprotect(data: bytes) -> bytes:
    if os.name != "nt":
        raise RuntimeError("Credential decryption requires Windows DPAPI")
    source, source_buffer = _blob(data)
    entropy, entropy_buffer = _blob(b"QihangOMS.MarketIntel.v1")
    output = _DataBlob()
    if not ctypes.windll.crypt32.CryptUnprotectData(
        ctypes.byref(source), None, ctypes.byref(entropy), None, None, 0x1,
        ctypes.byref(output),
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(output.pbData)
        del source_buffer, entropy_buffer


class CredentialStore:
    def __init__(self, path: Path | None = None, provider: str = "xiaohongshu",
                 account_id: str = "default"):
        root = Path(os.getenv("LOCALAPPDATA", Path.home())) / "QihangOMS" / "market-intel"
        self.provider = _identifier(provider)
        self.account_id = _identifier(account_id)
        self.path = path or root / self.provider / f"{self.account_id}.bin"
        self.last_error: str | None = None

    def load(self) -> dict[str, Any] | None:
        self.last_error = None
        if not self.path.exists():
            return None
        try:
            return json.loads(_unprotect(self.path.read_bytes()).decode("utf-8"))
        except Exception as exc:
            self.last_error = type(exc).__name__
            return None

    def save(self, cookie: str, nickname: str = "", red_id: str = "") -> None:
        if not cookie or "web_session=" not in cookie:
            raise ValueError("authenticated Cookie is missing web_session")
        payload = json.dumps({
            "cookie": cookie,
            "nickname": nickname,
            "red_id": red_id,
            "provider": self.provider,
            "account_id": self.account_id,
            "saved_at": datetime.now().isoformat(timespec="seconds"),
        }, ensure_ascii=False).encode("utf-8")
        encrypted = _protect(payload)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_bytes(encrypted)
        os.replace(temporary, self.path)

    def delete(self) -> None:
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


def mask_nickname(value: str) -> str:
    text = (value or "").strip()
    if len(text) <= 1:
        return text
    if len(text) == 2:
        return text[0] + "*"
    return text[0] + "*" * min(3, len(text) - 2) + text[-1]


def _identifier(value: str) -> str:
    text = (value or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,49}", text):
        raise ValueError("credential namespace is invalid")
    return text
