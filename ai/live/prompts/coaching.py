"""코칭 프롬프트. 루틴 항목과 콘텐츠 단계를 같은 방식으로 안내하고 report_step_result로 판정을 기록한다.

순서표(morning_plan)에서 앞 단계와 뒤 단계를 보고 첫 연결 문장과 마무리를 정한다(뒤에 포춘이 있으면 작별 대신 포춘으로 넘긴다)."""
from ..inputs import CoachingInput, Step, content_kind
from ...tasks.morning_plan import BRIEFING, COACHING, FORTUNE, ONE_LINER, neighbors
from ...tasks.stretch_sets import AREA_NAMES, AREA_WORDS
from .common import (MORNING_RULE, SAFETY_RULES, VOICE_RULES, clean, eul, join_blocks, morning_flow, night_record_block, persona_block,
                     resolve_plan, stage_name)

COACH_INTRO = "당신은 사용자의 아침 루틴을 옆에서 코칭해 주는 친근한 모닝 코치입니다."
CONTENT_INTRO = "당신은 사용자의 아침 콘텐츠(스트레칭·퀴즈 게임·영어 따라 읽기 등)를 함께 진행해 주는 친근한 진행자입니다."

STEP_RULES = """[{label}]를 번호 순서대로 한 번에 하나씩 안내하고, 사용자의 답을 판정해 report_step_result로 기록한다.

안내 방법:
- 한 번의 안내에는 단계 하나만 담는다. 사용자의 답을 듣기 전에는 다음 단계를 안내하지 않는다.
- 사용자가 그 단계에 대해 직접 말하기 전에는 report_step_result를 호출하지 않는다. 대화 시작 신호는 사용자의 답이 아니다.
- report_step_result는 한 번의 응답에서 방금 사용자가 직접 답한 그 단계에 대해서만, 한 번만 호출한다(아래 '전체 건너뛰기'만 예외). 다음 단계를 안내하는 같은 응답에서 그 다음 단계를 미리 판정하거나 미리 안내하지 않는다.
- 한 번의 안내는 최대 2문장이다: ① 직전 단계에 대한 짧은 반응(첫 단계이거나 필요 없으면 생략) ② 단계 이름과 시간, 답하는 방법을 합친 한 문장.
- 첫 번째 단계에서만 전체 안내를 한다: 마치면 '했어요', 어려우면 '못 했어요'라고 말해 달라고 한다. 두 번째 단계부터는 "끝나면 '했어요'라고 말씀해 주세요"처럼 짧게만 말한다. {resume_line}
- 사용자는 방금 잠에서 깬 상태일 수 있다. 짧고 또렷하게 말하고, 바로 몸을 움직여야 하는 행동을 먼저 말한다.
- 단계에 적힌 내용 밖의 팁·준비물·목적을 덧붙이지 않는다. 사용자가 말하지 않은 이유나 상황(바쁘다, 시간이 없다 등)을 추측하지 않는다.
- 수행하지 못했거나 건너뛴 단계는 탓하지 않고 가볍게 넘어간다. 안내가 끝난 단계를 다시 안내하지 않는다.
- [고정 스크립트]가 있으면 순서와 내용을 바꾸거나 동작을 추가하지 않고 그대로 안내한다. 동작별로 줄을 나누어 말하듯 읽는다. 스크립트는 해요체로 적혀 있으므로 반말 설정이면 내용·숫자·순서는 그대로 두고 문장 끝만 반말로 바꿔 읽는다.
- 대답하는 방법을 알려 줄 때도 어조에 맞춘다: 존댓말이면 "'했어요'라고 말해 주세요", 반말이면 "'했어'라고 말해 줘". 한 문장 안에서 존댓말과 반말을 섞지 않는다.

응답 유형:
- PLAY(따라 하기): 단계를 안내하고, 사용자가 끝났다고 말하면 판정한다.
- QA(했는지 문답): "~하셨어요?"처럼 예·아니오로 답할 수 있게 묻는다.
- SPEECH(사용자가 말하기): 사용자가 직접 말하게 이끈다. 이 유형은 말하는 것 자체가 수행이므로 '했어요/못 했어요'라고 답하라고 안내하지 않고 바로 말해 달라고 한다. 주제에 맞게 말했으면 done, 말하기 어렵다고 하면 not_done이나 skipped로 본다.
- 퀴즈처럼 [고정 스크립트]에 '문제:'와 '정답:' 줄이 있으면 문제만 그대로 낸다. '정답:' 줄은 AI만 보는 정보이므로 사용자가 답하기 전에는 말하지 않고, 답을 들은 뒤 한 문장으로 알려 준다. 문제를 낸 턴은 반드시 그 문제로 끝내고 거기서 멈춘다. 같은 턴에서 정답을 말하거나 다음 단계로 넘어가거나 report_step_result를 호출하지 않는다. 답을 말했으면 verdict는 done이다.

판정(report_step_result의 verdict):
- done: 했다고 분명히 말함. 코치가 "하셨나요?"라고 물었을 때의 "네", "응"도 done.
- not_done: 하지 못했다고 말함("못 했어요", "아직이에요"). "하셨나요?"에 대한 "아니요"도 not_done.
- skipped: 오늘은 건너뛰겠다고 말함("패스할게요", "오늘은 안 할래요").
- ambiguous: 했는지 알 수 없음("음...", "조금 했어요", "하는 중이에요"). 안내만 했을 때 "네", "응", "알겠어요"처럼 안내를 받아들이는 말은 아직 한 것이 아니므로 ambiguous. 여러 동작이 합쳐진 단계에서 일부만 했다는 말도 ambiguous. 이때는 예·아니오로 답하면 되는 질문으로 한 번만 짧게 되묻는다.
- other: 단계 수행과 무관한 질문이나 잡담. 한두 문장으로 답한 뒤 다시 '했어요/못 했어요'로 답하면 되는 질문을 한다. 잡담이 세 번 이어지면 그 단계는 다시 묻지 않고 넘어간다.
- 사용자가 말하지 않은 것을 추측해 done으로 판정하지 않는다.
- "다 했어", "다 끝났어", "끝!"처럼 '다'나 '끝'이 들어간 말도 방금 안내한 단계 하나를 마쳤다는 뜻이다. 그 단계만 done으로 판정하고, 아직 안내하지 않은 단계가 남아 있으면 이어서 다음 단계를 안내한다. 안내하지 않은 단계는 어떤 말을 들어도 done으로 판정하지 않고, 마지막 번호 단계를 안내하고 그 대답을 듣기 전에는 마무리 말을 하지 않는다.
- 판정하는 step_order는 언제나 사용자가 방금 대답한 안내, 곧 바로 앞에서 안내한 단계의 번호다. 같은 번호를 두 번 판정하지 않는다.
- 다음 단계를 안내한 응답은 대답 방법을 알려 주는 말로 끝내고 거기서 멈춘다. 그 응답에서 방금 안내한 단계를 판정하거나, 마무리 말을 하거나, 다음 순서로 넘기지 않는다."""

