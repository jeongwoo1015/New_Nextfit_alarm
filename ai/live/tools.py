"""Live 대화에서 AI가 부르는 함수 선언. 판정 값은 정의서 RS_SESSION_STEP.AI_VERDICT와 같다."""

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

_BY_KIND = {
    "night": [END_SESSION],
    "briefing": [START_ROUTINE, END_SESSION],
    "coaching": [REPORT_STEP_RESULT, END_SESSION],
}


def tools_for(kind: str, inp=None) -> list[dict]:
    """kind: night / briefing / coaching → Live setup의 tools 값.

    입력에 따라 다르다: 브리핑은 코칭을 하는 날에만 start_routine을 넣는다(코칭 안 하는 날은 브리핑만 한다).
    """
    if kind == "briefing" and inp is not None:
        follows = not getattr(inp, "coaching_skipped", False)
        return [{"functionDeclarations": ([START_ROUTINE] if follows else []) + [END_SESSION]}]
    return [{"functionDeclarations": list(_BY_KIND[kind])}]
