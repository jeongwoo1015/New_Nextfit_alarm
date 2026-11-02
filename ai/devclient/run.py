"""시나리오 실행기. 사용법(저장소 루트에서):
    .venv/bin/python -m ai.devclient.run --list
    .venv/bin/python -m ai.devclient.run briefing_full coaching_routine
    .venv/bin/python -m ai.devclient.run --all [--repeat N]
실제 Gemini를 호출한다(시나리오당 Live 연결 1회 + 밤 시나리오는 텍스트 호출 1~2회). GEMINI_API_KEY는 ai/.env에서 읽는다.
"""
import argparse
import dataclasses
import json
import os
import sys
import time

from ..tasks.llm import GeminiText, load_env
from ..tasks.night_record import extract_night_record
from ..tasks.routine_changes import parse_routine_changes
from .client import DevClient
from .rules import HARD, check, summarize
from .scenarios import SCENARIOS

HERE = os.path.dirname(os.path.abspath(__file__))


def run_one(scn, client: DevClient, llm: GeminiText) -> tuple:
    log = client.run(scn.kind, scn.inp, scn.utterances)
    extra = {}
    if scn.kind == "night" and not log.error:
        extra["extract"] = extract_night_record(log.transcript, llm)
        if scn.routine_items:
            extra["routine"] = parse_routine_changes(log.transcript, scn.routine_items, llm)
    return log, check(log, scn, extra), extra


def print_report(scn, log, results, extra) -> None:
    print(f"\n=== {scn.name} ({scn.kind})")
    for t in log.turns:
        if t.heard.strip():
            print(f"  사용자: {t.heard.strip()}")
        fa = f"{t.first_audio:.1f}초" if t.first_audio is not None else "-"
        print(f"  AI[{fa}]: {t.said.strip()}")
        for c in t.calls:
            print(f"    → 함수 {c['name']} {json.dumps(c['args'], ensure_ascii=False)}")
    if "extract" in extra:
        f = extra["extract"].fields
        print("  추출:", json.dumps({k: f[k] for k in ("tomorrow_goal", "condition_label", "override_wake_time", "override_skip_coaching")}, ensure_ascii=False))
    for r in results:
        if not r.passed:
            print(f"  {'✗ 실패' if r.severity == HARD else '△ 경고'} {r.rule}: {r.detail[:140]}")
    hard, soft = summarize(results)
    print(f"  결과: {'통과' if hard == 0 else '실패'} (HARD 실패 {hard}, SOFT 경고 {soft}, 검사 {len(results)}개)")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="*")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--repeat", type=int, default=1)
    args = ap.parse_args(argv)
    if args.list:
        for s in SCENARIOS.values():
            print(f"{s.name:26s} {s.kind}")
        return 0
    names = list(SCENARIOS) if args.all else args.names
    unknown = [n for n in names if n not in SCENARIOS]
    if not names or unknown:
        print("시나리오를 고르세요(--list로 목록 확인):", unknown or "")
        return 2
    load_env(os.path.join(HERE, "..", ".env"))
    client, llm = DevClient(), GeminiText()
    os.makedirs(os.path.join(HERE, "runs"), exist_ok=True)
    out_path = os.path.join(HERE, "runs", time.strftime("%Y%m%d_%H%M%S") + ".jsonl")
    failed = 0
    with open(out_path, "w", encoding="utf-8") as fp:
        for name in names:
            for _ in range(args.repeat):
                scn = SCENARIOS[name]
                log, results, extra = run_one(scn, client, llm)
                print_report(scn, log, results, extra)
                failed += summarize(results)[0] > 0
                fp.write(json.dumps({"scenario": name, "log": dataclasses.asdict(log),
                                     "results": [dataclasses.asdict(r) for r in results]}, ensure_ascii=False) + "\n")
    print(f"\n전체: {len(names) * args.repeat}회 중 HARD 실패 {failed}회 | Live 연결 {len(names) * args.repeat}회, 텍스트 호출 {llm.calls}회 | 기록: {out_path}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