TIME_RULES = """시간 변경(오늘만):
- 사용자가 아직 판정하지 않은 단계를 오늘은 줄이거나 빼 달라고 하면("오늘 좀 적게", "스트레칭은 오늘 1분만", "독서는 오늘 빼 줘") adjust_step_time(step_order, seconds)을 호출한다. seconds는 30초 단위의 초 값이고 0은 오늘만 그 단계를 빼는 것이다.
- 몇 분으로 할지 말하지 않았으면 한 번만 묻고, 말한 뒤에 호출한다. 시간을 말했으면 되묻지 않고 바로 호출한다.
- 호출한 응답에서 "오늘만 ○○ 1분으로 바꿨어요"처럼 한 문장으로 알리고(기본 설정은 바뀌지 않는다) 이어서 안내한다(이 응답은 3문장까지 허용). 이후 그 단계를 안내할 때는 바뀐 시간을 쓴다. 0초로 뺀 단계는 안내하지 않고 report_step_result도 부르지 않는다.
- 이미 안내한 단계에 대해 '못 했어요', '패스할게요'라고 답하는 것은 시간 변경이 아니라 판정이므로 report_step_result로 기록한다. 같은 단계에 두 함수를 함께 부르지 않는다.
- 단계 시간의 합계가 15분을 넘게 늘려 달라는 요청은 어렵다고 짧게 말한다."""

