"""자막용 숫자 표기. Gemini Live 받아쓰기(outputTranscription)는 AI가 "5초"를 바르게 "오 초"라고 읽어도 글자를 "오 초"로 적는다.
음성은 고칠 것이 없고 화면 자막만 "5초"로 바꾼다. 앱은 자막을 그리기 직전(한 줄이 다 모인 글자)에 이 규칙을 적용한다.
조각 단위(받아쓰기 한 덩어리)로 바꾸면 "열"과 "두 시"가 나뉘어 틀리므로 반드시 모인 글자에 적용한다.

- 한자어 수 + 초·분·도·퍼센트: 오 초 → 5초, 이십 분 → 20분, 십일 도 → 11도
- 고유어 수 + 시·번·시간·세트·가지: 열두 시 → 12시, 다섯 번 → 5번, 여섯 시간 → 6시간
- '한 번', '한 시간'처럼 '한'은 바꾸지 않는다("한 번 더"가 "1번 더"가 되면 어색하다). '한 시'(1시)만 바꾼다.
"""
import re

SINO_DIGIT = {"일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9}
NATIVE_ONES = {"한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9}
NATIVE_TENS = {"열": 10, "스물": 20, "스무": 20, "서른": 30, "마흔": 40, "쉰": 50}

SINO = r"(?:[이삼사오육칠팔구]?백)?(?:[이삼사오육칠팔구]?십)?[일이삼사오육칠팔구]?"
NATIVE = r"(?:열|스물|스무|서른|마흔|쉰)?(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉)?"
SINO_UNITS = "초|분|도|퍼센트"
NATIVE_UNITS = "시간|시|번|세트|가지"

# 수 앞이 한글·숫자가 아니고(단어 중간이 아님), 단위 뒤가 다른 낱말로 이어지지 않는 자리만 바꾼다("초록", "분위기", "시작", "번호" 제외)
NOT_WORD = "록콜대위명석야리착전움작장켜험절선원역호개"
SINO_RE = re.compile(rf"(?<![가-힣0-9])({SINO}) ?({SINO_UNITS})(?![{NOT_WORD}])")
NATIVE_RE = re.compile(rf"(?<![가-힣0-9])({NATIVE}) ?({NATIVE_UNITS})(?![{NOT_WORD}])")


def sino_value(word: str) -> int | None:
    if not word:
        return None
    total, num = 0, 0
    for ch in word:
        if ch in SINO_DIGIT:
            num = SINO_DIGIT[ch]
        elif ch == "십":
            total += (num or 1) * 10
            num = 0
        elif ch == "백":
            total += (num or 1) * 100
            num = 0
        else:
            return None
    return total + num


def native_value(word: str) -> int | None:
    if not word:
        return None
    for tens, tv in sorted(NATIVE_TENS.items(), key=lambda x: -len(x[0])):
        if word.startswith(tens):
            rest = word[len(tens):]
            if not rest:
                return tv
            return tv + NATIVE_ONES[rest] if rest in NATIVE_ONES else None
    return NATIVE_ONES.get(word)


def _sino(m: re.Match) -> str:
    word, unit = m.group(1), m.group(2)
    value = sino_value(word)
    # '이 분'(this person), '이 도'처럼 한 글자 '이·사·구'만 오면 숫자인지 알 수 없어 그대로 둔다
    if value is None or (len(word) == 1 and word in "이사구일" and unit in ("분", "도")):
        return m.group(0)
    return f"{value}{unit}"


def _native(m: re.Match) -> str:
    word, unit = m.group(1), m.group(2)
    value = native_value(word)
    if value is None or (word == "한" and unit != "시"):
        return m.group(0)
    return f"{value}{unit}"


def subtitle_numbers(text: str) -> str:
    """자막 한 줄의 수 읽기를 숫자 표기로 바꾼다. 이미 숫자인 곳과 수가 아닌 말은 그대로 둔다."""
    return NATIVE_RE.sub(_native, SINO_RE.sub(_sino, text or ""))
