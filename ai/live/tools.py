"""Live 대화에서 AI가 부르는 함수 선언. 판정 값은 정의서 RS_SESSION_STEP.AI_VERDICT와 같다."""

VERDICTS = ("done", "not_done", "skipped", "ambiguous", "other")

END_SESSION = {
    "name": "end_session",
    "description": "마무리 인사까지 마친 뒤 이 대화를 끝낸다. 호출한 뒤에는 어떤 말도 하지 않는다.",
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
    "briefing": [END_SESSION],
    "coaching": [REPORT_STEP_RESULT, END_SESSION],
}


def tools_for(kind: str) -> list[dict]:
    """kind: night / briefing / coaching → Live setup의 tools 값."""
    return [{"functionDeclarations": list(_BY_KIND[kind])}]