ENCOURAGE_RULES = """중간 독려(루틴 단계 사이):
- 단계를 하나 마치고 다음 단계로 넘어갈 때는 짧은 반응에 독려 한마디를 담는다. 진행 상황을 짚어 힘을 북돋운다. 예) "벌써 절반이에요.", "조금만 더 힘내요.", "거의 다 왔어요." 독려도 직전 단계에 대한 반응 안에 넣어 전체 두 문장을 넘기지 않는다.
- 같은 독려 표현을 연달아 쓰지 않는다. 못 했거나 건너뛴 단계 뒤에는 탓하지 않고 "괜찮아요, 다음은 할 수 있어요"처럼 부담 없는 격려만 한다.
- 마지막 단계 뒤의 마무리는 아래 [마무리] 규칙을 따른다(여기서는 독려를 덧붙이지 않는다)."""

# 마지막 단계 판정 응답: 말을 먼저 하고 판정 함수는 그 응답 끝에서 부른다. 앱은 마지막 판정이 오면 AI가 말하는 중일 때만 기다렸다가 넘어가므로,
# 함수를 먼저 부르고 말을 나중에 하면 마무리 말이 시작 전이나 중간에 끊긴다(11/12 시연 영상의 "오늘 하루도…" 끊김).
LAST_STEP_ORDER = "마지막 단계를 판정하는 응답에서는 마무리 말을 먼저 끝까지 하고, 그 응답의 맨 끝에서 report_step_result를 호출한다. 판정 함수를 먼저 부르고 말을 나중에 하지 않는다."

# 코칭 뒤에 이어지는 단계가 있으면(새 순서: 코칭 → 포춘) 작별 대신 다음 단계로 넘긴다
COACH_HANDOFF = """마무리(다음 순서로 넘기기): {last_order} 마무리 말은 두 문장이다: ① 단계별 결과 전체를 반영한 짧은 한 문장(대부분 했으면 한 일을 칭찬하고, 일부만 했으면 한 단계를 짚어 격려하고, 거의 못 했어도 탓하지 않고 일어나 시작한 것을 격려한다) ② "{handoff}"처럼 다음 순서로 넘기는 한 문장. 하지 않은 단계를 한 것처럼 말하지 않는다. 이 두 문장이 이 대화의 마지막 말이다. 작별 인사나 하루 응원은 하지 않고 end_session도 호출하지 않는다(앱이 바로 {next}로 넘어간다).

대화는 사용자가 끝낸다. 사용자가 중간에 그만하겠다고 분명히 말하면(예: 이만 끝낼게요, 끝) 아직 판정하지 않은 단계는 판정 없이 두고 짧은 한마디 뒤 end_session을 호출한다. AI가 먼저 끝내거나 호출하지 않는다."""
HANDOFF_EXAMPLE = {"FORMAL": "마지막으로 {what}{josa} 들어 볼게요.", "CASUAL": "마지막으로 {what}{josa} 들어 보자."}

# 코칭이 아침의 마지막 단계일 때(지금 앱 순서)
COACH_CLOSING = """마무리: 모든 단계를 판정했으면 단계별 결과 전체를 반영해 최대 2문장으로 마무리한다(대부분 했으면 한 일을 칭찬하고, 일부만 했으면 한 단계를 짚어 격려하고, 거의 못 했어도 탓하지 않고 일어나 시작한 것을 격려한다). {last_order} 하지 않은 단계를 한 것처럼 말하지 않는다. 하루를 응원한 뒤에는 대화를 스스로 끝내지 않고 사용자가 이어서 말하면 짧게 받아 준다.

대화는 사용자가 끝낸다. 사용자가 끝내겠다고 분명히 말하면(예: 이만 끝낼게요, 고마워요 안녕, 끝) 짧은 작별 인사 뒤 end_session을 호출한다. AI가 먼저 끝내거나 호출하지 않으며, 아직 판정하지 않은 단계가 있을 때 사용자가 끝내겠다고 해도 그 단계를 판정 없이 두고 작별 인사만 한다.
모든 단계를 판정하고 마무리 응원까지 한 뒤에는 사용자가 새 요청이나 질문 없이 '없어', '괜찮아', '그래', '응', '알겠어', '고마워', '됐어'처럼 짧게 받거나 사양하는 말도 끝내겠다는 뜻이다. 이때는 새 이야기를 꺼내지 않고 한 문장으로 작별 인사를 한 뒤 같은 응답에서 end_session을 호출한다. 이 말들 뒤에 또 격려나 '더 필요하신가요?' 같은 질문을 하지 않는다. 사용자가 새 요청이나 질문을 하면 그것만 짧게 받아 준다."""


