"""끝말잇기 글자 판정(11/25 앱 요청). 콘텐츠 탭에서 모닝콜 때 끝나지 않은 끝말잇기를 글자로 이어 가는 화면용이다.

실시간 음성(Live)이 아니라 서버가 글자로 한 번씩 부른다. 한 번 부를 때 두 가지를 한다.
  1) 사용자가 낸 단어가 실제 단어(사전에 있는 일반 명사)인지 본다.
  2) 맞으면 사용자 단어의 끝 글자로 시작하는 쉬운 일상 명사 하나를 넥스티 단어로 고른다.

끝 글자·두음법칙·중복·글자 수는 앱이 먼저 확인하고 보내지만, 여기서도 같은 규칙을 코드로 한 번 더 확인한다(AI를 부르지 않고 거른다).
AI가 준 넥스티 단어도 코드로 검사하고, 어긋나면 다시 묻는다. 끝까지 어긋나면 FAILED로 돌려준다.
"""
import re
from dataclasses import dataclass

from .llm import LLMError

HANGUL = re.compile(r"[가-힣]+")
MAX_REASON = 40
DEFAULT_REASON = "사전에 없는 말이에요"

SYSTEM = """당신은 끝말잇기 심판이자 상대입니다. 사용자가 낸 단어가 실제 단어인지 판단하고, 맞으면 이어 갈 단어 하나를 고릅니다.
JSON 객체 하나로만 출력하세요: {"valid": true 또는 false, "reason": "...", "next": "..." 또는 null}

[단어 판단 — 사용자 단어]
- 표준국어대사전에 올라 있는 두 글자 이상의 일반 명사(일상에서 쓰는 외래어 명사 포함)이면 valid=true.
- 아래는 valid=false: 뜻 없는 글자 나열, 동사·형용사·부사·조사·감탄사, 줄임말·초성·은어, 인명·지명·상표 같은 고유명사.
- 확실히 아니라고 판단될 때만 false로 한다. 일상에서 흔히 쓰는 명사인지 애매하면 true로 한다(게임이 즐거워야 한다).
- 끝 글자, 두음법칙, 중복, 글자 수는 이미 확인했으니 판단하지 않는다.
- valid=false면 reason에 화면에 그대로 보여 줄 짧은 존댓말 한 문장(40자 이내, 예: "사전에 없는 말이에요", "명사가 아니에요", "사람 이름은 쓸 수 없어요")을 쓰고 next는 null. valid=true면 reason은 "".

[넥스티 단어 — next]
- valid=true일 때, 사용자 단어의 끝 글자로 시작하는 쉬운 일상 명사 하나. 사용자가 이어 가기 쉬운 단어를 고른다.
- 두음법칙을 따라 시작 글자를 바꿔도 된다(리→이, 라→나, 녀→여 등). 이 경우 [이을 수 있는 시작 글자]에 적힌 글자 중 하나로 시작한다.
  시작 글자가 둘이면 바뀐 글자(이·나·여 등)로 시작하는 쉬운 단어가 훨씬 흔하니 바뀐 글자를 우선 찾는다(예: 계란 → 란마보다 난로).
- 두 글자 이상. 이미 나온 단어는 쓰지 않는다. 옛말·방언·전문 용어·인명·지명·어려운 단어는 쓰지 않는다.
- 초등학생도 아는 쉬운 단어만 쓴다. 그런 단어가 정말 없으면 어려운 말로 억지로 잇지 말고 null. 조금만 찾아봐도 쉬운 단어가 있으면 null로 하지 않는다.
- next에는 단어만 쓴다(설명·조사 없이)."""

# 두음법칙: ㄹ → ㅇ(ㅑ ㅒ ㅕ ㅖ ㅛ ㅠ ㅣ 앞) 또는 ㄴ(그 밖), ㄴ → ㅇ(ㅕ ㅛ ㅠ ㅣ 앞)
_CHO_NIEUN, _CHO_RIEUL, _CHO_IEUNG = 2, 5, 11
_RIEUL_TO_IEUNG = {2, 3, 6, 7, 12, 17, 20}
_NIEUN_TO_IEUNG = {6, 12, 17, 20}


def _is_syllable(ch: str) -> bool:
    return "가" <= ch <= "힣"


def _swap_initial(ch: str, cho: int) -> str:
    code = ord(ch) - 0xAC00
    jung_jong = code % 588
    return chr(0xAC00 + cho * 588 + jung_jong)


def start_letters(last: str) -> list[str]:
    """앞 단어의 끝 글자 → 다음 단어가 시작할 수 있는 글자들(두음법칙 포함). 한글 한 글자가 아니면 빈 목록."""
    if len(last) != 1 or not _is_syllable(last):
        return []
    cho, jung = (ord(last) - 0xAC00) // 588, ((ord(last) - 0xAC00) % 588) // 28
    out = [last]
    if cho == _CHO_RIEUL:
        out.append(_swap_initial(last, _CHO_IEUNG if jung in _RIEUL_TO_IEUNG else _CHO_NIEUN))
    elif cho == _CHO_NIEUN and jung in _NIEUN_TO_IEUNG:
        out.append(_swap_initial(last, _CHO_IEUNG))
    return out


