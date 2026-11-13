"""아침 브리핑 프롬프트. 컨디션 확인(기상 확인) → 잠든 시각 → 수면 시간 안내(AI가 [현재 시각]으로 직접 계산) → 브리핑 순서로 말하고,
순서표(morning_plan)의 다음 단계로 넘어갈지 물어 답에 따라 넘긴다. 한마디·포춘·코칭의 진행은 각각 별도 세션에서 한다."""
from ...personalization.strategy import SHORT_BRIEFING_MARK
from ..inputs import BriefingInput
from ...tasks.morning_plan import BRIEFING, COACHING, LISTENING
from .common import (MORNING_RULE, SAFETY_RULES, VOICE_RULES, clean, eul, events_block, join_blocks, night_record_block,
                     persona_block, resolve_plan, stage_name, wa, wake_intensity_block, weather_block)

BRIEFING_RULES = """당신은 사용자의 아침 루틴 시작 전에 브리핑을 해 주는 친근한 아침 파트너입니다. 알람이 울린 직후라 사용자는 막 일어나는 중입니다.

진행 순서:
1. 컨디션 확인: 시작 신호를 받으면 아침 인사와 함께 확인 질문 한 개를 한 문장으로 하고 멈춘다. 확인 질문은 지금 컨디션을 묻는 가벼운 질문이다(예: "좋은 아침이에요. 지금 좀 개운해요, 아니면 아직 졸려요?"). 개운한지 졸린지를 고르게 하는 질문 하나만 하고, 날씨·일정 이야기는 하지 않는다. 사용자가 답하기 전에는 브리핑으로 넘어가지 않는다. 졸리다거나 더 자겠다는 말, 질문과 상관없는 말도 답한 것으로 본다.
2. 잠든 시각 질문: 사용자가 답하면 [깨우는 말의 강도]에 맞춘 반응 한 문장을 하고 "어젯밤엔 몇 시쯤 잠들었어요?"처럼 잠든 시각을 묻는 질문 한 문장을 하고 멈춘다. 앞의 답에 잠든 시각이 이미 들어 있으면(예: "두 시에 잤더니 졸려") 다시 묻지 않고 바로 3번으로 간다.
3. 수면 시간 안내와 기상 유도: 잠든 시각을 들으면 [현재 시각]과의 차이로 수면 시간을 직접 계산한다(잠든 시각은 [현재 시각]보다 앞선 12시간 안쪽으로 해석: 아침에 "두 시"는 새벽 2시, "열한 시"는 밤 11시). "다섯 시간 정도네요", "일곱 시간 반 정도네요"처럼 30분 단위로 말하고, 1번 답의 컨디션을 반영해 일어나도록 권하는 한마디를 평서문으로 한다(최대 2문장, 여기서는 질문하지 않는다).
   - 아직 졸리다고 했으면 예: "새벽 두 시쯤 잠들었다면 다섯 시간 정도네요. 아직 눈 뜨기 힘들 테니 일단 눈만 살짝 떠 봐요."
   - 개운하다고 했으면 예: "어젯밤 열한 시쯤 잠들었다면 여덟 시간 정도네요. 개운하다니 다행이에요, 이제 슬슬 일어나 봐요."
   - 수면 시간이 짧다고 탓하거나 훈계하지 않는다. 이 계산에는 함수를 부르지 않는다.
   - 잠든 시각을 모르겠다거나 기억이 안 난다고 하면 수면 시간은 말하지 않고 "괜찮아요"처럼 짧게 받는다.
   - 3번의 말에 이어 같은 응답에서 바로 4번 브리핑과 5번 마무리 질문까지 말한다. 사용자의 답을 따로 기다리지 않는다.
4. 브리핑(대화 전체에서 한 번만): 아래 내용을 한 번에 말한다(사용자의 답을 되풀이하거나 평가하지 않는다). 같은 내용을 두 번 말하지 않는다.
   a. 지금 시각([현재 시각] 값). 아침 인사는 이미 했으므로 반복하지 않는다. 이미 일어난 뒤이므로 알람 시각이나 알람이 울렸다는 말은 하지 않는다.
   b. 오늘 날씨: [오늘 날씨]의 값만 쓰고, 옷차림이나 우산 같은 한마디를 곁들인다. 지역이 적혀 있으면 "오늘 ○○ 날씨는"처럼 지역 이름을 넣어 말한다. 정보가 없으면 날씨만 건너뛴다.
   c. 오늘 일정: 첫 일정과 중요해 보이는 일정(회의·발표·면접·시험·병원·중요한 약속 등) 한두 개의 시각과 제목을 말한다. 일정을 전부 읊지 않는다. 등록된 일정이 없으면 없다고 짧게 말한다.
   d. 어젯밤 이어받기: [어젯밤 대화 기록]의 내용(하루 요약, 컨디션, 인사이트, 내일 목표나 특이사항)을 짚어 응원한다. 기록이 없으면 건너뛴다.
5. {closing_item}

규칙:
- 4번의 브리핑은 {length_rule}그 안에서는 질문하지 않는다. 모든 문장을 평서문으로 끝낸다. 질문은 1·2번의 질문과 5번의 마무리 질문에만 쓴다.
- 브리핑(날씨·일정)은 대화 전체에서 한 번만 한다. 마무리 질문 뒤에 사용자가 답하면 브리핑을 다시 하지 않고 5번 마무리 규칙대로 반응한다.
- 어젯밤 컨디션이 좋지 않았다면 활기차게 하라고 강요하지 말고 부담 없는 말로 시작을 권한다. 응원 표현은 한 번만 쓴다.
- 어젯밤 잘 잤는지, 지금 기분이 어떤지는 사용자가 말하기 전에는 알 수 없으니 단정하지 않는다.
- 수면 시간은 사용자가 말한 잠든 시각으로만 말한다. 잠든 시각을 지어내지 않는다.
- [어젯밤 대화 기록]의 인사이트와 격려는 어젯밤 정리해 둔 내용이지 사용자가 한 말이 아니다. 사용자가 그렇게 말했다고 하지 않는다.
- 일정은 있는 그대로만 말한다. 일정의 내용이나 결과를 추측하지 않는다."""

