from collector import normalize_note, normalize_user, parse_count
from credential_store import CredentialStore, mask_nickname


def test_parse_count_units():
    assert parse_count("1.2万") == 12000
    assert parse_count("3万+") == 30000
    assert parse_count("2.5w") == 25000
    assert parse_count("1,234") == 1234
    assert parse_count(None) == 0


def test_normalize_search_note():
    raw = {
        "id": "note-1",
        "xsec_token": "token",
        "note_card": {
            "display_title": "新中式连衣裙",
            "user": {"user_id": "user-1", "nickname": "作者"},
            "interact_info": {"liked_count": "1.2万", "collected_count": "88", "comment_count": 7},
            "cover": {"url_default": "https://img.example/cover.jpg"},
            "time": 1_700_000_000_000,
        },
    }
    note = normalize_note(raw, "search", ["新中式"])
    assert note["note_id"] == "note-1"
    assert note["liked_count"] == 12000
    assert note["user_id"] == "user-1"
    assert "xsec_token=token" in note["note_url"]


def test_normalize_user_interactions():
    raw = {"data": {"basic_info": {"nickname": "品牌", "red_id": "red1", "imageb": "avatar"},
                    "interactions": [{"count": "12"}, {"count": "3.4万"}]}}
    user = normalize_user(raw, "u1")
    assert user["fans"] == 34000
    assert user["follows"] == 12


def test_dpapi_credential_roundtrip(tmp_path):
    store = CredentialStore(tmp_path / "credentials.bin")
    store.save("a1=test; web_session=secret", "测试账号", "red1")
    assert b"web_session" not in store.path.read_bytes()
    assert store.load()["cookie"] == "a1=test; web_session=secret"
    assert mask_nickname("测试账号") == "测**号"
    store.delete()
    assert store.load() is None
