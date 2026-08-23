from __future__ import annotations

import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from dataclasses import dataclass
from pathlib import Path
from typing import Literal
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import requests
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from collector import SpiderCollector
from auth_manager import QrAuthManager
from credential_store import CredentialStore, mask_nickname
from platform_adapter import PlatformAdapter, registry, validate_identifier
from result_outbox import ResultOutbox

load_dotenv(Path(__file__).with_name(".env"))

TOKEN = os.getenv("TOKEN", "").strip()
ENV_COOKIES = os.getenv("COOKIES", "").strip()
OMS_RESULT_BASE = os.getenv("OMS_RESULT_BASE", "http://127.0.0.1:8086/api/internal/intel/jobs").rstrip("/")
MIN_INTERVAL = float(os.getenv("MIN_REQUEST_INTERVAL", "3"))
MAX_INTERVAL = float(os.getenv("MAX_REQUEST_INTERVAL", "6"))


class Keyword(BaseModel):
    model_config = ConfigDict(extra="forbid")
    keyword: str = Field(min_length=1, max_length=100)
    sort_type: int = Field(default=2, ge=0, le=4)


class Competitor(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: int
    user_id: str = Field(min_length=1, max_length=100)
    profile_url: str = Field(min_length=1, max_length=1000)


class PreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: str = Field(min_length=1, max_length=100)
    profile_url: str = Field(min_length=1, max_length=1000)
    provider: str = "xiaohongshu"
    account_id: str = "default"


class JobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    job_id: int
    keywords: list[Keyword] = Field(max_length=50)
    competitors: list[Competitor]
    request_token: str = Field(min_length=64, max_length=64)
    provider: str = "xiaohongshu"
    account_id: str = "default"


class JobState(BaseModel):
    job_id: int
    status: Literal["pending", "running", "success", "fail"]
    error_count: int = 0
    message: str | None = None


app = FastAPI(title="OMS Market Intel Sidecar", version="0.3.0", docs_url=None, redoc_url=None)
executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="intel-collector")
jobs: dict[int, JobState] = {}
jobs_lock = threading.Lock()
outbox = ResultOutbox()
inbox = ResultOutbox(outbox.root.parent / "inbox")
replay_started = False


@app.exception_handler(ValueError)
def invalid_scope(_: Request, exc: ValueError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": sanitize_error(exc)})


@app.on_event("startup")
def replay_pending_results() -> None:
    global replay_started
    pending_results = outbox.pending()
    completed_ids = {job_id for job_id, _ in pending_results}
    for job_id, payload in inbox.pending():
        if job_id in completed_ids:
            continue
        try:
            request = JobRequest.model_validate(payload)
            set_state(job_id, "pending")
            executor.submit(collect_job, request)
        except Exception:
            continue
    if not replay_started:
        replay_started = True
        threading.Thread(target=retry_outbox_loop, name="intel-result-retry", daemon=True).start()


@dataclass
class ProviderRuntime:
    adapter: PlatformAdapter
    account_id: str
    store: CredentialStore
    collector: SpiderCollector
    auth: QrAuthManager
    cookie_ok: bool
    last_ok_at: str | None = None


class RuntimeManager:
    def __init__(self):
        self._items: dict[tuple[str, str], ProviderRuntime] = {}
        self._lock = threading.Lock()

    def get(self, provider: str = "xiaohongshu", account_id: str = "default") -> ProviderRuntime:
        adapter = registry.get(provider)
        account = validate_identifier(account_id, "account_id")
        key = (adapter.provider, account)
        with self._lock:
            existing = self._items.get(key)
            if existing:
                return existing
            store = CredentialStore(provider=adapter.provider, account_id=account)
            saved = store.load() or {}
            fallback = ENV_COOKIES if key == ("xiaohongshu", "default") else ""
            collector = adapter.create_collector(str(saved.get("cookie") or fallback), MIN_INTERVAL, MAX_INTERVAL)
            runtime = ProviderRuntime(adapter, account, store, collector, None,
                                      bool(saved.get("cookie") or fallback))  # type: ignore[arg-type]

            def apply_cookie(cookie: str) -> None:
                collector.update_cookie(cookie)
                runtime.cookie_ok = bool(cookie)

            runtime.auth = QrAuthManager(store, apply_cookie, adapter.create_login_client)
            self._items[key] = runtime
            return runtime


runtimes = RuntimeManager()


def require_token(authorization: str | None = Header(default=None)) -> None:
    if not TOKEN or authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="unauthorized")


