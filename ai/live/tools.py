"""Live 대화에서 AI가 부르는 함수 선언. 판정 값은 정의서 RS_SESSION_STEP.AI_VERDICT와 같다."""

from copy import deepcopy

from ..tasks.morning_plan import BRIEFING, COACHING
from .inputs import content_kind

VERDICTS = ("done", "not_done", "skipped", "ambiguous", "other")

END_SESSION = {
    "name": "end_session",
    "description": "사용자가 대화를 끝내겠다고 분명히 말했을 때만(예: 잘게, 이만 끊을게, 그만할래) 짧은 작별 인사 뒤에 호출한다. AI가 스스로 끝내려고 호출하지 않는다. 호출한 뒤에는 어떤 말도 하지 않는다.",
    "parameters": {"type": "OBJECT", "properties": {}},
}

START_ROUTINE = {
    "name": "start_routine",
    "description": "'다음 세션(루틴 또는 콘텐츠)을 시작할 준비가 됐는지' 물었고, 사용자가 그 질문에 준비됐다고 분명히 답한 다음 응답에서만 호출한다. 질문을 한 같은 응답에서는 호출하지 않는다. 호출하면 앱이 다음 세션을 시작한다.",
    "parameters": {"type": "OBJECT", "properties": {}},
}

REPORT_STEP_RESULT = {
    "name": "report_step_result",
    "description": "코칭 단계 하나에 대한 사용자의 수행 여부를 기록한다. 사용자의 답을 판정할 때마다 호출한다.",
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "step_order": {"type": "INTEGER", "description": "단계 번호 (STEP_ORDER)"},
            "verdict": {"type": "STRING", "enum": list(VERDICTS)},
        },
        "required": ["step_order", "verdict"],
    },
}

# 퀴즈 콘텐츠 전용: 앱이 AI의 말을 추측하지 않고 이 값으로 정답 여부(넥스티 O/X)를 알 수 있게 한다
REPORT_STEP_RESULT_QUIZ = deepcopy(REPORT_STEP_RESULT)
REPORT_STEP_RESULT_QUIZ["description"] = ("퀴즈 문제 하나에 대한 사용자의 답을 기록한다. 사용자가 답하거나 모르겠다고 말한 응답에서 한 번 호출한다. "
                                          "답을 말했으면 verdict는 done이고, answer_correct에 맞고 틀림을 함께 넣는다.")
REPORT_STEP_RESULT_QUIZ["parameters"]["properties"]["answer_correct"] = {
    "type": "BOOLEAN", "description": "사용자의 답이 [고정 스크립트]의 정답 줄과 같은 뜻이면 true, 틀렸거나 모르겠다고 했으면 false"}
REPORT_STEP_RESULT_QUIZ["parameters"]["required"] = ["step_order", "verdict", "answer_correct"]

ADJUST_STEP_TIME = {
    "name": "adjust_step_time",
    "description": "사용자가 아직 하지 않은 단계를 오늘만 줄이거나 빼 달라고 했을 때 호출한다. seconds는 오늘 그 단계의 새 시간(초)이며 30초 단위이고, 0이면 오늘만 그 단계를 뺀다. 시간을 말하지 않았으면 먼저 사용자에게 묻고, 같은 단계의 판정(report_step_result)과 함께 부르지 않는다.",
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "step_order": {"type": "INTEGER", "description": "바꿀 단계 번호 (STEP_ORDER)"},
            "seconds": {"type": "INTEGER", "description": "오늘 그 단계의 새 시간(초). 30초 단위, 0이면 오늘만 뺀다"},
        },
        "required": ["step_order", "seconds"],
    },
}

_BY_KIND = {
    "night": [END_SESSION],
    "briefing": [START_ROUTINE, END_SESSION],
    "fortune": [END_SESSION],        # 읽고 멈추는 세션이라 사용자가 끝내겠다고 할 때만 쓴다. 앱이 음성이 끝나면 다음 단계로 넘어간다
    "coaching": [REPORT_STEP_RESULT, ADJUST_STEP_TIME, END_SESSION],
}


def tools_for(kind: str, inp=None) -> list[dict]:
    """kind: night / briefing / fortune / coaching → Live setup의 tools 값.

    입력에 따라 다르다: 브리핑은 순서표에 코칭이 있는 날에만 start_routine을 넣는다(코칭 안 하는 날·이미 다 마친 날은 end_session으로 다음 순서에 넘긴다).
    adjust_step_time은 루틴 세션에만 넣는다(콘텐츠는 고정 시간).
    """
    if kind == "briefing" and inp is not None:
        from .prompts.common import resolve_plan      # 순서표에 코칭이 있는 날에만 start_routine (코칭 안 하는 날·이미 다 마친 날은 end_session으로 넘긴다)
        follows = COACHING in resolve_plan(inp, BRIEFING)
        return [{"functionDeclarations": ([START_ROUTINE] if follows else []) + [END_SESSION]}]
    if kind == "coaching" and getattr(inp, "morning_call_type", "ROUTINE") == "CONTENT":
        report = REPORT_STEP_RESULT_QUIZ if content_kind(inp) == "QUIZ" else REPORT_STEP_RESULT
        return [{"functionDeclarations": [report, END_SESSION]}]
    return [{"functionDeclarations": list(_BY_KIND[kind])}]