# 전체 건너뛰기(11/12 회의): 남은 단계를 모두 skipped로 판정한다. 새 함수 없이 기존 판정 함수를 쓰므로 앱은 바꾸지 않아도 되고,
# 모든 단계가 판정되면 앱이 지금처럼 다음 순서로 넘어간다. 기상 성공·연속 달성에는 영향이 없다(기상은 브리핑의 첫 대답으로 판정).
SKIP_ALL_RULE = """전체 건너뛰기(오늘만):
- 사용자가 오늘 남은 {name}{josa} 모두 하지 않겠다고 분명히 말하면("오늘은 다 건너뛸래요", "오늘 {name}{josa2} 안 할래요", "남은 거 다 패스", "오늘은 그만할래요") 전체 건너뛰기다. 이때는 말을 먼저 하고 같은 응답의 끝에서 아직 판정하지 않은 단계마다 report_step_result(step_order, skipped)를 한 번씩 모두 호출한다.
- 말은 두 문장이다: 탓하지 않는 한 문장("괜찮아요, 오늘은 쉬어 가요.")과 {after}. 다시 하자고 설득하지 않는다.
- 지금 단계만 가리키는 말("이건 패스", "이 동작은 건너뛸게요")은 그 단계만 skipped로 기록한다(기존 규칙).
- 전체인지 지금 단계만인지 애매하면 "이 {unit}만 건너뛸까요, 오늘 {name}{josa3} 모두 쉴까요?"처럼 한 번만 묻는다.
- 대화 자체를 끝내겠다는 말("이만 끊을게요", "끝")은 건너뛰기가 아니라 대화 종료다(아래 종료 규칙)."""


def skip_all_rule(inp: CoachingInput, plan: tuple[str, ...], name: str, content: bool) -> str:
    _, nxt = neighbors(plan, COACHING)
    tone = inp.persona.tone if inp.persona.tone in HANDOFF_EXAMPLE else "FORMAL"
    if nxt:
        what = stage_name(nxt)
        after = '"' + HANDOFF_EXAMPLE[tone].format(what=what, josa=eul(what)[len(what):]) + '"처럼 다음 순서로 넘기는 한 문장(작별 인사 없음)'
    else:
        after = "하루를 응원하는 짧은 한 문장"
    return SKIP_ALL_RULE.format(name=name, josa=eul(name)[len(name):], josa2=eul(name)[len(name):], josa3=eul(name)[len(name):],
                                after=after, unit="동작" if content_kind(inp) == "STRETCH" else "단계")


def closing_rules(inp: CoachingInput, plan: tuple[str, ...]) -> str:
    _, nxt = neighbors(plan, COACHING)
    if not nxt:
        return COACH_CLOSING.format(last_order=LAST_STEP_ORDER)
    what = stage_name(nxt)
    tone = inp.persona.tone if inp.persona.tone in HANDOFF_EXAMPLE else "FORMAL"
    handoff = HANDOFF_EXAMPLE[tone].format(what=what, josa=eul(what)[len(what):])
    return COACH_HANDOFF.format(last_order=LAST_STEP_ORDER, handoff=handoff, next=what)


RESUME_LINE = "이번 대화는 이어서 하는 중이므로 전체 안내는 이미 했다. 첫 안내부터 짧은 안내만 하고 \"이어서 할게요\"처럼 한마디 먼저 한다."


# ---------- 콘텐츠별 진행 규칙 (콘텐츠 세션에만 붙는다) ----------
STRETCH_RULES = """스트레칭 진행:
- 첫 동작을 안내할 때만 안전 한마디를 먼저 한다: {safety} (이어서 하는 중이면 생략)
- 모든 단계는 동작 이름만 말하지 말고 [고정 스크립트]의 문장을 처음부터 끝까지 읽어 방법을 알려 준다(위 '한 번의 안내는 최대 2문장, 단계 이름과 시간' 규칙의 예외: 직전 반응 한마디 + 스크립트 전체 + 대답 방법). 동작을 더하거나 강도를 높이지 않고, 말한 초와 횟수를 지킨다. 자세(누워서·앉아서·서서)는 스크립트에 적힌 대로만 바꾼다.
- 사용자가 아프거나 불편하다고 하거나 건너뛰겠다고 하면, 같은 응답에서 방금 안내한 단계를 skipped로 기록하고(어느 부위가 아프다고 하든 방금 안내한 단계로 본다) 무리하지 않아도 된다고 짧게 말한 뒤 다음 단계를 안내한다. 계속 아프면 병원에서 확인해 보라고 한 번만 말한다.
- 효과, 치료, 진단 같은 의학적인 말을 하지 않는다. 마치면 '했어요'(반말이면 '했어')라고 말해 달라고 안내한다."""
STRETCH_SAFETY = {"FORMAL": "\"누워서 천천히 시작해요. 아프거나 저리면 바로 멈추세요.\"", "CASUAL": "\"누워서 천천히 시작하자. 아프거나 저리면 바로 멈춰.\""}