@dataclass
class WordchainResult:
    valid: bool | None = None         # True / False. FAILED면 None(판정하지 못함)
    next: str | None = None           # 넥스티 단어. valid=True이고 이을 단어가 없으면 None (앱은 넥스티 패배로 처리)
    reason: str = ""                  # valid=False일 때 화면에 보여 줄 한 문장
    status: str = "DONE"              # DONE / FAILED
    note: str = ""                    # FAILED일 때 사유(로그용)

    def to_dict(self) -> dict:
        """앱에 내려줄 JSON. {"valid": true, "next": "정원"} / {"valid": true, "next": null} / {"valid": false, "reason": "..."}.
        FAILED면 {"status": "FAILED"}만 준다(서버가 다시 시도하거나 오류로 돌려준다)."""
        if self.status != "DONE":
            return {"status": "FAILED"}
        if self.valid:
            return {"valid": True, "next": self.next}
        return {"valid": False, "reason": self.reason}


def _clean(w) -> str:
    return re.sub(r"\s+", "", str(w or ""))


def check_rules(words: list[str], word: str) -> str:
    """코드로 확인하는 규칙. 어긋나면 화면에 보여 줄 이유를, 맞으면 빈 문자열을 돌려준다(앱이 먼저 확인하므로 보통 걸리지 않는다)."""
    if not HANGUL.fullmatch(word):
        return "한글 단어만 쓸 수 있어요"
    if len(word) < 2:
        return "두 글자 이상이어야 해요"
    if word in words:
        return "이미 나온 단어예요"
    if words and word[0] not in start_letters(words[-1][-1]):
        return f"{' 또는 '.join(repr(c) for c in start_letters(words[-1][-1])) or repr(words[-1][-1])}(으)로 시작하는 단어를 말해 주세요"
    return ""


def _next_problem(nxt: str, user_word: str, used: list[str]) -> str:
    """넥스티 단어가 규칙에 맞는지. 맞으면 빈 문자열, 아니면 다시 묻는 데 쓸 이유."""
    if not HANGUL.fullmatch(nxt):
        return "한글 단어가 아니에요"
    if len(nxt) < 2:
        return "한 글자 단어예요"
    if nxt in used or nxt == user_word:
        return "이미 나온 단어예요"
    if nxt[0] not in start_letters(user_word[-1]):
        return f"'{'·'.join(start_letters(user_word[-1]))}' 중 하나로 시작하지 않아요"
    return ""


def _prompt(used: list[str], word: str, starts: list[str], hint: str = "") -> str:
    lines = [f"[지금까지 나온 단어] {', '.join(used) if used else '(없음)'}",
             f"[사용자가 이번에 낸 단어] {word}",
             f"[이을 수 있는 시작 글자] {', '.join(starts)}"]
    if hint:
        lines.append(f"[다시 확인] {hint}")
    return "\n".join(lines)


def judge_wordchain(words, word, llm, retries: int = 2) -> WordchainResult:
    """words: 지금까지 이어진 단어(순서대로, 시작 단어 포함), word: 사용자가 이번에 낸 단어.
    규칙 위반은 AI를 부르지 않고 valid=False로 돌려준다. AI 호출이 실패하거나 넥스티 단어가 끝까지 규칙에 어긋나면 FAILED."""
    prev = [_clean(w) for w in (words or []) if _clean(w)]
    user_word = _clean(word)
    reason = check_rules(prev, user_word)
    if reason:
        return WordchainResult(valid=False, reason=reason)

    used = prev + [user_word]
    starts = start_letters(user_word[-1])
    hint, last, asked_null, errors = "", "", False, 0
    for _ in range(retries + 1):
        try:
            data = llm.generate_json(SYSTEM, _prompt(prev, user_word, starts, hint))
            verdict = _verdict(data)
        except (LLMError, ValueError) as e:
            last = str(e)
            errors += 1
            if errors >= 2:                      # 호출이 두 번 실패하면 멈춘다(GeminiText가 이미 모델을 바꿔 가며 시도했고, 사용자가 기다리고 있다)
                break
            continue
        if verdict.valid is False:
            return verdict
        if verdict.next is None:
            if asked_null:                       # 두 번 물어도 이을 단어가 없다고 하면 없는 것으로 본다
                return verdict
            asked_null, hint = True, "정말 이을 수 있는 쉬운 명사가 없는지 한 번 더 찾아 보세요. 있으면 그 단어를, 정말 없을 때만 null로 답하세요."
            continue
        problem = _next_problem(verdict.next, user_word, used)
        if not problem:
            return verdict
        hint, last = f"방금 고른 '{verdict.next}'은(는) 쓸 수 없어요({problem}). 규칙에 맞는 다른 단어를 고르세요.", problem
    return WordchainResult(status="FAILED", note=last or "응답을 받지 못했어요")


def _verdict(data: dict) -> WordchainResult:
    valid = data.get("valid")
    if not isinstance(valid, bool):
        raise ValueError("valid가 true/false가 아니에요")
    if not valid:
        reason = re.sub(r"\s+", " ", str(data.get("reason") or "")).strip()[:MAX_REASON] or DEFAULT_REASON
        return WordchainResult(valid=False, reason=reason)
    nxt = data.get("next")
    nxt = _clean(nxt) if nxt is not None else ""
    return WordchainResult(valid=True, next=nxt or None)