@app.get("/health", dependencies=[Depends(require_token)])
def health(provider: str = "xiaohongshu", account_id: str = "default") -> dict[str, object]:
    runtime = runtimes.get(provider, account_id)
    saved = runtime.store.load() or {}
    return {
        "ok": True,
        "provider": runtime.adapter.provider,
        "account_id": runtime.account_id,
        "providers": registry.public(),
        "cookie_ok": runtime.cookie_ok,
        "credential_present": bool(saved.get("cookie") or (ENV_COOKIES if runtime.account_id == "default" else "")),
        "nickname": mask_nickname(str(saved.get("nickname") or "")),
        "credential_error": runtime.store.last_error,
        "last_ok_at": runtime.last_ok_at,
    }


@app.post("/auth/qrcode/start", status_code=status.HTTP_202_ACCEPTED,
          dependencies=[Depends(require_token)])
def start_qrcode_login(provider: str = "xiaohongshu", account_id: str = "default") -> dict[str, object]:
    return runtimes.get(provider, account_id).auth.start()


@app.get("/auth/qrcode/status", dependencies=[Depends(require_token)])
def qrcode_login_status(session_id: str, provider: str = "xiaohongshu",
                        account_id: str = "default") -> dict[str, object]:
    try:
        return runtimes.get(provider, account_id).auth.status(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="login session not found") from exc


@app.delete("/auth", dependencies=[Depends(require_token)])
def logout(provider: str = "xiaohongshu", account_id: str = "default") -> dict[str, bool]:
    runtime = runtimes.get(provider, account_id)
    runtime.auth.logout()
    runtime.cookie_ok = False
    runtime.last_ok_at = None
    return {"success": True}


@app.post("/competitors/preview", dependencies=[Depends(require_token)])
def preview_competitor(request: PreviewRequest) -> dict[str, object]:
    runtime = runtimes.get(request.provider, request.account_id)
    if not runtime.collector.cookie:
        raise HTTPException(status_code=503, detail="请先连接小红书账号")
    try:
        profile = runtime.collector.profile(request.user_id)
        runtime.cookie_ok = True
        runtime.last_ok_at = datetime.now().isoformat(timespec="seconds")
        return profile
    except Exception as exc:
        runtime.cookie_ok = False
        raise HTTPException(status_code=502, detail=sanitize_error(exc)) from exc


@app.post("/jobs/run", status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(require_token)])
def run_job(request: JobRequest) -> dict[str, int | str]:
    runtime = runtimes.get(request.provider, request.account_id)
    if not runtime.collector.cookie:
        raise HTTPException(status_code=503, detail="请先连接小红书账号")
    with jobs_lock:
        existing = jobs.get(request.job_id)
        if existing and existing.status in ("pending", "running"):
            return {"job_id": request.job_id, "status": existing.status}
        inbox.save(request.job_id, request.model_dump(mode="json"))
        jobs[request.job_id] = JobState(job_id=request.job_id, status="pending")
    executor.submit(collect_job, request)
    return {"job_id": request.job_id, "status": "pending"}


@app.get("/jobs/{job_id}", dependencies=[Depends(require_token)])
def get_job(job_id: int) -> JobState:
    with jobs_lock:
        state = jobs.get(job_id)
    if not state:
        raise HTTPException(status_code=404, detail="job not found")
    return state


