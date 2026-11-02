"""밤 대화 원문 → RS_NIGHT_RECORD 컬럼. 서버가 통화 종료 후 부른다."""
import copy
import re
from dataclasses import dataclass, field

from . import transcript as tr
from .llm import LLMError

CONDITION_LABELS = {"GOOD", "NORMAL", "TIRED", "STRESSED"}  # 정의서(임시, AI 파트와 확정)

SYSTEM = """아래 밤 대화 기록(사용자와 AI의 대화)을 바탕으로 다음 날 아침 브리핑과 일기에 쓸 정보를 JSON 객체 하나로만 출력하세요.
키:
- ai_summary: 밤 대화 요약 (문자열, 1~2문장. 사용자가 오늘 한 일과 느낀 점 중심)
- tomorrow_goal: 사용자가 직접 말한 내일의 핵심 목표 한 가지 (문자열, 한 문장, 200자 이내). 목표 내용만 짧게 정리해 쓰고(예: "발표 잘 마치기") 사용자의 말투(~예요)를 그대로 옮기지 않는다. 사용자가 목표나 가장 중요한 일을 말하지 않았으면 반드시 "" (AI가 만들어 내지 않는다)
- tomorrow_notes: 내일 특이사항 목록 (문자열 배열. 일정·약속·시험·걱정 등. 없으면 [])
- condition_label: 사용자의 컨디션을 GOOD, NORMAL, TIRED, STRESSED 중 하나로. 판단할 근거가 없으면 ""
- condition_text: 컨디션을 나타내는 짧은 표현 (예: "여유로움", "지쳤지만 뿌듯함", 100자 이내). 없으면 ""
- encouragement: 사용자에게 건네는 응원 한마디 (한 문장)
- insight: 대화에서 AI가 실제로 짚어 주었거나 사용자가 깨달은 핵심 포인트 (한 문장). 그런 내용이 없으면 반드시 "" (일반적인 조언을 만들어 쓰지 않는다)
- diary_text: 사용자가 오늘 한 일과 느낀 감정을 사용자 시점으로 쓴 한 줄 일기 (40자 이내). 사용자가 대화에서 말한 내용만 쓴다
- override_wake_time: 내일 하루만 알람 시각을 바꾸기로 **사용자가** 직접 요청했거나 AI의 제안에 분명히 동의했을 때만 "HH:MM"(24시간제). 아니면 ""
- override_skip_coaching: 내일 하루만 코칭을 쉬기로(아침에 브리핑만 하기로) **사용자가** 직접 요청했거나 AI의 제안에 분명히 동의했으면 true. 아니면 false
규칙:
- 대화에 없는 내용은 지어내지 마세요. 사용자가 하지 않은 말을 사용자가 한 것처럼 쓰지 마세요.
- 받아쓰기 오류로 보이는 단어는 문맥으로 바로잡되, 확신이 없으면 그대로 두세요.
- override_*는 사용자(대화 기록에서 "사용자:"로 시작하는 줄)의 요청이나 동의가 있을 때만 쓰세요. AI가 "줄일게요", "바꿔 둘게요"처럼 정한 것처럼 말했더라도 그 뒤나 그 전에 사용자의 요청·동의가 없으면 반드시 ""와 null로 두세요. AI가 제안만 했거나 사용자가 거절·무시했거나 대답이 모호해도 마찬가지입니다.
- 숫자 시각은 24시간제 HH:MM으로 쓰세요 (예: 오전 6시 40분 → "06:40")."""


@dataclass
class NightRecordResult:
    status: str                      # DONE / FAILED  (RS_NIGHT_RECORD.EXTRACT_STATUS)
    fields: dict = field(default_factory=dict)
    error: str = ""

    def to_row(self) -> dict:
        """RS_NIGHT_RECORD에 넣을 컬럼(소문자) + extract_status. OVERRIDE_ALARM_ID는 서버가 정한다."""
        return {**self.fields, "extract_status": self.status}


EMPTY = {"ai_summary": None, "tomorrow_goal": None, "tomorrow_notes": [], "condition_label": None, "condition_text": None,
         "encouragement": None, "insight": None, "diary_text": None, "override_wake_time": None, "override_skip_coaching": False}


def _text(v, limit: int):
    s = re.sub(r"\s+", " ", str(v or "")).strip()
    return s[:limit] or None


def _wake(v):
    m = re.fullmatch(r"\s*(\d{1,2}):(\d{2})\s*", str(v or ""))
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}"


def normalize(data: dict) -> dict:
    """AI 응답을 DB 컬럼 형식으로 정리한다. 형식이 크게 어긋나면 ValueError(→ 재시도)."""
    notes = data.get("tomorrow_notes", [])
    if notes is None:
        notes = []
    if not isinstance(notes, list):
        raise ValueError("tomorrow_notes는 배열이어야 해요")
    label = str(data.get("condition_label") or "").strip().upper()
    return {
        "ai_summary": _text(data.get("ai_summary"), 500),
        "tomorrow_goal": _text(data.get("tomorrow_goal"), 200),
        "tomorrow_notes": [n for n in (_text(x, 100) for x in notes[:10]) if n],
        "condition_label": label if label in CONDITION_LABELS else None,
        "condition_text": _text(data.get("condition_text"), 100),
        "encouragement": _text(data.get("encouragement"), 300),
        "insight": _text(data.get("insight"), 300),
        "diary_text": _text(data.get("diary_text"), 80),
        "override_wake_time": _wake(data.get("override_wake_time")),
        "override_skip_coaching": data.get("override_skip_coaching") is True,
    }


def extract_night_record(transcript, llm, retries: int = 1) -> NightRecordResult:
    """transcript: [{role, text, at}] (또는 그 JSON 문자열). llm: generate_json(system, user)을 가진 객체.

    사용자 발화가 없으면 추출할 내용이 없으므로 AI를 부르지 않고 빈 값으로 DONE을 돌려준다(기본안).
    끝내 실패하면 FAILED — 서버가 RS_NIGHT_RAW 원문으로 다시 추출한다.
    """
    turns = tr.normalize(transcript)
    if not tr.has_user_speech(turns):
        return NightRecordResult("DONE", copy.deepcopy(EMPTY))
    last = ""
    for _ in range(retries + 1):
        try:
            return NightRecordResult("DONE", normalize(llm.generate_json(SYSTEM, "[밤 대화 기록]\n" + tr.to_text(turns))))
        except (LLMError, ValueError) as e:
            last = str(e)
    return NightRecordResult("FAILED", copy.deepcopy(EMPTY), last)
