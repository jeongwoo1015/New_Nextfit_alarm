"""시나리오 실행 결과의 규칙 검사. HARD는 어기면 실패, SOFT는 경고(표본이 작고 AI 응답이 매번 달라지기 때문)."""
import re
from dataclasses import dataclass

from ..live.tools import VERDICTS

HARD, SOFT = "HARD", "SOFT"

END_INTENT = re.compile(r"잘게|잘 ?자|이만|끊을게|그만|끝낼게|끝내|안녕|종료|들어갈게|가볼게")     # 사용자가 대화를 끝내겠다는 말
READY = re.compile(r"준비|시작|응|네|좋아|그래|오케이|ㅇㅋ")                                      # 루틴을 시작할 준비가 됐다는 말
ALARM_WORDS = re.compile(r"알람")


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


def _script_text(scn) -> str:
    inp = getattr(scn, "inp", None)
    steps = list(getattr(inp, "steps", ()) or ()) + list(getattr(inp, "content_steps", ()) or ())
    return re.sub(r"[\s.!?~…,]", "", " ".join(s.script for s in steps if getattr(s, "script", "")))


def _from_script(sentence: str, scn) -> bool:
    key = re.sub(r"[\s.!?~…,]", "", sentence)
    return bool(key) and key in _script_text(scn)


def _names(turn) -> list[str]:
    return [c["name"] for c in turn.calls]


