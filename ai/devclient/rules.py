"""시나리오 실행 결과의 규칙 검사. HARD는 어기면 실패, SOFT는 경고(표본이 작고 AI 응답이 매번 달라지기 때문)."""
import re
from dataclasses import dataclass

from ..live.tools import VERDICTS

HARD, SOFT = "HARD", "SOFT"


@dataclass
class Result:
    rule: str
    severity: str
    passed: bool
    detail: str = ""


def sentences(text: str) -> list[str]:
    """받아쓰기는 문장 사이 공백이 없을 수 있어('에요.오늘은') 마침표·물음표 뒤에서 나눈다."""
    parts = re.split(r"(?<=[.!?])\s*", (text or "").strip())
    return [p for p in parts if re.search(r"[0-9A-Za-z가-힣]", p)]


def _ending(s: str) -> str:
    return re.sub(r"[\s.!?~…\"')]+$", "", s)


CASUAL_END = re.compile(r"(야|어|해|지|자|네|군|걸|래|게|니|아|와|냐)$")   # 반말 어미(요/다로 끝나면 해당 없음)
POLITE_END = re.compile(r"(요|니다|니까|세요)$")


def _turns(log):
    return log.turns


def _ai_text(log) -> str:
    return " ".join(t.said for t in log.turns)