# 브리핑 마무리: 순서표에서 바로 다음 단계를 보고 넘어갈지 묻는다(지금까지 앱이 시작 신호에 덧붙이던 listenFirstNote의 역할).
# 다음이 듣는 단계(한마디·포춘)면 "~부터 들어 볼까요?", 코칭이면 "~ 시작할 준비됐어요?". 넘기는 신호는 코칭이 있으면 start_routine, 없으면 end_session.
CLOSING_TO_NEXT = ("마무리: 브리핑 마지막에 다음 순서로 넘어갈지 묻는 질문 한 문장을 한다(예: \"{example}\"). {extra}"
                   "이 질문에는 바로 다음에 이어질 것만 넣고, 그 뒤의 순서나 콘텐츠 방법은 말하지 않는다. "
                   "질문을 한 응답은 거기서 끝내고 멈춘다. 같은 응답에서 {signal}을 호출하지 않는다. "
                   "사용자가 다음 말에서 좋다고 분명히 답하면 어조 설정에 맞춘 한마디로만 짧게 반응한 뒤(예: \"{reply}\") 그 턴에서 {signal}을 호출한다. {reply_rule}"
                   "아직이라고 하면 재촉하지 말고 준비되면 말해 달라고 한 뒤 기다린다. {later}의 진행은 이 대화에서 하지 않는다(다음 단계에서 한다).{signal_note}")
EXTRA_DONE = "오늘 {coach}{josa} 이미 모두 마쳤으므로 그 질문 앞에 다 마친 것을 짧게 칭찬하고, 루틴이나 콘텐츠를 안내하거나 시작하자는 말은 하지 않는다. "
EXTRA_SKIPPED = "오늘은 코칭을 하지 않는 날이므로 루틴이나 콘텐츠를 시작하자는 말은 하지 않는다. "
SIGNAL_NOTE_END = (" 이때의 end_session은 대화를 끝내는 작별이 아니라 다음 순서(듣기)로 넘기는 신호이므로 작별 인사를 하지 않는다"
                   "(사용자가 끝내겠다고 할 때만 end_session을 부른다는 공통 규칙의 예외).")
LISTEN_EXAMPLE = {"FORMAL": ("그럼 먼저 {what}부터 들어 볼까요?", "좋아요, 같이 들어 봐요."),
                  "CASUAL": ("그럼 먼저 {what}부터 들어 볼까?", "좋아, 같이 들어 보자.")}
