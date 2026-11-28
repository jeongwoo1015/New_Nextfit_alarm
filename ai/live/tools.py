"""Live 대화에서 AI가 부르는 함수 선언. 판정 값은 정의서 RS_SESSION_STEP.AI_VERDICT와 같다."""

from copy import deepcopy

from ..tasks.morning_plan import BRIEFING, COACHING
from ..tasks.stretch_sets import AREA_NAMES, AREAS
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

# 스트레칭 부위 고르기(11/12 회의, 11/18 부위 12개로 확대): 앱이 미리 받아 둔 부위별 루틴 중 이 부위로 화면·타이머·단계 기록을 바꾼다.
# AI는 동작이 아니라 부위만 고른다(동작은 stretch_sets.ROUTINES에 고정)
STRETCH_AREAS = AREAS
SELECT_STRETCH_AREA = {
    "name": "select_stretch_area",
    "description": "\"오늘 어디가 불편하세요?\"에 대한 대답으로 오늘 스트레칭할 부위를 정했을 때 한 번 호출한다. 앱이 그 부위 루틴으로 화면과 단계를 바꾸고, 이후 step_order는 그 루틴의 번호다.",
    "parameters": {
        "type": "OBJECT",
        "properties": {"area": {"type": "STRING", "enum": list(STRETCH_AREAS),
                                "description": " / ".join(f"{a} {AREA_NAMES[a]}" for a in STRETCH_AREAS)}},
        "required": ["area"],
    },
}

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

# 모닝콜 말로 미루기(11/20 회의 1, 11/24 확대): 전화를 받은 뒤 "~분 뒤에 다시 울려 줘", "9시에 다시 맞춰 줘"라고 하면 오늘만 알람을 다시 맞춘다.
# 브리핑뿐 아니라 루틴·콘텐츠(코칭) 통화에도 둔다. 미루면 언제든 그날 기상은 실패로 센다(서버 몫, B21). 포춘(읽고 멈추는 세션)은 함수를 부르지 않으므로 뺀다.
# 앱은 end_session처럼 응답하지 않고 안내 음성이 끝나면 통화를 저절로 끝내고 알람을 다시 예약한다(11/27 앱 요청: 사용자에게 직접 종료하라고 안내하지 않는다).
SNOOZE_MAX_MINUTES = 60
SNOOZE_MAX_PER_ALARM = 3            # 알람(세션)마다 미룰 수 있는 횟수(11/26 회의: 하루 합계가 아니라 알람마다). 서버는 그 알람의 미룬 횟수를 snoozes_today 칸에 넣는다
RESCHEDULE_ALARM = {
    "name": "reschedule_alarm",
    "description": ("전화를 받은 뒤 사용자가 오늘만 알람을 미루고 싶다고 분명히 말했을 때(예: '10분 뒤에 다시 울려 줘', '9시에 다시 맞춰 줘') 호출한다. "
                    "minutes_later(지금부터 몇 분 뒤, 1~60)와 at_time(오늘 몇 시, 'HH:MM') 중 사용자가 말한 쪽 하나만 넣는다. "
                    "시간을 말하지 않았으면 먼저 한 번 묻고, 말한 뒤에 호출한다. 단순히 졸리다는 말은 미루는 요청이 아니다. 같은 응답에서 다시 맞췄다고 먼저 안내한 뒤, 그 응답의 맨 끝에서 호출한다(호출하면 앱이 응답 없이 안내 음성이 끝나면 연결을 닫으므로, 호출한 뒤에는 어떤 말도 하지 않는다)."),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "minutes_later": {"type": "INTEGER", "description": f"지금부터 몇 분 뒤에 다시 울릴지(1~{SNOOZE_MAX_MINUTES}). at_time과 함께 쓰지 않는다"},
            "at_time": {"type": "STRING", "description": "오늘 다시 울릴 시각 'HH:MM'(24시간). 지금보다 뒤이고 60분 안이어야 한다. minutes_later와 함께 쓰지 않는다"},
        },
    },
}

# 모닝콜에서 말한 잠든 시각(11/26 회의, 11/27 앱 요청): 브리핑에서 사용자가 어젯밤 잠든 시각을 말하면 부른다.
# 11/28 실제 모델 시험: 잠든 시각을 들은 응답에서 부르면 함수 응답 뒤 AI가 브리핑을 되풀이하고 다음 순서로 넘기지 못했다(5번 중 4번).
# 그래서 통화를 끝내는 함수(start_routine·end_session·reschedule_alarm)와 같은 응답에서 함께 부르게 한다.
# 앱은 {"result":"ok"}를 바로 돌려주고 서버(PUT /morning/sessions/{id}/bedtime)에 저장한다. 서버가 날짜를 붙이고 리포트 취침 시각을 이 값으로 덮어쓴다.
REPORT_BEDTIME = {
    "name": "report_bedtime",
    "description": ("사용자가 어젯밤 잠든 시각을 말했으면, 다음 순서로 넘기거나 통화를 끝내는 함수(알람 미루기 포함)를 부르는 응답에서 그 함수 바로 앞에 한 번 함께 호출한다. 잠든 시각을 들은 응답에서 따로 부르지 않는다. time은 24시간 'HH:MM'이다(새벽 2시는 '02:00', 밤 11시 반은 '23:30', 자정은 '00:00'). "
                    "잠든 시각은 지금보다 앞선 12시간 안쪽으로 해석한다. 모르겠다거나 기억이 안 난다고 하면 호출하지 않고, 사용자가 말하지 않은 시각을 지어내지 않는다. "
                    "사용자가 잠든 시각을 고쳐 말하면 고친 값으로 한 번 더 호출한다. 호출했다는 것이나 저장했다는 말은 하지 않는다."),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "time": {"type": "STRING", "description": "어젯밤 잠든 시각 'HH:MM'(24시간)"},
        },
        "required": ["time"],
    },
}

