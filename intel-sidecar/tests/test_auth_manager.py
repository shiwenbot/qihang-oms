import threading
import time

from auth_manager import QrAuthManager


class MemoryStore:
    def __init__(self):
        self.saved = None

    def save(self, cookie, nickname="", red_id=""):
        self.saved = (cookie, nickname, red_id)

    def delete(self):
        self.saved = None


class BlockingLogin:
    def __init__(self, reached, release):
        self.reached = reached
        self.release = release
        self.calls = 0

    def generate_init_cookies(self):
        return {}

    def generate_qrcode(self, cookies):
        return True, "ok", {"cookies": cookies, "qr_id": "id", "code": "code", "qr_url": "https://example.test/qr"}

    def check_qrcode_status(self, qr_id, code, cookies):
        self.calls += 1
        return (False, "请扫描二维码", cookies) if self.calls == 1 else (True, "ok", cookies)

    def ensure_webprofile(self, cookies):
        return None

    def get_user_info(self, cookies):
        self.reached.set()
        self.release.wait(2)
        return True, {"guest": False, "nickname": "账号", "red_id": "red"}, cookies

    def cookies_to_str(self, cookies):
        return "a1=test; web_session=secret"

    def close(self):
        return None


def test_logout_cancels_login_before_credential_save(monkeypatch):
    reached = threading.Event()
    release = threading.Event()
    store = MemoryStore()
    applied = []
    monkeypatch.setattr(QrAuthManager, "_qr_data_url", staticmethod(lambda _: "data:image/png;base64,test"))
    manager = QrAuthManager(store, applied.append, lambda: BlockingLogin(reached, release))

    manager.start()
    assert reached.wait(1)
    manager.logout()
    release.set()
    time.sleep(0.1)

    assert store.saved is None
    assert applied == [""]

