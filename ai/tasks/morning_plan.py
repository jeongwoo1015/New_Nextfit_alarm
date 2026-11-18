"""모닝콜 순서표(morning_plan). 아침은 브리핑·한마디 듣기·코칭(루틴/콘텐츠)·포춘이 각각 따로 이어지는 단계라,
어떤 순서로 진행할지를 AI 쪽 코드가 정해 서버가 세션 시작 응답에 실어 앱에 넘긴다(앱은 이 배열을 앞에서부터 실행).
같은 순서표를 각 세션 입력(plan)에도 넣어 AI의 안내 문장과 실제 진행이 항상 같게 한다.

순서를 바꿀 때는 이 파일의 ORDER만 고친다.
- 11/12 회의: 포춘을 마지막으로 옮김 → BRIEFING → ONE_LINER → COACHING → FORTUNE
- LEGACY_ORDER: 서버가 아직 순서표를 넘기지 않을 때(=지금 앱이 고정해 둔 순서) 프롬프트가 쓰는 순서
"""
BRIEFING, ONE_LINER, COACHING, FORTUNE = "BRIEFING", "ONE_LINER", "COACHING", "FORTUNE"
STAGES = (BRIEFING, ONE_LINER, COACHING, FORTUNE)
ORDER = (BRIEFING, ONE_LINER, COACHING, FORTUNE)
LEGACY_ORDER = (BRIEFING, ONE_LINER, FORTUNE, COACHING)
LISTENING = (ONE_LINER, FORTUNE)          # 사용자가 듣기만 하는 단계 (브리핑 마무리 질문이 "~부터 들어 볼까요?"가 된다)


def morning_plan(has_one_liner: bool, coaching: bool = True, order: tuple[str, ...] = ORDER) -> tuple[str, ...]:
    """그날 순서표.
    has_one_liner: 어젯밤 '내일의 나에게 한마디' 녹음이 있으면 True (없으면 ONE_LINER를 뺀다)
    coaching: 루틴·콘텐츠를 하는 날이면 True. 밤에 '내일 코칭 안 하기'에 동의했거나 이미 다 마친 날은 False (COACHING을 뺀다)
    포춘은 항상 넣는다(토큰 발급에 실패하면 앱이 그 단계만 건너뛴다)."""
    return tuple(s for s in order if (s != ONE_LINER or has_one_liner) and (s != COACHING or coaching))


def normalize_plan(plan) -> tuple[str, ...]:
    """서버가 넘긴 순서표를 정리한다: 대문자로 맞추고, 모르는 값과 중복은 버린다. 비어 있거나 잘못된 값이면 ()."""
    out: list[str] = []
    for s in plan or ():
        code = str(s).strip().upper()
        if code in STAGES and code not in out:
            out.append(code)
    return tuple(out)


def neighbors(plan: tuple[str, ...], stage: str) -> tuple[str, str]:
    """순서표에서 stage의 바로 앞 단계와 바로 뒤 단계 ("" = 없음)."""
    if stage not in plan:
        return "", ""
    i = plan.index(stage)
    return (plan[i - 1] if i > 0 else ""), (plan[i + 1] if i + 1 < len(plan) else "")