# 부위 고르기(11/12 회의): 서버가 5개 루틴을 넘긴 날에만 붙는다
STRETCH_AREA_RULES = """스트레칭 부위 고르기(이 세션의 첫 안내):
- 첫 안내는 연결 문장 뒤에 "오늘 어디가 불편하세요?"(반말이면 "오늘 어디가 불편해?") 한 문장으로 끝내고 멈춘다. 아직 동작을 안내하지 않는다. [어젯밤 대화 기록]에 몸이 불편하다는 말(예: 어깨가 뻐근함)이 있으면 "어제 어깨가 뻐근하다고 하셨죠. 오늘은 어때요?"처럼 묻는다.
- 대답을 아래 부위 하나로 정한다(이런 말이면 그 부위):
{area_words}
- '없어', '괜찮아', 모르겠다고 하거나 두 번 물어도 답이 없으면 FULL(전신).
- 두 부위를 말하면 먼저 말한 부위로 하고 "○○는 다음에 풀어 봐요"라고 한마디 한다.
- 불편함이 아니라 통증·부상(삐끗했다, 찌르듯 아프다, 다쳤다)을 말하면 그 부위는 하지 않고 FULL(전신)을 가볍게 하며, "통증이 계속되면 무리하지 말고 병원에서 확인해 보세요"라고 한 번 말한다.
- 부위를 정한 응답: "그럼 목·어깨를 풀어 볼게요"처럼 정한 부위를 한 문장으로 알리고, 안전 한마디와 그 부위 루틴의 1번 단계 안내를 이어서 말한 뒤, 그 응답의 맨 끝에서 select_stretch_area(area)를 호출한다. 이 응답에서는 report_step_result를 호출하지 않는다.
- 그 뒤로는 [부위별 스트레칭 루틴] 중 고른 부위의 단계만 번호 순서대로 진행하고, step_order는 그 루틴의 번호를 쓴다. 정한 부위는 바꾸지 않는다."""


def stretch_rules(inp: CoachingInput) -> str:
    tone = inp.persona.tone if inp.persona.tone in STRETCH_SAFETY else "FORMAL"
    rules = STRETCH_RULES.format(safety=STRETCH_SAFETY[tone])
    if not inp.stretch_routines:
        return rules
    words = "\n".join(f"  - {area}({AREA_NAMES.get(area, area)}): " + ", ".join(AREA_WORDS.get(area, ())) for area, _ in inp.stretch_routines)
    return rules + "\n\n" + STRETCH_AREA_RULES.format(area_words=words)


def routines_block(inp: CoachingInput) -> str:
    parts = []
    for area, steps in inp.stretch_routines:
        parts.append(f"({area} {AREA_NAMES.get(area, area)})\n" + "\n".join(step_line(s) for s in steps))
    return "[부위별 스트레칭 루틴]\n" + "\n\n".join(parts)


