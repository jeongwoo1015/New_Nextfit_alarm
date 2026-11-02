"""콘텐츠 알람(명상·운동·외국어·퀴즈)의 그날 단계를 AI가 구성한다. 서버가 알람 세션을 만들 때 부른다.

값 목록은 정의서의 예시(팀 확정 전)다. 모르는 세부 값은 해당 종류의 일반 구성으로 처리한다.
"""
from dataclasses import dataclass
from datetime import date

from ..live.inputs import Step
from .llm import LLMError

CONTENT_OPTIONS = {
    "MEDITATION": ("BREATH", "BODY_SCAN"),
    "EXERCISE": ("STRETCH", "MASSAGE"),
    "LANGUAGE": ("ENGLISH", "JAPANESE", "CHINESE"),
    "QUIZ": ("GENERAL", "SCIENCE", "HISTORY", "NONSENSE"),
}
RESPONSE_TYPES = {"PLAY", "QA", "SPEECH"}
DEFAULT_RESPONSE = {"MEDITATION": "PLAY", "EXERCISE": "PLAY", "LANGUAGE": "SPEECH", "QUIZ": "SPEECH"}
MIN_STEPS, MAX_STEPS = 2, 8
CONTENT_TOTAL_SEC = 300      # 콘텐츠 알람은 고정 5분 (정의서 11/01, RS_APP_CONFIG.CONTENT_DURATION_SEC)

# 종류별 분량 안내 (5분에 맞춘 값)
COUNTS = {"MEDITATION": "2~3단계", "EXERCISE": "3동작", "LANGUAGE": "3개 표현", "QUIZ": "3문제"}
TYPE_GUIDE = {
    "MEDITATION": "각 단계의 script에 AI가 차분히 읽어 줄 안내 문장(호흡 횟수, 집중할 부위 등)을 적는다. response_type은 PLAY.",
    "EXERCISE": "각 단계는 동작 하나다. name은 동작 이름, script에 방법을 한두 문장으로 적는다. 아침에 안전하게 할 수 있는 가벼운 동작만. response_type은 PLAY.",
    "LANGUAGE": "각 단계는 따라 말할 표현 하나다. name은 '표현 1'처럼 쓰고 script에 '표현 / 한국어 뜻 / 발음'을 적는다. 사용자가 소리 내어 따라 말하는 단계이므로 response_type은 SPEECH.",
    "QUIZ": "각 단계는 문제 하나다. name은 '퀴즈 1번'처럼 쓰고 script를 정확히 '문제: ...\\n정답: ...' 두 줄로 적는다. 정답이 분명한 문제만 낸다. response_type은 SPEECH.",
}
OPTION_GUIDE = {
    "BREATH": "호흡 명상", "BODY_SCAN": "바디스캔 명상", "STRETCH": "스트레칭", "MASSAGE": "셀프 마사지",
    "ENGLISH": "영어 표현", "JAPANESE": "일본어 표현", "CHINESE": "중국어 표현",
    "GENERAL": "일반 상식 퀴즈", "SCIENCE": "과학 퀴즈", "HISTORY": "역사 퀴즈", "NONSENSE": "넌센스 퀴즈",
}

SYSTEM = """당신은 아침 알람 앱의 콘텐츠 구성자입니다. 사용자가 고른 콘텐츠를 오늘 아침에 AI가 음성으로 이끌 단계 목록으로 만드세요.
JSON 객체 하나로만 출력: {{"steps": [{{"name": 문자열(100자 이내), "seconds": 정수(양수), "response_type": "PLAY"|"QA"|"SPEECH", "script": 문자열}}]}}
- 분량: {count}. 모든 단계의 seconds를 더하면 정확히 {total}초가 되게 한다.
- {guide}
- 사용자는 방금 잠에서 깬 상태이므로 쉽고 짧게 만든다.
- 퀴즈는 문제 문장에 정답이 들어 있지 않아야 하고, 정답이 하나로 분명해야 한다.
- 오늘 날짜({today})를 참고해 매일 다른 내용이 나오게 하되, 사실이 틀리거나 위험한 내용은 넣지 않는다."""


@dataclass
class ContentStepsResult:
    steps: list[Step]
    source: str      # "ai" 또는 "fallback"(AI 호출이 실패해 준비된 기본 단계를 썼다)
    error: str = ""


def _fit_seconds(raw: list[int], total: int) -> list[int]:
    """비율을 유지하면서 합이 정확히 total이 되게 맞춘다 (각 단계 최소 10초)."""
    s = sum(raw)
    out = [max(10, round(x * total / s)) for x in raw]
    out[-1] += total - sum(out)
    if out[-1] < 10:  # 마지막 단계가 너무 줄면 가장 긴 단계에서 덜어 낸다
        need = 10 - out[-1]
        i = max(range(len(out) - 1), key=lambda k: out[k])
        out[i] -= need
        out[-1] = 10
    return out