def check(log, scn, extra: dict | None = None) -> list[Result]:
    """log: SessionLog, scn: Scenario, extra: {'extract': NightRecordResult, 'routine': RoutineChanges}"""
    extra = extra or {}
    out: list[Result] = []
    add = lambda rule, sev, ok, detail="": out.append(Result(rule, sev, bool(ok), "" if ok else detail))
    e = scn.expect
    turns = log.turns
    texts = [t.said for t in turns]
    all_ai = " ".join(texts)

    # ----- 공통 -----
    add("연결 오류 없음", HARD, not log.error, log.error)
    add("AI가 말함", HARD, all(t.said.strip() for t in turns[:1]), "시작 신호에 AI가 말하지 않음")
    add("[END] 글자 없음", HARD, "[END]" not in all_ai and "END]" not in all_ai, "종료 표시가 글자로 나옴")
    add("마크다운 없음", HARD, not re.search(r"\*\*|^#|`|^\s*[-*]\s", all_ai, re.M), "마크다운 기호가 있음")
    eng = re.search(r"(?:[A-Za-z]{3,}[ ,.!]+){3,}", all_ai) if not e.get("allow_english") else None
    add("영어 덧붙임 없음", HARD, not eng, eng.group(0) if eng else "")
    known = ("end_session", "report_step_result", "start_routine", "adjust_step_time")
    bad_calls = [c for c in log.calls() if c["name"] not in known
                 or (c["name"] == "report_step_result" and (c["args"].get("verdict") not in VERDICTS or not isinstance(c["args"].get("step_order"), int)))
                 or (c["name"] == "adjust_step_time" and not (isinstance(c["args"].get("step_order"), int) and isinstance(c["args"].get("seconds"), int)
                                                              and c["args"]["seconds"] >= 0 and c["args"]["seconds"] % 30 == 0))]
    add("함수 호출 형식", HARD, not bad_calls, str(bad_calls))
    sents = [s for t in texts for s in sentences(t) if not _from_script(s, scn)]       # 고정 스크립트는 그대로 읽는 것이 규칙이라 어조 검사에서 뺀다
    if scn.tone == "CASUAL":
        bad = [s for s in sents if POLITE_END.search(_ending(s))]
        add("반말 유지", HARD, not bad, "; ".join(bad[:2]))
    else:
        bad = [s for s in sents if len(_ending(s)) > 2 and CASUAL_END.search(_ending(s)) and not POLITE_END.search(_ending(s))]
        add("존댓말 유지", HARD, not bad, "; ".join(bad[:2]))
    fa = turns[0].first_audio if turns else None
    add("첫 소리 3초 이내", SOFT, fa is not None and fa <= 3.0, f"{fa}")

    # 대화는 사용자가 끝낸다: 사용자가 끝내겠다는 말을 하지 않았는데 AI가 end_session을 부르면 실패
    early = [i for i, t in enumerate(turns) if "end_session" in _names(t) and not END_INTENT.search(t.heard)]
    add("사용자가 끝내자고 하기 전에 AI가 끝내지 않음", HARD, not early, f"{early}번째 턴에서 종료 호출")
    if e.get("farewell"):
        add("사용자 작별 뒤 end_session 호출", HARD, log.ended_by == "end_session", f"종료 방식={log.ended_by}")

    kind = scn.kind
    if kind == "night":
        first = texts[0] if texts else ""
        add("밤: 첫 마디 2문장 이하, 질문 하나", HARD, len(sentences(first)) <= 2 and first.count("?") == 1, first)
        awkward = [s for t in texts for s in sentences(t) if re.search(r"오늘은\s*내일", s)]
        add("밤: '오늘은 내일…'처럼 어색한 문장 없음", HARD, not awkward, "; ".join(awkward[:2]))
        asked_tomorrow = [s for t in texts for s in sentences(t) if "?" in s and "내일" in s and re.search(r"일정|챙겨|목표|해내|걱정|특별한", s)]
        if e.get("asks_tomorrow"):
            add("밤: 내일 목표·특이사항을 물어봄", SOFT, bool(asked_tomorrow), "내일 이야기를 묻는 질문이 없음")
        for i, t in enumerate(turns[1:], 1):
            farewell = "end_session" in _names(t) or bool(END_INTENT.search(t.heard))
            relaxed = bool(re.search(r"하루|알람|코칭", t.said))       # 하루 요약 확인, 내일 아침 조정 제안·확인은 3문장까지
            limit = 3 if relaxed else 2
            add(f"밤: {i}번째 답변 {limit}문장 이하", HARD, len(sentences(t.said)) <= limit, t.said)
            if not farewell:
                add(f"밤: {i}번째 답변이 질문으로 끝남", SOFT, t.said.strip().endswith("?"), t.said)
        if e.get("event_words"):
            add("밤: 일정을 짚으며 시작", SOFT, any(w in first for w in e["event_words"]), first)
        if "extract" in extra:
            ex = extra["extract"]
            add("추출 성공", HARD, ex.status == "DONE", ex.error)
            for k, v in (e.get("extract") or {}).items():
                got = ex.fields.get(k)
                ok = v(got) if callable(v) else got == v
                add(f"추출 {k}", HARD, ok, f"값={got!r}")
            if e.get("diary"):
                add("추출 diary_text(한 줄 일기)", HARD, bool(ex.fields.get("diary_text")), "일기가 비어 있음")
        if "routine" in extra:
            rc = extra["routine"]
            got = sorted(((c.template_item_id, c.duration_sec, c.scope) for c in rc.changes), key=str)
            want = sorted((tuple(x) for x in e.get("routine_changes", [])), key=str)
            add("항목 변경 파싱", HARD, rc.status == "DONE" and got == want, f"실제={got} 기대={want}")

    elif kind == "briefing":
        skipped = bool(e.get("coaching_skipped"))      # 코칭을 안 하는 날은 브리핑만 한다
        first = texts[0] if texts else ""
        add("브리핑: 확인 질문 하나만 묻기", HARD, first.count("?") == 1 and len(sentences(first)) <= 2 and not re.search(r"날씨|일정|우산", first), first)
        add("브리핑: 확인 질문 답 전에 시작하지 않음", HARD, "start_routine" not in _names(turns[0]) if turns else True, "")
        brief = texts[1] if len(texts) > 1 else ""
        n = len(sentences(brief))
        add("브리핑: 확인 질문에 답한 뒤 브리핑함", HARD, bool(brief.strip()), "브리핑이 없음")
        if brief.strip():
            add("브리핑: 알람 시각·알람 언급 없음", HARD, not ALARM_WORDS.search(brief), brief)
            short = bool(e.get("short_briefing"))       # 전략이 '짧게'인 날은 2~3문장(+준비 확인 질문)
            if not skipped:
                lo, hi = (2, 4) if short else (4, 7)
                add(f"브리핑: {lo}~{hi}문장(마지막은 준비 확인 질문)", HARD, lo <= n <= hi and brief.count("?") == 1 and brief.strip().endswith("?"), f"{n}문장, 물음표 {brief.count('?')}개")
            else:
                lo, hi = (2, 3) if short else (4, 6)
                add(f"브리핑: 코칭 안 하는 날이면 {lo}~{hi}문장, 질문 없음", HARD, lo <= n <= hi and "?" not in brief, f"{n}문장, 물음표 {brief.count('?')}개")
                add("브리핑: 코칭 안 하는 날이면 시작 권유 없음", HARD, not re.search(r"(루틴|콘텐츠|코칭|퀴즈).{0,10}시작", brief), brief)
            if e.get("now_words"):
                add("브리핑: 지금 시각 언급", SOFT, any(w in brief for w in e["now_words"]), brief)
            for key, label in (("weather_words", "날씨"), ("location_words", "날씨 지역"), ("event_words", "일정")):
                if e.get(key):
                    add(f"브리핑: {label} 언급", SOFT, any(w in brief for w in e[key]), brief)
            add("브리핑: 수면·기상 단정 없음", SOFT, not re.search(r"잘 (잤|주무|일어났)|푹 (잤|주무)", brief), brief)
        add("브리핑: 단계 판정은 하지 않음(콘텐츠·루틴 진행은 다음 세션)", HARD, not log.calls("report_step_result"), str(log.calls("report_step_result")))
        if e.get("ready_words") and brief.strip():
            add("브리핑: 다음 세션 시작 준비를 물음", SOFT, any(w in sentences(brief)[-1] for w in e["ready_words"]), brief)
        for i, t in enumerate(turns):
            if "start_routine" in _names(t):
                add("브리핑: 준비됐다는 답 뒤에만 시작", HARD, i >= 2 and bool(READY.search(t.heard)), f"{i}번째 턴, 사용자 말={t.heard!r}")
        if e.get("expect_start"):
            add("브리핑: 준비됐다고 하면 start_routine 호출", HARD, log.ended_by == "start_routine", f"종료 방식={log.ended_by}")

    elif kind == "coaching":
        add("코칭: 사용자 답 전에 판정 호출 없음", HARD, not (turns and turns[0].calls), str(turns[0].calls if turns else ""))
        orders = sorted(e.get("verdicts", {}))           # 재연결이면 남은 단계부터라 단계 번호가 1부터가 아닐 수 있다
        skip_turns = set(e.get("no_verdict_turns", ()))   # 시간 변경만 한 턴(그 턴에는 판정할 단계가 없다)
        slots = [i for i in range(1, len(turns)) if i not in skip_turns]
        turn_of = {o: slots[j] for j, o in enumerate(orders) if j < len(slots)}       # 단계 번호 → 그 단계의 답을 듣는 턴
        wrong_turn = [(i, c["args"].get("step_order")) for i, t in enumerate(turns) for c in t.calls
                      if c["name"] == "report_step_result" and i >= 1 and orders and turn_of.get(c["args"].get("step_order")) != i]
        add("코칭: 한 턴에 방금 답한 단계만 판정", HARD, not wrong_turn, f"(턴, 판정한 단계)={wrong_turn}")
        last_verdict = {}
        for c in log.calls("report_step_result"):
            last_verdict[c["args"].get("step_order")] = c["args"].get("verdict")
        want = e.get("verdicts", {})
        add("코칭: 판정이 기대와 같음", HARD, last_verdict == want, f"실제={last_verdict} 기대={want}")
        got_adj = {c["args"].get("step_order"): c["args"].get("seconds") for c in log.calls("adjust_step_time")}
        add("코칭: 시간 변경 호출이 기대와 같음", HARD, got_adj == e.get("adjust", {}), f"실제={got_adj} 기대={e.get('adjust', {})}")
        both = [(i, o) for i, t in enumerate(turns) for o in {c["args"].get("step_order") for c in t.calls if c["name"] == "adjust_step_time"}
                if o in {c["args"].get("step_order") for c in t.calls if c["name"] == "report_step_result"}]
        add("코칭: 같은 단계에 시간 변경과 판정을 함께 하지 않음", HARD, not both, f"(턴, 단계)={both}")
        dup = [k for k in last_verdict if sum(1 for c in log.calls('report_step_result') if c['args'].get('step_order') == k) > 1]
        add("코칭: 같은 단계 판정 중복 없음", SOFT, not dup, f"중복 단계={dup} (서버는 마지막 판정만 쓰면 됨)")
        names = e.get("step_names", [])
        for i, t in enumerate(turns):
            if "end_session" in _names(t):
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