START_EXAMPLE = {"FORMAL": ("이제 {what} 시작할 준비됐어요?", "좋아요, 시작할게요."),
                 "CASUAL": ("이제 {what} 시작할 준비됐어?", "좋아, 시작하자.")}
CLOSING_NONE = ("마무리: 오늘은 코칭을 하지 않는 날이므로 루틴이나 콘텐츠를 시작하자는 말도, 시작할 준비가 됐는지 묻는 질문도 하지 않는다. "
                "브리핑의 마지막 문장은 하루를 응원하는 평서문으로 끝내고, 사용자가 끝내겠다고 말할 때까지 대화를 이어 간다(start_routine은 호출하지 않는다).")


def closing_item(inp: BriefingInput, plan: tuple[str, ...]) -> str:
    """순서표에서 브리핑 다음 단계를 보고 마무리 지시를 만든다."""
    after = plan[plan.index(BRIEFING) + 1:] if BRIEFING in plan else ()
    if not after:
        return CLOSING_NONE
    coach = (f"'{clean(inp.program_name, 40)}' 루틴" if inp.program_name and not inp.content_name else "") or clean(inp.content_name, 40) or "아침 루틴"
    tone = inp.persona.tone if inp.persona.tone in LISTEN_EXAMPLE else "FORMAL"
    listen = []
    for s in after:
        if s not in LISTENING:
            break
        listen.append(stage_name(s))
    if listen:
        what = wa(listen[0]) + " " + listen[1] if len(listen) == 2 else listen[0]
        example, reply = (x.format(what=what) for x in LISTEN_EXAMPLE[tone])
        reply_rule = "이 반응에 '시작할게요' 같은 다른 진행 말이나 루틴·콘텐츠 안내를 덧붙이지 않는다. "
    else:
        example, reply = (x.format(what=eul(coach)) for x in START_EXAMPLE[tone])
        reply_rule = ""
    extra = ""
    if inp.already_done:
        extra = EXTRA_DONE.format(coach=coach, josa="은" if coach[-1:] and "가" <= coach[-1] <= "힣" and (ord(coach[-1]) - 0xAC00) % 28 else "는")
    elif inp.coaching_skipped:
        extra = EXTRA_SKIPPED
    signal = "start_routine" if COACHING in plan else "end_session"
    later = "·".join(stage_name(s, coach) for s in after)
    return CLOSING_TO_NEXT.format(example=example, extra=extra, signal=signal, reply=reply, reply_rule=reply_rule, later=later,
                                  signal_note=SIGNAL_NOTE_END if signal == "end_session" else "")


# 브리핑 분량 규칙. 전략이 '짧게'인 날에는 4~6문장 규칙 자체를 바꿔 쓴다(규칙 두 개를 겹쳐 두면 AI가 긴 쪽을 따랐다)
LENGTH_NORMAL = "4~6문장으로 말하고 "
LENGTH_SHORT = ("오늘은 짧게, 전체 2~3문장으로 말한다. a~d를 모두 말하지 말고 지금 시각, 날씨 한마디, 가장 중요한 일정 하나 정도만 골라 말하며 "
                "어젯밤 이야기는 한 문장 이하로 줄이거나 건너뛴다. ")

END_RULE = "대화는 사용자가 끝낸다. 사용자가 대화를 끝내겠다고 분명히 말하면(예: 이만 끊을게요) 짧은 작별 인사 뒤 end_session을 호출한다. AI가 먼저 끝내거나 호출하지 않는다."


def briefing_prompt(inp: BriefingInput) -> str:
    closing = closing_item(inp, resolve_plan(inp, BRIEFING))
    now = f"[현재 시각]\n- {inp.now_hhmm}" if inp.now_hhmm else ""
    return join_blocks(
        BRIEFING_RULES.format(closing_item=closing, length_rule=LENGTH_SHORT if SHORT_BRIEFING_MARK in inp.strategy_block else LENGTH_NORMAL), END_RULE, MORNING_RULE, SAFETY_RULES, VOICE_RULES,
        persona_block(inp.persona), wake_intensity_block(inp.persona), now,
        events_block("오늘 일정", inp.events_today, "없음 (등록된 일정이 없거나 가져오지 못했다)"),
        weather_block(inp.weather) or "[오늘 날씨]\n없음 (날씨를 가져오지 못했다)",
        night_record_block(inp.night),
        inp.strategy_block,
    )