def validate_steps(data: dict, content_type: str, total: int) -> list[Step]:
    """AI 응답 → Step 목록. 형식이 어긋나면 ValueError."""
    items = data.get("steps")
    if not isinstance(items, list) or not (MIN_STEPS <= len(items) <= MAX_STEPS):
        raise ValueError(f"steps는 {MIN_STEPS}~{MAX_STEPS}개 배열이어야 해요")
    names, secs, types, scripts = [], [], [], []
    for it in items:
        if not isinstance(it, dict):
            raise ValueError("step은 객체여야 해요")
        name = " ".join(str(it.get("name") or "").split())[:100]
        script = str(it.get("script") or "").strip()[:800]
        try:
            sec = int(it.get("seconds"))
        except (TypeError, ValueError):
            raise ValueError("seconds는 정수여야 해요") from None
        if not name or sec <= 0:
            raise ValueError("name과 seconds(양수)가 필요해요")
        if not script:
            raise ValueError("script가 필요해요")
        rt = str(it.get("response_type") or "").upper()
        names.append(name), secs.append(sec), scripts.append(script)
        types.append(rt if rt in RESPONSE_TYPES else DEFAULT_RESPONSE[content_type])
    if content_type == "QUIZ" and not all("문제:" in s and "정답:" in s for s in scripts):
        raise ValueError("퀴즈 script는 '문제:'와 '정답:' 줄이 있어야 해요")
    fitted = _fit_seconds(secs, total)
    return [Step(i, n, f, t, s) for i, (n, f, t, s) in enumerate(zip(names, fitted, types, scripts), 1)]


FALLBACKS = {
    "MEDITATION": [("호흡 맞추기", "편안히 앉아 눈을 감고, 코로 4초 들이마시고 6초 동안 천천히 내쉬어요."),
                   ("호흡에 집중하기", "숨이 들어오고 나가는 느낌에만 마음을 두어요. 생각이 떠오르면 다시 호흡으로 돌아와요."),
                   ("마무리 호흡", "깊게 숨을 한 번 들이마시고 내쉬며 천천히 눈을 떠요.")],
    "EXERCISE": [("목 돌리기", "목을 천천히 좌우로 돌려요. 각 방향으로 다섯 번씩 해요."),
                 ("어깨 돌리기", "어깨를 앞뒤로 크게 열 번씩 돌려요."),
                 ("옆구리 늘리기", "양손을 위로 모아 올리고 좌우로 천천히 기울여요."),
                 ("기지개 켜기", "두 팔을 위로 쭉 뻗고 깊게 숨을 쉬며 몸 전체를 늘려요.")],
    "LANGUAGE": [("표현 1", "Good morning. / 좋은 아침이에요. / 굿 모닝"),
                 ("표현 2", "Have a nice day. / 좋은 하루 보내세요. / 해브 어 나이스 데이"),
                 ("표현 3", "Thank you very much. / 정말 고맙습니다. / 땡큐 베리 머치")],
    "QUIZ": [("퀴즈 1번", "문제: 대한민국의 수도는 어디일까요?\n정답: 서울"),
             ("퀴즈 2번", "문제: 1년은 몇 개월일까요?\n정답: 열두 달"),
             ("퀴즈 3번", "문제: 한글을 만든 임금님은 누구일까요?\n정답: 세종대왕"),
             ("퀴즈 4번", "문제: 태양계에서 가장 큰 행성은 무엇일까요?\n정답: 목성"),
             ("퀴즈 5번", "문제: 물은 보통 몇 도에서 끓을까요? (1기압)\n정답: 100도")],
}
FALLBACK_COUNT = {"QUIZ": 3, "EXERCISE": 3}
LANGUAGE_FALLBACKS = {
    "JAPANESE": [("표현 1", "おはようございます / 좋은 아침이에요. / 오하요 고자이마스"),
                 ("표현 2", "ありがとうございます / 감사합니다. / 아리가토 고자이마스"),
                 ("표현 3", "今日もがんばりましょう / 오늘도 힘내요. / 쿄-모 간바리마쇼-")],
    "CHINESE": [("표현 1", "早上好 / 좋은 아침이에요. / 자오샹 하오"),
                ("표현 2", "谢谢 / 고맙습니다. / 씨에씨에"),
                ("표현 3", "加油 / 힘내요. / 지아요우")],
}


def fallback_steps(content_type: str, content_option: str | None) -> list[Step]:
    rows = LANGUAGE_FALLBACKS.get(content_option) if content_type == "LANGUAGE" and content_option in LANGUAGE_FALLBACKS \
        else FALLBACKS[content_type]
    rows = rows[:FALLBACK_COUNT.get(content_type, len(rows))]
    secs = _fit_seconds([1] * len(rows), CONTENT_TOTAL_SEC)
    return [Step(i, n, s, DEFAULT_RESPONSE[content_type], script) for i, ((n, script), s) in enumerate(zip(rows, secs), 1)]


def compose_content_steps(content_type: str, content_option: str | None, llm, today: date | None = None,
                          retries: int = 1) -> ContentStepsResult:
    """고정 5분(CONTENT_TOTAL_SEC) 동안 진행할 단계를 구성한다. AI 호출이 실패해도 기본 단계를 돌려준다."""
    if content_type not in CONTENT_OPTIONS:
        raise ValueError(f"CONTENT_TYPE은 {'/'.join(CONTENT_OPTIONS)} 중 하나예요: {content_type!r}")
    total = CONTENT_TOTAL_SEC
    topic = OPTION_GUIDE.get(content_option or "", "")
    system = SYSTEM.format(count=COUNTS[content_type], total=total, guide=TYPE_GUIDE[content_type],
                           today=(today or date.today()).isoformat())
    user = f"콘텐츠 종류: {content_type}" + (f"\n세부: {content_option} ({topic})" if topic else "")
    last = ""
    for _ in range(retries + 1):
        try:
            return ContentStepsResult(validate_steps(llm.generate_json(system, user), content_type, total), "ai")
        except (LLMError, ValueError) as e:
            last = str(e)
    return ContentStepsResult(fallback_steps(content_type, content_option), "fallback", last)
