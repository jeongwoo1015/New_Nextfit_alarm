"""RS_NIGHT_RAW.TRANSCRIPT([{role, text, at}])를 AI에 넘길 글자로 바꾼다."""
import json

USER_ROLES = {"user", "human", "사용자"}
AI_ROLES = {"assistant", "model", "ai", "bot"}   # 정의서에 role 값이 정해져 있지 않아 흔한 이름을 모두 받는다
MAX_CHARS = 12000


def normalize(transcript) -> list[tuple[str, str]]:
    """[(화자, 글자)] 목록. 화자는 '사용자'/'AI'. 빈 글자와 알 수 없는 형식은 버린다. JSON 문자열도 받는다."""
    if isinstance(transcript, str):
        try:
            transcript = json.loads(transcript)
        except json.JSONDecodeError:
            return []
    out = []
    for turn in transcript or []:
        if not isinstance(turn, dict):
            continue
        text = str(turn.get("text") or "").strip()
        if not text:
            continue
        role = str(turn.get("role") or "").strip().lower()
        out.append(("사용자" if role in USER_ROLES else "AI", text))
    return out


def has_user_speech(turns: list[tuple[str, str]]) -> bool:
    return any(who == "사용자" for who, _ in turns)


def to_text(turns: list[tuple[str, str]]) -> str:
    """너무 길면 앞부분을 버리고 뒷부분을 남긴다 (마무리에 동의 내용이 나오기 때문)."""
    text = "\n".join(f"{who}: {t}" for who, t in turns)
    return text[-MAX_CHARS:]