# 대화로 설정 바꾸기(11/20 회의 4): 굿나잇콜·모닝콜(브리핑)에서 AI 음악·성격·강도·기상 볼륨만 바꾼다. 어조·호칭 등 다른 설정은 바꾸지 않는다.
# 앱은 평소처럼 {"result":"ok"}로 응답하고 서버 설정을 바꾼다(성격·강도·음악은 다음 모닝콜부터, 기상 볼륨은 scope에 따라 다음 알람 한 번 또는 앞으로의 상한).
SETTING_VALUES = {
    "music_genre": ("POP", "JAZZ", "CLASSIC", "DANCE", "ACOUSTIC"),
    "personality": ("KIND", "FRIENDLY", "STRICT", "CHEERFUL"),
    "intensity": ("1", "2", "3"),
    "wake_volume": ("1", "2", "3", "4", "5"),
}
SETTING_SCOPES = ("ALWAYS", "TODAY")
UPDATE_SETTING = {
    "name": "update_setting",
    "description": ("사용자가 AI 음악(music_genre), AI 성격(personality), AI 강도(intensity), 기상 알람 볼륨(wake_volume)을 바꾸고 싶다고 분명히 말했고, "
                    "바꿀 값을 한 번 말해 확인받은 다음에만 호출한다. 다른 설정(어조·호칭·알람 시각 등)은 바꾸지 않는다. 사용자가 말하지 않은 값으로 바꾸지 않는다. 바꿨다고 말하려면 먼저 이 함수를 호출해야 한다."),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "field": {"type": "STRING", "enum": list(SETTING_VALUES), "description": "music_genre 음악 / personality 성격 / intensity 깨우는 말 강도 / wake_volume 기상 알람 시작 볼륨 단계"},
            "value": {"type": "STRING", "description": ("music_genre: POP·JAZZ·CLASSIC·DANCE·ACOUSTIC / personality: KIND 다정한·FRIENDLY 친근한·STRICT 엄격한·CHEERFUL 유쾌한 / "
                                                       "intensity: 1 약하게·2 보통·3 강하게 / wake_volume: 1~5단계")},
            "scope": {"type": "STRING", "enum": list(SETTING_SCOPES),
                      "description": "wake_volume만 구분한다: TODAY = 다음 알람 한 번만 이 단계로 시작, ALWAYS = 앞으로 이 단계보다 높게 시작하지 않는 상한. 나머지 설정은 ALWAYS"},
        },
        "required": ["field", "value"],
    },
}

_BY_KIND = {
    "night": [UPDATE_SETTING, END_SESSION],
    "briefing": [START_ROUTINE, RESCHEDULE_ALARM, REPORT_BEDTIME, UPDATE_SETTING, END_SESSION],
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
        can_snooze = int(getattr(inp, "snoozes_today", 0) or 0) < SNOOZE_MAX_PER_ALARM    # 이 알람의 한도를 넘으면 함수를 빼서 호출할 수 없게 한다
        return [{"functionDeclarations": ([START_ROUTINE] if follows else []) + ([RESCHEDULE_ALARM] if can_snooze else []) + [REPORT_BEDTIME, UPDATE_SETTING, END_SESSION]}]
    if kind == "coaching":
        snooze = [RESCHEDULE_ALARM] if int(getattr(inp, "snoozes_today", 0) or 0) < SNOOZE_MAX_PER_ALARM else []   # 코칭 통화에서도 이 알람의 한도를 넘으면 함수를 뺀다
        if getattr(inp, "morning_call_type", "ROUTINE") == "CONTENT":
            report = REPORT_STEP_RESULT_QUIZ if content_kind(inp) == "QUIZ" else REPORT_STEP_RESULT
            area = [SELECT_STRETCH_AREA] if content_kind(inp) == "STRETCH" and getattr(inp, "stretch_routines", ()) else []
            return [{"functionDeclarations": area + [report] + snooze + [END_SESSION]}]
        return [{"functionDeclarations": list(_BY_KIND[kind][:-1]) + snooze + [END_SESSION]}]
    return [{"functionDeclarations": list(_BY_KIND[kind])}]
