#!/usr/bin/env python3
"""検知の記録（diag の jsonl）を、合わせ込みに使える形にまとめる。

使い方: scripts/summarize_diag.py <jsonl> [--since HH:MM] [--until HH:MM]

出すもの:
- 撮った回数（自動・シャッター・撮り直し）、消した回数
- 1 枚ごとの一覧（時刻、きっかけ、撮るのにかかった時間、鮮明さ、紙の明るさ、行の数、知らせ）
- 「同じページ」と判断した回数と、そのときの一致の度合い
- 写真の大きさ（平均・最大）、仕上げにかかった時間
- 上げる処理（回数、かかった時間、失敗）
"""
import json
import statistics
import sys
from datetime import datetime


def hhmm(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    path = args[0]
    since = until = None
    if "--since" in args:
        since = args[args.index("--since") + 1]
    if "--until" in args:
        until = args[args.index("--until") + 1]

    events = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = datetime.fromtimestamp(e["t"] / 1000).strftime("%H:%M")
            if since and t < since:
                continue
            if until and t > until:
                continue
            events.append(e)

    captured = [e for e in events if e["e"] == "captured"]
    photos = {e["id"]: e for e in events if e["e"] == "photo"}
    deletes = [e for e in events if e["e"] == "delete"]
    retakes = [e for e in events if e["e"] == "retake"]
    decides = [e for e in events if e["e"] == "decide"]
    same = [e for e in decides if e.get("same") is True]
    not_page = [e for e in decides if e.get("status") == "NOT_A_PAGE"]
    syncs = [e for e in events if e["e"] == "sync"]
    sync_errors = [e for e in events if e["e"] == "sync_error"]
    errors = [e for e in events if e["e"].endswith("_error") and e["e"] != "sync_error"]
    bound = [e for e in events if e["e"] == "camera_bound"]

    by_reason = {}
    for e in captured:
        by_reason[e["reason"]] = by_reason.get(e["reason"], 0) + 1

    print()
    print("== 撮った回数 ==")
    print(f"  合計 {len(captured)}（自動 {by_reason.get('AUTO', 0)}・シャッター/音量ボタン {by_reason.get('MANUAL', 0)}・撮り直し {by_reason.get('RETAKE', 0)}）")
    print(f"  消した {len(deletes)}（うち「撮り直す」 {len(retakes)}）")
    if bound:
        b = bound[-1]
        print(f"  カメラ: 写真 {b.get('photoW')}×{b.get('photoH')}、判定用 {b.get('analysisW')}×{b.get('analysisH')}")

    print()
    print("== 1 枚ごと ==")
    print("  時刻      きっかけ  撮る[ms]  鮮明さ  紙  行  大きさ[KB]  知らせ")
    deleted_ids = {e["id"] for e in deletes}
    for e in captured:
        p = photos.get(e["id"], {})
        warn = []
        if p.get("cut"):
            warn.append("切れ:" + p["cut"])
        if p.get("blur"):
            warn.append("ぶれ")
        if p.get("dark"):
            warn.append("暗い")
        if e.get("moved"):
            warn.append("(撮影中に動いた)")
        if e["id"] in deleted_ids:
            warn.append("[消した]")
        sharp = p.get("sharp")
        print(
            f"  {hhmm(e['t'])}  {e['reason']:8}  {e.get('latencyMs', 0):7}  "
            f"{(f'{sharp:.2f}' if sharp is not None else '  - '):>6}  {p.get('paper', '-'):>3}  {p.get('lines', '-'):>2}  "
            f"{(p.get('bytes', 0) // 1024):>9}  {' '.join(warn)}"
        )

    sharps = [p["sharp"] for p in photos.values() if p.get("sharp") is not None]
    sizes = [p["bytes"] for p in photos.values() if p.get("bytes")]
    times = [p["ms"] for p in photos.values() if p.get("ms")]
    lat = [e["latencyMs"] for e in captured if e.get("latencyMs")]
    print()
    print("== まとめ ==")
    if sharps:
        print(f"  鮮明さ: 中央 {statistics.median(sharps):.2f}、最小 {min(sharps):.2f}、最大 {max(sharps):.2f}")
    if sizes:
        print(f"  写真の大きさ: 平均 {statistics.mean(sizes) / 1024:.0f} KB、最大 {max(sizes) / 1024:.0f} KB、合計 {sum(sizes) / 1024 / 1024:.1f} MB")
    if times:
        print(f"  仕上げ: 中央 {statistics.median(times):.0f} ms、最大 {max(times)} ms")
    if lat:
        print(f"  撮る合図から保存まで: 中央 {statistics.median(lat):.0f} ms、最大 {max(lat)} ms")
    cut = sum(1 for p in photos.values() if p.get("cut"))
    blur = sum(1 for p in photos.values() if p.get("blur"))
    dark = sum(1 for p in photos.values() if p.get("dark"))
    print(f"  知らせ: 切れ {cut}、ぶれ {blur}、暗い {dark}")

    print()
    print("== 「同じページ」と判断した回数 ==")
    print(f"  {len(same)} 回（手が入った・本がずれた・明るさが変わった、など。ここで撮っていれば二度撮りになっていた）")
    fr = [e["matched"] / e["eligible"] for e in same if e.get("eligible")]
    if fr:
        print(f"  一致の度合い: 最小 {min(fr):.2f}、中央 {statistics.median(fr):.2f}（しきい値は MatcherConfig.sameFraction）")
    diff = [e["matched"] / e["eligible"] for e in decides if e.get("same") is False and e.get("eligible")]
    if diff:
        print(f"  違うページと判断したときの一致の度合い: 最大 {max(diff):.2f}、中央 {statistics.median(diff):.2f}")
    print(f"  「ページらしくない」と判断: {len(not_page)} 回")

    print()
    print("== 上げる ==")
    print(f"  {len(syncs)} 回、失敗 {len(sync_errors)} 回")
    if syncs:
        ms = [e["ms"] for e in syncs]
        print(f"  1 回あたり: 中央 {statistics.median(ms):.0f} ms、最大 {max(ms)} ms")
    for e in sync_errors[-5:]:
        print(f"  失敗 {hhmm(e['t'])}: {e.get('problem')} {e.get('detail', '')}")
    if errors:
        print()
        print("== そのほかの失敗 ==")
        for e in errors[-10:]:
            print(f"  {hhmm(e['t'])} {e['e']}: {e.get('error', '')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
