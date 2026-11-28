"""시나리오 실행 결과의 규칙 검사. HARD는 어기면 실패, SOFT는 경고(표본이 작고 AI 응답이 매번 달라지기 때문)."""
import re
from dataclasses import dataclass

from ..live.tools import SETTING_SCOPES, SETTING_VALUES, VERDICTS

HARD, SOFT = "HARD", "SOFT"

END_INTENT = re.compile(r"잘게|잘 ?자|이만|끊을게|그만|끝낼게|끝내|안녕|종료|들어갈게|가볼게")     # 사용자가 대화를 끝내겠다는 말
REGREET = re.compile(r"좋은 아침|굿모닝|안녕하세요")                                              # 브리핑 뒤 단계에서 첫인사를 또 함
READY = re.compile(r"준비|시작|응|네|좋아|그래|오케이|ㅇㅋ")                                      # 루틴을 시작할 준비가 됐다는 말
ALARM_WORDS = re.compile(r"알람")
NATIVE_UNIT = re.compile(r"(열|스무|스물|서른|마흔|쉰)(한|두|세|네|다섯|여섯|일곱|여덟|아홉)?\s?(분|초)")     # "열다섯 분"처럼 우리말 수로 분·초를 말함 (15분 / 십오 분이어야 한다)
ENCOURAGE = re.compile(r"조금만|거의 다|힘내|화이팅|파이팅|벌써|절반|반이나|잘하고 (있|계)|잘 하고 (있|계)|계속 이렇게|이대로|멋져|훌륭|대단|힘차게")   # 단계 사이 독려 한마디
ACCEPT = re.compile(r"응|네|그래|없어|알겠|알았|좋아|그렇게|오케이|ㅇㅋ")                 # 마무리를 받아들이는 짧은 대답
WRAP_Q = re.compile(r"마무리할까|마칠까|이만 할까")                                      # 마무리해도 되는지 묻는 말 (밤 대화는 묻지 않고 알린다)
CLOSE = re.compile(r"마무리할게|녹음해 볼게")                                                       # AI가 "이제 마무리할게요"라고 알리는 말
NIGHT_GREETING = re.compile(r"안녕히 주무|잘 ?자요|잘 ?자[.!]|편안한 밤|좋은 꿈|푹 주무")                # 아침 대화에서 나오면 안 되는 밤 인사
FAREWELL = re.compile(r"편안한 밤|좋은 꿈|푹 (쉬|주무|자)|안녕히|잘 자|좋은 밤")             # AI의 작별 인사


def _may_end(turns, i) -> bool:
    """end_session을 불러도 되는 턴인가: 사용자가 끝내겠다고 했거나, AI의 마무리 확인 질문·작별 인사 뒤에 짧게 받아들였을 때."""
    heard = turns[i].heard
    if END_INTENT.search(heard) or CLOSE.search(turns[i].said):      # AI가 "이제 마무리할게요"라고 알리며 끝내는 것은 허용(밤 대화)
        return True
    prev = next((t.said for t in reversed(turns[:i]) if t.said.strip()), "")      # 바로 앞 AI 말(빈 턴은 건너뜀)
    return bool(ACCEPT.search(heard)) and bool(WRAP_Q.search(prev) or FAREWELL.search(prev))


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


CASUAL_END = re.compile(r"(야|어|해|지|자|네|군|걸|래|게|니|아|와|냐|줘|러|여)$")   # 반말 어미(요/다로 끝나면 해당 없음). 줘·러·여는 11/21 추가("말해 줘", "머물러", "줄여"가 새 나감)
POLITE_END = re.compile(r"(요|니다|니까|세요)$")


