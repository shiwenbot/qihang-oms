from fastapi.testclient import TestClient
from pathlib import Path

import app as sidecar
from result_outbox import ResultOutbox


def test_routes_require_shared_token(monkeypatch):
    monkeypatch.setattr(sidecar, "TOKEN", "test-token")
    client = TestClient(sidecar.app)
    response = client.get("/health")
    assert response.status_code == 401
    assert response.json() == {"detail": "unauthorized"}


def test_invalid_provider_is_bad_request(monkeypatch):
    monkeypatch.setattr(sidecar, "TOKEN", "test-token")
    client = TestClient(sidecar.app)
    response = client.get("/health?provider=unknown", headers={"Authorization": "Bearer test-token"})
    assert response.status_code == 400
    assert "unknown" in response.json()["detail"]


def test_invalid_account_identifier_is_bad_request(monkeypatch):
    monkeypatch.setattr(sidecar, "TOKEN", "test-token")
    client = TestClient(sidecar.app)
    response = client.delete("/auth?account_id=../bad", headers={"Authorization": "Bearer test-token"})
    assert response.status_code == 400
    assert response.json()["detail"] == "account_id格式无效"


def test_result_outbox_round_trip(monkeypatch, tmp_path: Path):
    monkeypatch.setattr("result_outbox._protect", lambda value: b"protected:" + value)
    monkeypatch.setattr("result_outbox._unprotect", lambda value: value.removeprefix(b"protected:"))
    outbox = ResultOutbox(tmp_path)
    payload = {"status": "success", "request_token": "a" * 64}

    outbox.save(42, payload)

    assert outbox.pending() == [(42, payload)]
    assert not (tmp_path / "42.tmp").exists()
    outbox.delete(42)
    assert outbox.pending() == []


def test_startup_replays_persisted_results(monkeypatch):
    class PendingOutbox:
        def pending(self):
            return [(7, {"status": "success"})]

    delivered = []
    monkeypatch.setattr(sidecar, "outbox", PendingOutbox())
    monkeypatch.setattr(sidecar, "deliver_result", lambda job_id, payload: delivered.append((job_id, payload)))

    sidecar.retry_outbox_once()

    assert delivered == [(7, {"status": "success"})]


def test_run_job_persists_request_before_queueing(monkeypatch):
    saved = []
    queued = []

    class FakeInbox:
        def save(self, job_id, payload):
            saved.append((job_id, payload))

    class FakeExecutor:
        def submit(self, function, *args):
            queued.append((function, args))

    runtime = type("Runtime", (), {"collector": type("Collector", (), {"cookie": "present"})()})()
    monkeypatch.setattr(sidecar.runtimes, "get", lambda provider, account_id: runtime)
    monkeypatch.setattr(sidecar, "inbox", FakeInbox())
    monkeypatch.setattr(sidecar, "executor", FakeExecutor())
    sidecar.jobs.clear()
    request = sidecar.JobRequest(job_id=81, keywords=[], competitors=[], request_token="a" * 64)

    result = sidecar.run_job(request)

    assert result == {"job_id": 81, "status": "pending"}
    assert saved[0][0] == 81
    assert saved[0][1]["provider"] == "xiaohongshu"
    assert len(queued) == 1
