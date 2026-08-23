from __future__ import annotations

import base64
import io
import threading
import time
import uuid
import re
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable

from credential_store import CredentialStore, mask_nickname


@dataclass
class LoginSession:
    session_id: str
    status: str = "preparing"
    message: str = "正在准备登录环境"
    qr_image: str | None = None
    nickname: str | None = None
    created_at: float = field(default_factory=time.monotonic)
    cancelled: threading.Event = field(default_factory=threading.Event, repr=False)

    def public(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "status": self.status,
            "message": self.message,
            "qr_image": self.qr_image,
            "nickname": self.nickname,
        }


class QrAuthManager:
    def __init__(self, store: CredentialStore, on_cookie: Callable[[str], None],
                 login_factory: Callable[[], Any]):
        self.store = store
        self.on_cookie = on_cookie
        self.login_factory = login_factory
        self._session: LoginSession | None = None
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="xhs-login")

    def start(self) -> dict[str, Any]:
        with self._lock:
            if self._session and self._session.status in {"preparing", "wait_scan", "wait_confirm"}:
                return self._session.public()
            session = LoginSession(session_id=uuid.uuid4().hex)
            self._session = session
        self._executor.submit(self._login, session)
        return session.public()

    def status(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            if not self._session or self._session.session_id != session_id:
                raise KeyError("login session not found")
            return self._session.public()

    def logout(self) -> None:
        with self._lock:
            if self._session:
                self._session.cancelled.set()
            self._session = None
        self.store.delete()
        self.on_cookie("")

    def _set(self, session: LoginSession, status: str, message: str, **values: Any) -> None:
        with self._lock:
            if self._session is not session or session.cancelled.is_set():
                return
            session.status = status
            session.message = message
            for key, value in values.items():
                setattr(session, key, value)

    def _login(self, session: LoginSession) -> None:
        login = None
        try:
            login = self.login_factory()
            cookies = login.generate_init_cookies()
            ok, message, qr_data = login.generate_qrcode(cookies)
            if not ok or not qr_data:
                raise RuntimeError(message)
            cookies = qr_data["cookies"]
            ok, message, cookies = login.check_qrcode_status(qr_data["qr_id"], qr_data["code"], cookies)
            if ok or message != "请扫描二维码":
                raise RuntimeError("二维码预检查状态异常")
            login.ensure_webprofile(cookies)
            self._set(session, "wait_scan", "请使用小红书 App 扫码",
                      qr_image=self._qr_data_url(qr_data["qr_url"]))

            deadline = time.monotonic() + 180
            while time.monotonic() < deadline and not session.cancelled.is_set():
                ok, message, cookies = login.check_qrcode_status(qr_data["qr_id"], qr_data["code"], cookies)
                if ok:
                    break
                if message == "二维码已过期":
                    self._set(session, "expired", message)
                    return
                self._set(session, "wait_confirm" if message == "请确认登录" else "wait_scan", message)
                if session.cancelled.wait(2):
                    return
            else:
                if session.cancelled.is_set():
                    return
                self._set(session, "expired", "二维码已过期，请重新生成")
                return

            ok, user_info, cookies = login.get_user_info(cookies)
            if not ok or user_info.get("guest") is not False:
                raise RuntimeError("正式登录状态验证失败")
            cookie = login.cookies_to_str(cookies)
            nickname = str(user_info.get("nickname") or "")
            red_id = str(user_info.get("red_id") or "")
            with self._lock:
                if self._session is not session or session.cancelled.is_set():
                    return
                self.store.save(cookie, nickname, red_id)
                self.on_cookie(cookie)
                session.status = "success"
                session.message = "账号连接成功"
                session.qr_image = None
                session.nickname = mask_nickname(nickname)
        except Exception as exc:
            self._set(session, "fail", _sanitize_error(exc), qr_image=None)
        finally:
            if login is not None:
                try:
                    login.close()
                except Exception:
                    pass

    @staticmethod
    def _qr_data_url(url: str) -> str:
        import qrcode

        qr = qrcode.QRCode(box_size=7, border=3)
        qr.add_data(url)
        qr.make(fit=True)
        image = qr.make_image(fill_color="black", back_color="white")
        output = io.BytesIO()
        image.save(output, format="PNG")
        return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def _sanitize_error(error: object) -> str:
    text = str(error or "")[:500]
    text = re.sub(r"(?i)(cookie|authorization|web_session|xsec_token|access[-_]?token)\s*[:=]\s*[^\s,;]+",
                  r"\1=[redacted]", text)
    return re.sub(r"(?i)([?&](?:xsec_token|access_token|token)=)[^&\s]+", r"\1[redacted]", text)[:300]