def collect_job(request: JobRequest) -> None:
    runtime = runtimes.get(request.provider, request.account_id)
    collector = runtime.collector
    set_state(request.job_id, "running")
    notes: list[dict[str, object]] = []
    competitors: list[dict[str, object]] = []
    errors = 0
    messages: list[str] = []
    for keyword in request.keywords:
        try:
            notes.extend(collector.search(keyword.keyword, keyword.sort_type))
            runtime.cookie_ok = True
            runtime.last_ok_at = datetime.now().isoformat(timespec="seconds")
        except Exception as exc:
            errors += 1
            messages.append(f"keyword {keyword.keyword}: {sanitize_error(exc)}")
    for competitor in request.competitors:
        try:
            profile, user_notes = collector.competitor(competitor.user_id, competitor.profile_url)
            competitors.append(profile)
            notes.extend(user_notes)
            runtime.cookie_ok = True
            runtime.last_ok_at = datetime.now().isoformat(timespec="seconds")
        except Exception as exc:
            errors += 1
            messages.append(f"competitor {competitor.user_id}: {sanitize_error(exc)}")
    final_status = "success" if notes or competitors or (not request.keywords and not request.competitors) else "fail"
    if final_status == "fail" and errors:
        runtime.cookie_ok = False
    payload = {
        "request_token": request.request_token,
        "provider": request.provider,
        "account_id": request.account_id,
        "status": final_status,
        "notes": [sanitize_note_urls(note) for note in notes],
        "competitors": competitors,
        "error_count": errors,
        "error_msg": "; ".join(messages)[:1000] or None,
    }
    outbox.save(request.job_id, payload)
    inbox.delete(request.job_id)
    try:
        deliver_result(request.job_id, payload)
        set_state(request.job_id, final_status, errors, payload["error_msg"])
    except Exception as exc:
        set_state(request.job_id, "fail", errors + 1, f"callback: {sanitize_error(exc)}")
        if not notes:
            runtime.cookie_ok = False


def post_result(job_id: int, payload: dict[str, object]) -> None:
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            response = requests.post(
                f"{OMS_RESULT_BASE}/{job_id}/result",
                json=payload,
                headers={"Authorization": f"Bearer {TOKEN}"},
                timeout=10,
            )
            response.raise_for_status()
            body = response.json()
            if body.get("code") != 200:
                raise RuntimeError(f"OMS rejected result: {body.get('msg', body.get('code'))}")
            return
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(2**attempt)
    raise RuntimeError(str(last_error))


def deliver_result(job_id: int, payload: dict[str, object]) -> None:
    try:
        post_result(job_id, payload)
    except Exception:
        return
    outbox.delete(job_id)


def retry_outbox_loop() -> None:
    while True:
        retry_outbox_once()
        time.sleep(30)


def retry_outbox_once() -> None:
    for job_id, payload in outbox.pending():
        deliver_result(job_id, payload)


def sanitize_note_urls(note: dict[str, object]) -> dict[str, object]:
    result = dict(note)
    value = result.get("note_url")
    if isinstance(value, str) and value:
        try:
            parts = urlsplit(value)
            query = urlencode([(key, item) for key, item in parse_qsl(parts.query, keep_blank_values=True)
                               if key.lower() not in {"xsec_token", "access_token", "token"}])
            result["note_url"] = urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))
        except ValueError:
            result["note_url"] = sanitize_error(value)
    return result


def set_state(job_id: int, state: Literal["pending", "running", "success", "fail"],
              errors: int = 0, message: str | None = None) -> None:
    with jobs_lock:
        jobs[job_id] = JobState(job_id=job_id, status=state, error_count=errors, message=message)


def sanitize_error(error: object) -> str:
    text = str(error or "")[:500]
    text = re.sub(
        r"(?i)(cookie|authorization|web_session|xsec_token|access[-_]?token)\s*[:=]\s*[^\s,;]+",
        r"\1=[redacted]",
        text,
    )
    text = re.sub(r"(?i)([?&](?:xsec_token|access_token|token)=)[^&\s]+", r"\1[redacted]", text)
    return text[:300]