def check(log, scn, extra: dict | None = None) -> list[Result]:
    """log: SessionLog, scn: Scenario, extra: {'extract': NightRecordResult, 'routine': RoutineChanges}"""
    extra = extra or {}
    out: list[Result] = []
    add = lambda rule, sev, ok, detail="": out.append(Result(rule, sev, bool(ok), "" if ok else detail))
    e = scn.expect
    turns = _turns(log)
    texts = [t.said for t in turns]
    all_ai = _ai_text(log)

    # ----- 공통 -----
    add("연결 오류 없음", HARD, not log.error, log.error)
    add("AI가 말함", HARD, all(t.said.strip() for t in turns[:1]), "시작 신호에 AI가 말하지 않음")
    add("[END] 글자 없음", HARD, "[END]" not in all_ai and "END]" not in all_ai, "종료 표시가 글자로 나옴")
    add("마크다운 없음", HARD, not re.search(r"\*\*|^#|`|^\s*[-*]\s", all_ai, re.M), "마크다운 기호가 있음")
    eng = re.search(r"(?:[A-Za-z]{3,}[ ,.!]+){3,}", all_ai) if not e.get("allow_english") else None
    add("영어 덧붙임 없음", HARD, not eng, eng.group(0) if eng else "")
    bad_calls = [c for c in log.calls() if c["name"] not in ("end_session", "report_step_result")
                 or (c["name"] == "report_step_result" and (c["args"].get("verdict") not in VERDICTS or not isinstance(c["args"].get("step_order"), int)))]
    add("함수 호출 형식", HARD, not bad_calls, str(bad_calls))
    sents = [s for t in texts for s in sentences(t)]
    if scn.tone == "CASUAL":
        bad = [s for s in sents if POLITE_END.search(_ending(s))]
        add("반말 유지", HARD, not bad, "; ".join(bad[:2]))
    else:
        bad = [s for s in sents if len(_ending(s)) > 2 and CASUAL_END.search(_ending(s)) and not POLITE_END.search(_ending(s))]
        add("존댓말 유지", HARD, not bad, "; ".join(bad[:2]))
    fa = turns[0].first_audio if turns else None
    add("첫 소리 3초 이내", SOFT, fa is not None and fa <= 3.0, f"{fa}")

    kind = scn.kind
    if kind == "night":
        first = texts[0] if texts else ""
        add("밤: 첫 마디 2문장 이하, 질문 하나", HARD, len(sentences(first)) <= 2 and first.count("?") == 1, first)
        for i, t in enumerate(turns[1:], 1):
            final = "end_session" in [c["name"] for c in t.calls]
            if not final:
                limit = 3 if "하루" in t.said else 2   # 하루 한정 변경 제안은 적용 범위 안내까지 3문장 허용(지시문 규칙)
                add(f"밤: {i}번째 답변 {limit}문장 이하", HARD, len(sentences(t.said)) <= limit, t.said)
                add(f"밤: {i}번째 답변이 질문으로 끝남", SOFT, t.said.strip().endswith("?"), t.said)
        ended_on_question = [t for t in turns if t.said.strip().endswith("?") and any(c["name"] == "end_session" for c in t.calls)]
        add("밤: 질문하고 바로 끝내지 않음", HARD, not ended_on_question, "")
        if e.get("event_words"):
            add("밤: 일정을 짚으며 시작", SOFT, any(w in first for w in e["event_words"]), first)
        if "extract" in extra:
            ex = extra["extract"]
            add("추출 성공", HARD, ex.status == "DONE", ex.error)
            for k, v in (e.get("extract") or {}).items():
                got = ex.fields.get(k)
                ok = v(got) if callable(v) else got == v
                add(f"추출 {k}", HARD, ok, f"값={got!r}")
        if "routine" in extra:
            rc = extra["routine"]
            add("항목 빼기 파싱", HARD, rc.status == "DONE" and sorted(rc.remove) == sorted(e.get("routine_remove", [])), f"{rc}")

    elif kind == "briefing":
        text = texts[0] if texts else ""
        n = len(sentences(text))
        add("브리핑: 물음표 없음", HARD, "?" not in text, text)
        add("브리핑: 4~6문장", HARD, 4 <= n <= 6, f"{n}문장")
        add("브리핑: end_session 호출", HARD, log.ended_by == "end_session", "호출하지 않음(앱은 turnComplete를 종료로 봐야 함)")
        last = sentences(text)[-1] if sentences(text) else ""
        if e.get("coaching_min", 20) > 0:
            add("브리핑: 마지막 문장이 시작 권유", HARD, "시작" in last, last)
        else:
            add("브리핑: 코칭 0분이면 시작 권유 없음", HARD, not re.search(r"시작(해|하자|할)", text), text)
        for key, label in (("weather_words", "날씨"), ("event_words", "일정"), ("wake_words", "알람 시각")):
            if e.get(key):
                add(f"브리핑: {label} 언급", SOFT, any(w in text for w in e[key]), text)
        add("브리핑: 수면·기상 단정 없음", SOFT, not re.search(r"잘 (잤|주무|일어났)|푹 (잤|주무)", text), text)

    elif kind == "coaching":
        add("코칭: 사용자 답 전에 판정 호출 없음", HARD, not (turns and turns[0].calls), str(turns[0].calls if turns else ""))
        last_verdict = {}
        for c in log.calls("report_step_result"):
            last_verdict[c["args"].get("step_order")] = c["args"].get("verdict")
        want = e.get("verdicts", {})
        add("코칭: 판정이 기대와 같음", HARD, last_verdict == want, f"실제={last_verdict} 기대={want}")
        dup = [k for k in last_verdict if sum(1 for c in log.calls('report_step_result') if c['args'].get('step_order') == k) > 1]
        add("코칭: 같은 단계 판정 중복 없음", SOFT, not dup, f"중복 단계={dup} (서버는 마지막 판정만 쓰면 됨)")
        add("코칭: 마지막에 end_session", HARD, log.ended_by == "end_session", "")
        names = e.get("step_names", [])
        for i, t in enumerate(turns):
            if "end_session" in [c["name"] for c in t.calls]:
                continue
            mentioned = [nm for nm in names if nm in t.said]
            add(f"코칭: {i}번째 안내가 단계 하나만", SOFT, len(mentioned) <= 1, f"{mentioned}")
        answers = e.get("answers", [])
        for i, t in enumerate(turns):
            leaked = [a for a in answers[i:] if a in t.said]
            if answers:
                add(f"코칭: {i}번째 말에서 정답 미공개", HARD, not leaked, f"공개된 정답={leaked}")
    return out


def summarize(results: list[Result]) -> tuple[int, int]:
    """(HARD 실패 수, SOFT 경고 수)"""
    return (sum(1 for r in results if r.severity == HARD and not r.passed),
            sum(1 for r in results if r.severity == SOFT and not r.passed))
