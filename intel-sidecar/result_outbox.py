from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from credential_store import _protect, _unprotect


class ResultOutbox:
    def __init__(self, root: Path | None = None):
        self.root = root or Path(os.getenv("LOCALAPPDATA", Path.home())) / "QihangOMS" / "market-intel" / "outbox"

    def save(self, job_id: int, payload: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        target = self.root / f"{job_id}.bin"
        temporary = self.root / f"{job_id}.tmp"
        data = json.dumps({"job_id": job_id, "payload": payload}, ensure_ascii=False).encode("utf-8")
        temporary.write_bytes(_protect(data))
        os.replace(temporary, target)

    def delete(self, job_id: int) -> None:
        try:
            (self.root / f"{job_id}.bin").unlink()
        except FileNotFoundError:
            pass

    def pending(self) -> list[tuple[int, dict[str, Any]]]:
        results: list[tuple[int, dict[str, Any]]] = []
        if not self.root.exists():
            return results
        for path in self.root.glob("*.bin"):
            try:
                value = json.loads(_unprotect(path.read_bytes()).decode("utf-8"))
                results.append((int(value["job_id"]), dict(value["payload"])))
            except Exception:
                continue
        return results
