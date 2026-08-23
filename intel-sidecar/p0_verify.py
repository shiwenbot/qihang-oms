from __future__ import annotations

import argparse
import os
from pathlib import Path

from dotenv import load_dotenv

from collector import SpiderCollector


def main() -> int:
    parser = argparse.ArgumentParser(description="Spider_XHS P0 read-only verification")
    parser.add_argument("profile_url", help="带 xsec_token 的竞品主页 URL")
    args = parser.parse_args()
    load_dotenv(Path(__file__).with_name(".env"))
    cookie = os.getenv("COOKIES", "").strip()
    if not cookie:
        raise SystemExit("COOKIES is missing in .env")
    user_id = args.profile_url.split("/user/profile/", 1)[-1].split("?", 1)[0]
    collector = SpiderCollector(cookie, 3, 6)

    notes = collector.search("新中式连衣裙", 2)
    assert len(notes) >= 10, f"search returned only {len(notes)} notes"
    assert all(x["note_id"] and x["user_id"] and x["nickname"] for x in notes)
    assert all(isinstance(x["liked_count"], int) for x in notes)
    print(f"[PASS] search: {len(notes)} notes with author and integer counts")

    profile, user_notes = collector.competitor(user_id, args.profile_url)
    assert profile["nickname"], "competitor nickname missing"
    assert isinstance(profile["fans"], int), "competitor fans is not int"
    assert user_notes, "competitor has no visible notes"
    print(f"[PASS] competitor: {profile['nickname']}, fans={profile['fans']}, notes={len(user_notes)}")
    print("P0 verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