def _script_text(scn) -> str:
    inp = getattr(scn, "inp", None)
    steps = list(getattr(inp, "steps", ()) or ()) + list(getattr(inp, "content_steps", ()) or ())
    scripts = [s.script for s in steps if getattr(s, "script", "")] + [getattr(inp, "fortune_text", "") or ""]
    return re.sub(r"[\s.!?~…,]", "", " ".join(scripts))


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
    known = ("end_session", "report_step_result", "start_routine", "adjust_step_time", "reschedule_alarm", "select_stretch_area", "update_setting")

    def _bad_setting(a):            # field·value는 허용 값, scope는 ALWAYS/TODAY(없으면 ALWAYS)
        return a.get("field") not in SETTING_VALUES or str(a.get("value")) not in SETTING_VALUES.get(a.get("field"), ()) or a.get("scope", "ALWAYS") not in SETTING_SCOPES

    def _bad_reschedule(a):         # minutes_later(1~60) 또는 at_time("HH:MM") 중 하나만
        ml, at = a.get("minutes_later"), a.get("at_time")
        if (ml is None) == (at is None):
            return True
        return not (isinstance(ml, int) and 1 <= ml <= 60) if ml is not None else not (isinstance(at, str) and re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", at))
    bad_calls = [c for c in log.calls() if c["name"] not in known or (c["name"] == "reschedule_alarm" and _bad_reschedule(c["args"])) or (c["name"] == "update_setting" and _bad_setting(c["args"]))
                 or (c["name"] == "report_step_result" and (c["args"].get("verdict") not in VERDICTS or not isinstance(c["args"].get("step_order"), int)
                                                             or ("answer_correct" in c["args"] and not isinstance(c["args"]["answer_correct"], bool))))
                 or (c["name"] == "adjust_step_time" and not (isinstance(c["args"].get("step_order"), int) and isinstance(c["args"].get("seconds"), int)
                                                              and c["args"]["seconds"] >= 0 and c["args"]["seconds"] % 30 == 0))]
    add("함수 호출 형식", HARD, not bad_calls, str(bad_calls))
    native = [m.group(0) for t in turns for m in [NATIVE_UNIT.search(t.said)] if m]
    add("시간은 '열다섯 분'이 아니라 15분·십오 분으로 말함", HARD, not native, f"우리말 수로 말함: {native[:3]}")
    sents = [s for t in texts for s in sentences(t) if not _from_script(s, scn)]       # 고정 스크립트는 그대로 읽는 것이 규칙이라 어조 검사에서 뺀다
    if scn.tone == "CASUAL":
        bad = [s for s in sents if POLITE_END.search(_ending(s))]
        add("반말 유지", HARD, not bad, "; ".join(bad[:2]))
    else:
        bad = [s for s in sents if len(_ending(s)) > 2 and CASUAL_END.search(_ending(s)) and not POLITE_END.search(_ending(s))]
        add("존댓말 유지", HARD, not bad, "; ".join(bad[:2]))
    if scn.kind != "night":
        bad_night = [t.said for t in turns if NIGHT_GREETING.search(t.said)]
        add("아침 대화에 밤 인사 없음", HARD, not bad_night, "; ".join(bad_night[:2]))
    fa = turns[0].first_audio if turns else None
    add("첫 소리 3초 이내", SOFT, fa is not None and fa <= 3.0, f"{fa}")

    # 대화는 사용자가 끝낸다: 사용자가 끝내겠다는 말을 하지 않았는데 AI가 end_session을 부르면 실패
    pass_ends = scn.expect.get("pass_ends")       # 끝말잇기: 마지막 단계에서 '패스'하면 마무리하며 끝내도 된다(남은 단계가 없다)
    early = [i for i, t in enumerate(turns) if "end_session" in _names(t) and not _may_end(turns, i)
             and not (pass_ends and re.search(r"패스|그만", t.heard))]
    add("사용자가 끝내자고 하기 전에 AI가 끝내지 않음", HARD, not early, f"{early}번째 턴에서 종료 호출")
    if e.get("farewell"):
        add("사용자 작별 뒤 end_session 호출", HARD, log.ended_by == "end_session", f"종료 방식={log.ended_by}")

    if "settings" in e:              # 대화로 설정 바꾸기(11/20 회의 4): 확인을 받은 뒤 허용된 값으로만 바꾸고, 바꿨다고 알린다
        wanted = [dict(w, scope=w.get("scope", "ALWAYS")) for w in e["settings"]]       # [] = 지원하지 않는 설정이라 바꾸지 않는다
        got_calls = [dict(c["args"], scope=c["args"].get("scope", "ALWAYS")) for c in log.calls("update_setting")]
        add("설정 변경: 호출이 기대와 같음", HARD, got_calls == wanted, f"실제={got_calls} 기대={wanted}")
        add("설정 변경: 함수를 부르지 않고 바꿨다고 말하지 않음", HARD, not (re.search(r"바꿨", all_ai) and not got_calls), all_ai[:160])
        first_call = next((i for i, t in enumerate(turns) if "update_setting" in _names(t)), None)
        if wanted:
            add("설정 변경: 호출 전에 바꿀 값을 확인받음", HARD, first_call is not None and first_call >= 2 and "?" in turns[first_call - 1].said,
                f"호출 턴={first_call}, 앞 응답={turns[first_call - 1].said[:80] if first_call else ''!r}")
            tail = " ".join(t.said for t in turns[first_call:first_call + 2]) if first_call is not None else ""
            add("설정 변경: 바꿨다고 알림", HARD, bool(re.search(r"바꿨|바꿀게|바뀌|적용|시작할게|맞출게|설정했|변경", tail)), tail[:140])
        else:
            add("설정 변경: 지원하지 않는 설정은 설정 화면을 안내함", SOFT, "설정" in all_ai, all_ai[:140])

    if "wake_raise" in e:            # 밤 대화에서 내일 알람 시작 볼륨 상승을 알리고, 반대하면 이전 단계를 유지한다(11/21 결정 4)
        wr = e["wake_raise"]           # {"from": 1, "to": 3, "objects": False, "scope": "TODAY"}
        raise_turn = next((i for i, t in enumerate(turns) if re.search(r"단계", t.said) and re.search(rf"{wr['to']}\s*단계", t.said) and re.search(r"소리|볼륨|알람", t.said)), None)
        add("볼륨 상승: 내일 시작 단계 변화를 알림", HARD, raise_turn is not None, all_ai[:160])
        add("볼륨 상승: 알림을 한 번만 함", SOFT, sum(1 for t in turns if re.search(rf"{wr['to']}\s*단계", t.said)) <= 2, "")
        ucalls = log.calls("update_setting")
        if wr.get("objects"):
            want = {"field": "wake_volume", "value": str(wr["from"]), "scope": wr.get("scope", "TODAY")}
            got = [dict(c["args"], scope=c["args"].get("scope", "ALWAYS")) for c in ucalls]
            add("볼륨 상승: 반대하면 이전 단계로 호출", HARD, got == [want], f"실제={got} 기대={want}")
            ci = next((i for i, t in enumerate(turns) if "update_setting" in _names(t)), None)
            add("볼륨 상승: 알린 뒤에 호출함", HARD, ci is not None and raise_turn is not None and ci > raise_turn, f"알림 턴={raise_turn}, 호출 턴={ci}")
            tail = " ".join(t.said for t in turns[ci:ci + 2]) if ci is not None else ""
            add("볼륨 상승: 이전 단계로 둔다고 알림", HARD, bool(re.search(rf"{wr['from']}\s*단계|그대로|유지|두", tail)), tail[:140])
        else:
            add("볼륨 상승: 반대가 없으면 호출하지 않음", HARD, not ucalls, str(ucalls))

    if not e.get("snooze"):          # 미루겠다고 하지 않았는데 알람을 미루면 안 된다(졸리다는 말은 미루는 요청이 아니다)
        asked = log.calls("reschedule_alarm")
        add("알람 미루기: 요청이 없는데 미루지 않음", HARD, not asked, str(asked))

    kind = scn.kind
    if (kind == "briefing" and ("snooze" in e or "settings" in e)) or (kind == "coaching" and "snooze" in e):         # 말로 알람 미루기(11/20 회의 1)·설정 변경(회의 4): 일반 브리핑 흐름(확인 질문 → 4~7문장 브리핑)과 다른 흐름이라 따로 본다
        want = e.get("snooze") or {}                           # {"minutes_later": 10} / {"at_time": "09:00"} / {}(미루지 않음, 한도 초과 등)
        calls = log.calls("reschedule_alarm")
        ti = next((i for i, t in enumerate(turns) if "reschedule_alarm" in _names(t)), None)
        said = turns[ti].said if ti is not None else ""
        if "snooze" not in e:
            pass
        elif want:
            add("미루기: reschedule_alarm을 한 번 호출", HARD, len(calls) == 1, f"호출 {len(calls)}번")
            got = calls[0]["args"] if calls else {}
            add("미루기: 시간 값이 기대와 같음", HARD, got == want, f"실제={got} 기대={want}")
            add("미루기: 호출한 응답에서 다시 맞췄다고 알림(앱이 통화를 저절로 끝내므로 직접 종료하라고 하지 않음)", HARD, "맞췄" in said and "종료" not in said, said[:140])
            add("미루기: 안내 뒤 다른 종료·시작 함수를 부르지 않음", HARD, not any(n in ("end_session", "start_routine") for t in turns for n in _names(t)), "")
            if kind == "coaching":                   # 단계 진행 중 미루기: 같은 응답에서 단계 판정·시간 조정 함수를 부르지 않는다
                add("미루기: 같은 응답에서 단계 판정·조정 함수를 부르지 않음", HARD,
                    ti is not None and not any(n in ("report_step_result", "adjust_step_time", "select_stretch_area") for n in _names(turns[ti])), str(_names(turns[ti])) if ti is not None else "")
            add("미루기: 연결이 reschedule_alarm으로 끝남", HARD, log.ended_by == "reschedule_alarm", f"종료 방식={log.ended_by}")
            add("미루기: 안내가 날씨·일정 브리핑으로 이어지지 않음", HARD, not re.search(r"날씨|일정|포춘|루틴", said), said[:140])
            if e.get("asks_first"):                  # 시간을 말하지 않았으면 한 번만 되묻고, 답을 들은 뒤에 호출한다
                add("미루기: 시간이 없으면 한 번 되묻고 답을 들은 뒤 호출함", HARD, ti is not None and ti >= 2 and "?" in turns[ti - 1].said, f"호출 턴={ti}, 앞 응답={turns[ti - 1].said[:80] if ti else ''!r}")
            if e.get("asks_over_limit"):             # 60분을 넘으면 한도를 알리고 되물은 뒤 호출한다
                add("미루기: 한도(60분)를 알리고 되물은 뒤 호출함", HARD, ti is not None and ti >= 2 and "60" in turns[ti - 1].said and "?" in turns[ti - 1].said, f"앞 응답={turns[ti - 1].said[:100] if ti else ''!r}")
        else:
            add("미루기: 알람을 미루지 않음", HARD, not calls, str(calls))
            add("미루기: 함수 없이 다시 맞췄다고 거짓으로 말하지 않음", HARD, "맞췄" not in all_ai and "미뤘어요" not in all_ai.replace("이미", "").replace("번 미뤘", ""), all_ai[:160])
            add("미루기: 이미 여러 번 미뤘다고 부드럽게 알림", SOFT, bool(re.search(r"이미|번 미|더 미룰", all_ai)), all_ai[:140])
    elif kind == "night":
        first = texts[0] if texts else ""
        add("밤: 첫 마디 2문장 이하, 질문 하나", HARD, len(sentences(first)) <= 2 and first.count("?") == 1, first)
        awkward = [s for t in texts for s in sentences(t) if re.search(r"오늘은\s*내일", s)]
        add("밤: '오늘은 내일…'처럼 어색한 문장 없음", HARD, not awkward, "; ".join(awkward[:2]))
        asked_tomorrow = [s for t in texts for s in sentences(t) if "?" in s and "내일" in s and re.search(r"일정|챙겨|목표|해내|걱정|특별한", s)]
        if e.get("asks_tomorrow"):
            add("밤: 내일 목표·특이사항을 물어봄", SOFT, bool(asked_tomorrow), "내일 이야기를 묻는 질문이 없음")
        # 일정이 없고 별일 없다고 하는 날: 작고 구체적인 일(식사·산책·만난 사람 등)을 물어 일기 재료를 얻는다.
        # '기억에 남는 순간이 있나요?' 같은 막연한 질문은 세지 않고, 요약 확인 질문("…맞아요?")도 세지 않는다
        small = re.compile(r"먹|식사|점심|저녁|간식|커피|산책|걷|만나|연락|통화")
        confirm = re.compile(r"맞(아요|죠|지|나요|을까요)[^.?]{0,12}\?|그렇죠\?|(요약|정리)(해 (볼까요|봐도 될까요|볼게요)|하면 (될까요|맞을까요))")
        small_q = [i for i, t in enumerate(turns) if i >= 1 and ("?" in t.said or "궁금해요" in t.said or "알려" in t.said) and small.search(t.said) and not confirm.search(t.said)]
        if e.get("asks_small"):
            add("밤: 별일 없는 날 작고 구체적인 일을 물어봄", HARD, bool(small_q), "식사·산책·사람처럼 구체적인 질문이 없음")
            add("밤: 작은 일 질문은 3번 이하", SOFT, len(small_q) <= 3, f"{len(small_q)}번 {small_q}")
        first_tom = next((i for i, t in enumerate(turns) if re.search(r"내일", t.said) and "?" in t.said and re.search(r"일정|챙겨|목표|해내|걱정|특별한|하고 싶|계획", t.said)), None)
        if e.get("tomorrow_after_talk"):      # 하루 이야기를 두 번 이상 들은 뒤에 내일 이야기로 넘어간다 (첫 답을 듣자마자 서두르지 않음)
            add("밤: 하루 이야기를 듣은 뒤에 내일 이야기를 물음", HARD, first_tom is None or first_tom >= 2, f"내일 질문 턴={first_tom}")
        notes_q = [i for i, t in enumerate(turns) if "?" in t.said and re.search(r"내일", t.said) and re.search(r"챙겨|걱정|특별한|특이|미리", t.said)]
        if e.get("asks_notes"):      # 내일 목표를 들은 뒤 특이사항도 한 번 물어야 한다 (목표만 듣고 마무리하지 않음)
            add("밤: 내일 특이사항(미리 챙길 일·걱정)을 물음", HARD, bool(notes_q), "특이사항 질문이 없음")
        if e.get("proposes_override"):      # 피곤하다고 하면 마무리 전에 알람·코칭 조정을 한 번 제안한다
            prop = [i for i, t in enumerate(turns) if "?" in t.said and "알람" in t.said and re.search(r"늦추|쉬어|조정|미룰|바꿀", t.said)]
            end_i2 = next((i for i, t in enumerate(turns) if "end_session" in _names(t)), len(turns))
            add("밤: 피곤해 보일 때 마무리 전에 알람·코칭 조정을 제안함", HARD, bool(prop) and prop[0] < end_i2, f"제안 턴={prop}, 종료 턴={end_i2}")
            add("밤: 알람·코칭 조정 제안은 대화 전체에서 한 번", HARD, len(prop) <= 1, f"{prop}번째 턴")
        recap = [i for i, t in enumerate(turns) if confirm.search(t.said) and re.search(r"하루|오늘", t.said) and not re.search(r"알람|코칭|항목|분으로|초로|시로|시 반", t.said)]
        add("밤: 하루를 요약해 맞는지 되묻지 않음", HARD, not recap, f"{recap}번째 턴: {turns[recap[0]].said if recap else ''}")
        if e.get("ends_cleanly"):      # 마무리는 묻지 않고 알린 뒤 같은 응답에서 끝내고, 작별 인사는 한 번만 한다 (덕담을 되풀이하며 대화가 안 끝나는 문제)
            ft = [i for i, t in enumerate(turns) if i >= 1 and FAREWELL.search(t.said)]   # 첫 마디의 "편안한 밤이에요" 같은 인사는 작별이 아니다
            add("밤: 작별 인사는 한 번만 하고 그 응답에서 대화를 끝냄", HARD,
                log.ended_by == "end_session" and len(ft) <= 1 and all("end_session" in _names(turns[i]) for i in ft), f"작별 인사 턴={ft}, 종료={log.ended_by}")
            asked = [i for i, t in enumerate(turns) if WRAP_Q.search(t.said)]
            add("밤: 마무리해도 되는지 묻지 않음", HARD, not asked, f"{asked}번째 턴에서 마무리를 물음")
            end_i = next((i for i, t in enumerate(turns) if "end_session" in _names(t)), None)
            if end_i is not None and not END_INTENT.search(turns[end_i].heard):      # AI가 스스로 마무리한 경우: 내일 이야기가 끝난 뒤여야 한다
                add("밤: 내일 이야기를 마친 뒤에 마무리함", HARD, first_tom is not None and end_i > first_tom, f"내일 질문 턴={first_tom}, 종료 턴={end_i}")
                add("밤: 마무리하는 응답은 질문으로 끝나지 않음", HARD, not turns[end_i].said.strip().endswith("?"), turns[end_i].said)
                add("밤: 마무리에서 내일의 나에게 남길 한마디 녹음을 알림", HARD, "녹음" in turns[end_i].said, turns[end_i].said)
        if "small_stop_from" in e:    # 말하기 싫다고 한 뒤에는 작은 일을 더 묻지 않는다
            late = [i for i in small_q if i >= e["small_stop_from"]]
            add("밤: 말하기 싫다고 한 뒤에는 작은 일을 더 묻지 않음", HARD, not late, f"{late}번째 턴")
        for i, t in enumerate(turns[1:], 1):
            farewell = "end_session" in _names(t) or bool(END_INTENT.search(t.heard))
            relaxed = bool(re.search(r"하루|알람|코칭", t.said)) or "update_setting" in _names(t) or bool(re.search(r"바꿨|적용돼|단계", t.said))       # 하루 요약 확인, 내일 아침 조정 제안·확인은 3문장까지
            limit = 4 if ("update_setting" in _names(t) and "wake_raise" in e) else (3 if relaxed else 2)        # 볼륨 상승에 반대해 이전 단계로 두는 응답은 알림 + 마무리까지 4문장
            add(f"밤: {i}번째 답변 {limit}문장 이하", HARD, len(sentences(t.said)) <= limit, t.said)
            if not farewell and not ("wake_raise" in e and re.search(r"단계", t.said)):      # 볼륨 상승 알림·확인은 질문으로 끝나지 않아도 된다(반대 여부는 사용자가 알아서 말함)
                add(f"밤: {i}번째 답변이 질문으로 끝남", SOFT, t.said.strip().endswith("?"), t.said)
        if e.get("event_words"):
            add("밤: 일정을 짚으며 시작", SOFT, any(w in first for w in e["event_words"]), first)
            add("밤: 첫 질문은 일정이 잘 끝났는지만 묻고 기분은 묻지 않음", SOFT, not re.search(r"기분|느낌|어떠셨|어땠", first), first)
        if e.get("open_day_q") and len(texts) > 1:      # 두 번째 질문은 하루·한 일·기분을 한 번에 묻는 열린 질문이고, 다른 일정을 하나씩 짚지 않는다
            second = texts[1]
            add("밤: 두 번째 질문이 하루·한 일·기분을 한 질문으로 물음", SOFT,
                second.count("?") == 1 and "하루" in second and bool(re.search(r"기분", second)) and bool(re.search(r"뭘|뭐|무엇|어떻게 (보|지)", second)), second)
            titles = [ev.title for ev in getattr(scn.inp, "events_today", ())]
            asked = {ti for t in texts for sn in sentences(t) if "?" in sn for ti in titles if ti in sn}
            add("밤: 일정을 하나씩 짚으며 묻지 않음(질문에 나온 일정 1개 이하)", SOFT, len(asked) <= 1, f"질문에 나온 일정={sorted(asked)}")
        if e.get("tomorrow_first_words"):
            add("밤: 내일 첫 일정을 짚음", SOFT, any(w in all_ai for w in e["tomorrow_first_words"]), "내일 첫 일정 언급이 없음")
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
        if e.get("ready_all") and brief.strip():
            add("브리핑: 마무리 질문이 이어질 순서를 모두 알림", SOFT, all(w in sentences(brief)[-1] for w in e["ready_all"]), sentences(brief)[-1])
        for i, t in enumerate(turns):
            if "start_routine" in _names(t):
                add("브리핑: 준비됐다는 답 뒤에만 시작", HARD, i >= 2 and bool(READY.search(t.heard)), f"{i}번째 턴, 사용자 말={t.heard!r}")
        if e.get("expect_start"):
            add("브리핑: 준비됐다고 하면 start_routine 호출", HARD, log.ended_by == "start_routine", f"종료 방식={log.ended_by}")

    elif kind == "fortune":
        want = re.sub(r"[\s.!?~…,\"'‘’“”]", "", e.get("fortune_text", ""))
        norm = lambda s: re.sub(r"[\s.!?~…,\"'‘’“”]", "", s)
        first = norm(texts[0]) if texts else ""
        add("포춘: 문장을 한 글자도 바꾸지 않고 읽음", HARD, bool(want) and want in first, f"읽은 말={texts[0] if texts else ''!r}")
        extra_chars = len(first) - len(want) if want in first else None
        add("포춘: 소개 한 문장 외에 덧붙이지 않음", SOFT, extra_chars is not None and extra_chars <= (30 if e.get("intro_words") else 18), f"덧붙은 글자 {extra_chars}자: {texts[0] if texts else ''}")
        add("포춘: 첫 응답에서 한 번만 읽음", HARD, first.count(want) == 1 if want else False, f"{first.count(want) if want else 0}번 읽음")
        add("포춘: 아침 첫인사를 반복하지 않음", HARD, not REGREET.search(texts[0] if texts else ""), texts[0] if texts else "")
        if e.get("intro_words"):
            add("포춘: 소개가 만든 재료(어젯밤 한마디)를 밝힘", SOFT, any(w in (texts[0] if texts else "") for w in e["intro_words"]), texts[0] if texts else "")
        add("포춘: 읽는 중 함수 호출 없음", HARD, not (turns and turns[0].calls), str(turns[0].calls if turns else ""))
        add("포춘: 읽은 뒤 질문하지 않음", HARD, "?" not in (texts[0] if texts else ""), texts[0] if texts else "")
        if e.get("replay") and len(turns) > 1:
            add("포춘: 다시 읽어 달라고 하면 그대로 한 번 더 읽음", HARD, want in norm(turns[1].said), f"{turns[1].said!r}")

    elif kind == "coaching":
        if getattr(scn.inp, "morning_call_type", "") == "CONTENT" and texts:
            add("콘텐츠: 아침 첫인사를 반복하지 않음", HARD, not REGREET.search(all_ai), all_ai[:120])
            if e.get("bridge_words"):
                add("콘텐츠: 첫 안내가 포춘에 이어 콘텐츠를 시작한다고 연결함", SOFT, any(w in texts[0] for w in e["bridge_words"]), texts[0])
        add("코칭: 사용자 답 전에 판정 호출 없음", HARD, not (turns and turns[0].calls), str(turns[0].calls if turns else ""))
        orders = sorted(e.get("verdicts", {}))           # 재연결이면 남은 단계부터라 단계 번호가 1부터가 아닐 수 있다
        skip_turns = set(e.get("no_verdict_turns", ()))   # 시간 변경만 한 턴(그 턴에는 판정할 단계가 없다)
        slots = [i for i in range(1, len(turns)) if i not in skip_turns]
        turn_of = {o: slots[j] for j, o in enumerate(orders) if j < len(slots)}       # 단계 번호 → 그 단계의 답을 듣는 턴
        turn_of.update(e.get("report_turn", {}))          # 한 단계 안에서 여러 번 주고받는 게임(끝말잇기)은 판정이 마지막 응답에 온다
        wrong_turn = [(i, c["args"].get("step_order")) for i, t in enumerate(turns) for c in t.calls
                      if c["name"] == "report_step_result" and i >= 1 and orders and turn_of.get(c["args"].get("step_order")) != i]
        # 11/21: 틀린 판정을 미리/늦음으로 나누고 무응답 턴을 따로 센다. 사용자가 "했어"라고 했는데 AI가 말도 호출도 안 하면 다음 판정이 한 턴 늦어지는데,
        # 이건 'AI가 앞서 나감'이 아니다. 가상 음성(합성)이 짧으면 인식이 안 돼 생기는 측정 오차일 수 있어 --realistic으로 다시 확인한다.
        silent_turns = [i for i in range(1, len(turns) - 1) if not turns[i].said.strip() and not turns[i].calls]

        def _order_kind(i, o):
            want_turn = turn_of.get(o)
            kind = "미리" if want_turn is not None and i < want_turn else ("늦음" if want_turn is not None else "모르는 단계")
            return kind + (", 그 단계 답을 듣는 턴이 무응답" if want_turn in silent_turns else "")
        add("코칭: 한 턴에 방금 답한 단계만 판정", HARD, not wrong_turn, f"(턴, 판정한 단계, 종류)={[(i, o, _order_kind(i, o)) for i, o in wrong_turn]}")
        add("코칭: 사용자 말에 AI가 응답함(무응답 턴 없음)", SOFT, not silent_turns, f"무응답 턴={silent_turns} (합성 음성 인식 누락일 수 있음: --realistic으로 확인)")
        last_verdict = {}
        for c in log.calls("report_step_result"):
            last_verdict[c["args"].get("step_order")] = c["args"].get("verdict")
        want = e.get("verdicts", {})
        alt = e.get("verdict_any_of", {})               # 끝말잇기: 한두 번 주고받고 '패스'한 경우 done과 skipped 모두 합리적이다
        ok_verdict = set(last_verdict) == set(want) and all(last_verdict[k] == want[k] or last_verdict[k] in alt.get(k, ()) for k in want)
        add("코칭: 판정이 기대와 같음", HARD, ok_verdict, f"실제={last_verdict} 기대={want}{' 또는 ' + str(alt) if alt else ''}")
        for step, sent in (e.get("english_repeat") or {}).items():       # 영어 따라 읽기(11/20 회의 7): 사용자가 따라 말한 응답에서 영어 문장을 한 번 더 들려 준다
            tt = turns[turn_of[step]].said if turn_of.get(step) is not None and turn_of[step] < len(turns) else ""
            _n = lambda s: re.sub(r"[^0-9a-z가-힣]", "", s.lower())
            add(f"영어: {step}번 따라 말한 뒤 영어 문장을 한 번 더 들려 줌", HARD, _n(sent) in _n(tt), tt[:140])
        if e.get("handoff_words") and want:      # 순서표에서 코칭 뒤에 다음 단계(포춘)가 있으면 마지막 단계를 판정하는 응답이 다음 순서로 넘기는 말이어야 한다(앱이 바로 넘어가므로 end_session은 부르지 않는다)
            last_i = max((i for i, t in enumerate(turns) if any(c["name"] == "report_step_result" and c["args"].get("step_order") == max(want) for c in t.calls)), default=None)
            said_last = turns[last_i].said if last_i is not None else ""
            add("코칭: 마지막 단계 뒤 다음 순서로 넘기는 말", HARD, any(w in said_last for w in e["handoff_words"]), said_last[:120])
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
        if e.get("encourages"):        # 단계를 마칠 때마다 짧은 반응에 그치지 않고, 중간중간 독려 한마디(조금만 더, 거의 다 왔어요 등)를 넣는다
            last = max(want) if want else None
            moves = [t for i, t in enumerate(turns) if i >= 1 and any(c["name"] == "report_step_result" and c["args"].get("step_order") != last for c in t.calls)]
            cheered = [t for t in moves if ENCOURAGE.search(t.said)]
            need = max(1, (len(moves) + 1) // 2)
            add("코칭: 단계 사이에 독려 한마디", HARD, len(cheered) >= need, f"단계 전환 {len(moves)}번 중 독려 {len(cheered)}번 (필요 {need}번)")
        if e.get("answer_correct") is not None:      # 퀴즈: 모든 판정에 정답 여부를 넣고, 사용자가 일부러 틀린 답도 틀렸다고 기록해야 한다
            reps = log.calls("report_step_result")
            add("퀴즈: 모든 판정에 answer_correct를 넣음", HARD, all(isinstance(c["args"].get("answer_correct"), bool) for c in reps), str([c["args"] for c in reps]))
            got_ac = {}
            for c in reps:
                got_ac[c["args"].get("step_order")] = c["args"].get("answer_correct")
            add("퀴즈: 맞고 틀림이 기대와 같음", HARD, got_ac == e["answer_correct"], f"실제={got_ac} 기대={e['answer_correct']}")
            for step, word in (e.get("reactions") or {}).items():
                tt = turns[turn_of[step]].said if turn_of.get(step) is not None and turn_of[step] < len(turns) else ""
                add(f"퀴즈: {step}번 답에 '{word}…'로 반응(앱이 O/X로 읽는 표현)", SOFT, word in tt, tt)
        if e.get("safety_first"):                    # 스트레칭: 첫 안내에서 안전 한마디(멈추기·어지러우면 잡기)
            first = turns[0].said if turns else ""
            add("스트레칭: 첫 안내에 안전 한마디", SOFT, "멈추" in first and bool(re.search(r"어지러|벽|침대", first)), first)
        if e.get("pain_note"):                       # 아프다고 하면 무리하지 않게 받아 주고 효과·진단을 말하지 않는다
            tt = turns[e["pain_note"]].said if len(turns) > e["pain_note"] else ""
            add("스트레칭: 아프다고 하면 무리하지 않게 받아 줌", HARD, bool(re.search(r"괜찮|무리|쉬|건너|멈추|넘어", tt)) and not re.search(r"치료|진단|효과|디스크", tt), tt)
        if e.get("start_word"):                      # 끝말잇기: 시작 단어로 시작하고, 한 번에 단어 하나씩 주고받는다
            add("끝말잇기: 시작 단어로 시작", HARD, e["start_word"] in (texts[0] if texts else ""), texts[0] if texts else "")
            add("끝말잇기: 첫 안내는 사용자 차례에서 멈춤(질문 이어서 하지 않음)", SOFT, len(sentences(texts[0])) <= 3 if texts else False, texts[0] if texts else "")
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