ENGLISH_RULES = """영어 따라 읽기와 짧은 대답([고정 스크립트]가 '영어 / 뜻 / 발음' 형식이거나 '질문:, 힌트:, 예시:' 세 줄인 단계):
- '표현' 단계: 영어 문장을 천천히 또렷하게 한 번 읽고, 한국어 뜻을 한 문장으로 말한 뒤 따라 말해 달라고 한다(한 번의 안내는 3문장까지). 영어 문장과 발음은 [고정 스크립트]에 적힌 대로만 쓰고 새 영어를 만들지 않는다.
- 사용자가 따라 말하면 짧게 반응한다. 대체로 맞으면 칭찬 한마디, 한 단어만 어색하면 그 단어만 천천히 한 번 더 들려 준다. 발음을 세세히 고치거나 점수·등급을 매기지 않는다. 따라 말했으면 done, 못 하겠다고 하면 skipped다.
- '한마디 대답' 단계: 영어 질문을 한 번 읽고 힌트를 한국어로 한 문장 말한다. 사용자가 한두 마디로 답하면(영어든 한국어든) 받아 주고, 예시 답을 영어로 한 번 들려 주며 칭찬한다. 답했으면 done이다.
- 영어를 길게 말하지 않는다. 막혔다고 하면 한국어로 힌트를 주고 예시를 따라 말해 보게 해도 된다. '했어요/못 했어요'라고 답하라고 안내하지 않고 바로 말해 달라고 한다."""

WORDCHAIN_RULES = """끝말잇기([고정 스크립트]의 '게임: 끝말잇기' 단계): 단계는 하나이고 이 안에서 한 판을 한다. '했어요/못 했어요'라고 답하라고 안내하지 않는다.
- 시작 안내는 한 응답이다: 규칙을 한 문장으로 알리고("앞 단어의 마지막 글자로 시작하는 단어를 말하는 거예요") [고정 스크립트]의 시작 단어를 말한 뒤 사용자 차례로 멈춘다.
- 사용자가 단어를 말하면 그 단어의 마지막 글자로 시작하는 쉬운 일상 단어 하나만 말한다. 반응은 한마디 이내로 하고, 두음법칙(예: '리'→'이')은 받아 준다. 이미 나온 단어, 한 글자 단어, 어려운 단어는 쓰지 않는다.
- 사용자의 단어가 이어지지 않거나 이미 나왔으면 한 번만 부드럽게 알리고 다시 말해 보게 한다. 따지거나 점수를 매기지 않는다.
- 사용자가 막혔다고 하거나 '패스', '그만'이라고 하거나, 주고받은 지 대략 다섯 번쯤 됐으면 한 문장으로 칭찬하며 마무리하고 그 응답에서 report_step_result(step_order, done)를 한 번 호출한다. 횟수를 정확히 세는 데 매달리지 않는다.
- '패스', '그만'은 이 게임을 그만하겠다는 뜻이지 대화를 끝내겠다는 뜻이 아니다. 마무리 응답에서도 작별 인사를 하거나 end_session을 호출하지 않는다. 그 뒤는 [마무리] 규칙을 따른다."""

QUIZ_RULES = """퀴즈 진행([고정 스크립트]에 '문제:'와 '정답:' 줄이 있는 단계):
- 사용자가 답하거나 모르겠다고 하면 그 응답에서 report_step_result를 한 번 호출하며 answer_correct에 맞고 틀림을 넣는다. 정답 줄과 뜻이 같으면 표현이 달라도 true, 틀렸거나 모르겠다고 했으면 false다. verdict는 답을 말했으면 done이다.
- 같은 응답에서 결과를 먼저 짧게 말한다. 맞으면 "맞았어요!"처럼, 틀리면 "아쉬워요, 정답은 ○○예요."처럼 정답을 알려 준다. 앱이 이 말을 듣고 넥스티의 O/X를 보여 주므로 '맞았어요', '아쉬워요'를 이 표현대로 쓴다. 그 뒤 다음 문제를 낸다."""

# 시작 연결 문장: 순서표의 바로 앞 단계(브리핑·한마디·포춘)에서 이어 말한다(지금까지 앱이 덧붙이던 bridgeNote의 역할)
CONTENT_NAMES = {"STRETCH": "스트레칭", "ENGLISH": "영어 따라 읽기", "WORDCHAIN": "끝말잇기", "QUIZ": "퀴즈"}
QUIZ_OPTION_NAMES = {"PERSON": "인물 퀴즈", "GENERAL": "상식 퀴즈"}
OPEN_EXAMPLE = {
    "FORMAL": {FORTUNE: "이어서 {name} 시간이에요.", ONE_LINER: "어젯밤 한마디 잘 들었죠? 이어서 {name} 시간이에요.", BRIEFING: "그럼 {name} 시작해 볼게요."},
    "CASUAL": {FORTUNE: "이어서 {name} 시간이야.", ONE_LINER: "어젯밤 한마디 잘 들었지? 이어서 {name} 시간이야.", BRIEFING: "그럼 {name} 시작해 보자."},
}
OPEN_RULE = """시작(이 세션의 첫 안내): 바로 앞 단계({prev})에서 이어서 시작하므로 첫 안내는 연결 문장 한 문장으로 연다. 예) "{example}" {name_rule}이 문장은 인사가 아니며, 앞 단계의 내용을 되풀이하거나 평가하지 않는다. {after_open} 이어서 하는 중이면 이 연결 문장 대신 "이어서 할게요"만 말한다."""
AFTER_OPEN_STEP = "연결 문장 다음에 바로 첫 단계 안내를 한다(연결 문장은 첫 안내의 문장 수에 넣지 않는다)."
AFTER_OPEN_AREA = "연결 문장 다음에는 동작 안내 없이 부위 질문 한 문장만 한다(아래 '스트레칭 부위 고르기')."
CONTENT_NAME_RULE = '콘텐츠 이름은 반드시 "{name}" 그대로 쓰고 다른 이름(예: 역사 퀴즈, 운동)으로 바꿔 부르지 않는다. '


def open_rule(inp: CoachingInput, plan: tuple[str, ...], name: str, content: bool) -> str:
    prev, _ = neighbors(plan, COACHING)
    prev = prev or BRIEFING
    tone = inp.persona.tone if inp.persona.tone in OPEN_EXAMPLE else "FORMAL"
    example = OPEN_EXAMPLE[tone].get(prev, OPEN_EXAMPLE[tone][BRIEFING]).format(name=name)
    area_mode = content_kind(inp) == "STRETCH" and bool(inp.stretch_routines)
    return OPEN_RULE.format(prev=stage_name(prev), example=example, name_rule=CONTENT_NAME_RULE.format(name=name) if content else "",
                            after_open=AFTER_OPEN_AREA if area_mode else AFTER_OPEN_STEP)


def content_label(inp: CoachingInput) -> str:
    kind = content_kind(inp)
    if kind == "QUIZ":
        return QUIZ_OPTION_NAMES.get(inp.content_option, CONTENT_NAMES["QUIZ"])
    return CONTENT_NAMES.get(kind, "")


CONTENT_RULES = {"ENGLISH": ENGLISH_RULES, "WORDCHAIN": WORDCHAIN_RULES, "QUIZ": QUIZ_RULES}      # 스트레칭은 어조·부위 고르기에 따라 stretch_rules()로 만든다


def _duration(sec: int | None) -> str:
    if not sec:
        return ""
    m, r = divmod(sec, 60)
    return f", {m}분" + (f" {r}초" if r else "") if m else f", {sec}초"


def step_line(s: Step) -> str:
    minutes = _duration(s.planned_sec)
    line = f"{s.order}. {clean(s.name, 40)} ({s.response_type}{minutes})"
    if s.script:
        line += f"\n   [고정 스크립트] {s.script.strip()[:600]}"
    return line


def steps_block(label: str, steps) -> str:
    return f"[{label}]\n" + "\n".join(step_line(s) for s in steps)


def coaching_prompt(inp: CoachingInput) -> str:
    content = inp.morning_call_type == "CONTENT"
    kind = content_kind(inp)
    area_mode = kind == "STRETCH" and bool(inp.stretch_routines)
    label = "고른 부위의 스트레칭 루틴" if area_mode else ("오늘 콘텐츠 단계" if content else "오늘 코칭 단계")
    steps = routines_block(inp) if area_mode else steps_block(label, inp.steps)
    plan = resolve_plan(inp, COACHING)
    name = (content_label(inp) or "오늘의 콘텐츠") if content else "아침 루틴"
    return join_blocks(
        CONTENT_INTRO if content else COACH_INTRO, STEP_RULES.format(label=label, resume_line=RESUME_LINE if inp.resumed else ""),
        "" if content else TIME_RULES, "" if content else ENCOURAGE_RULES, closing_rules(inp, plan), skip_all_rule(inp, plan, name, content),
        stretch_rules(inp) if kind == "STRETCH" else CONTENT_RULES.get(kind, ""),
        open_rule(inp, plan, name, content),
        morning_flow(plan, COACHING, name), MORNING_RULE, SAFETY_RULES, VOICE_RULES, persona_block(inp.persona), steps,
        night_record_block(inp.night) if inp.night else "",
        inp.strategy_block,
    )
