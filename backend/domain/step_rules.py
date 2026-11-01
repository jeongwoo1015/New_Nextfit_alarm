"""세션 단계 상태 규칙: AI 판정 반영, 앱에서 직접 체크, 진행 개수."""
from dataclasses import replace
from datetime import datetime
from typing import Iterable

from backend.core.errors import Conflict, ValidationFailed

from . import codes
from .models import Progress, SessionStep

# AI_VERDICT → STEP_STATUS. None이면 상태를 바꾸지 않고 AI_VERDICT만 남긴다.
# not_done은 정의서에 대응 값이 없어 AI 파트 제안(CONTRACTS 1-3)대로 SKIPPED로 둔다 — 팀 확인 필요
VERDICT_STATUS = {"done": "DONE", "skipped": "SKIPPED", "not_done": "SKIPPED", "ambiguous": None, "other": None}


def _set_status(step: SessionStep, status: str, now: datetime) -> SessionStep:
    """CHECK ((STEP_STATUS = 'PENDING') = (CHECKED_AT IS NULL)) 를 지키며 상태를 바꾼다."""
    if status == step.step_status:
        return step
    return replace(step, step_status=status, checked_at=None if status == "PENDING" else now)


def apply_verdict(step: SessionStep, verdict: str, now: datetime) -> SessionStep:
    """Live 코칭의 report_step_result 한 번. 같은 단계에 여러 번 오면 호출 순서대로 적용해 마지막 값이 남는다."""
    if verdict not in codes.AI_VERDICTS:
        raise ValidationFailed(f"verdict는 {'/'.join(codes.AI_VERDICTS)} 중 하나예요: {verdict!r}")
    status = VERDICT_STATUS[verdict]
    step = replace(step, ai_verdict=verdict)
    return _set_status(step, status, now) if status else step


def mark(step: SessionStep, status: str, now: datetime) -> SessionStep:
    """앱 체크리스트에서 사용자가 직접 바꾼 상태 (AI_VERDICT는 그대로 둔다)."""
    if status not in codes.STEP_STATUSES:
        raise ValidationFailed(f"step_status는 {'/'.join(codes.STEP_STATUSES)} 중 하나예요: {status!r}")
    return _set_status(step, status, now)


def progress(steps: Iterable[SessionStep]) -> Progress:
    steps = list(steps)
    return Progress(total=len(steps), done=sum(s.step_status == "DONE" for s in steps),
                    skipped=sum(s.step_status == "SKIPPED" for s in steps))


def pending_steps(steps: Iterable[SessionStep]) -> list[SessionStep]:
    """재연결 시 이어서 진행할 단계 (정의서: PENDING 단계부터)."""
    return sorted((s for s in steps if s.step_status == "PENDING"), key=lambda s: s.step_order)


def today_total(steps: Iterable[SessionStep]) -> int:
    """오늘 진행할 단계 시간 합계 (오늘만 뺀 SKIPPED 단계 제외)."""
    return sum(s.planned_duration_sec or 0 for s in steps if s.step_status != "SKIPPED")


def check_today_duration(steps: list[SessionStep], step: SessionStep, duration_sec: int, max_sec: int) -> None:
    """오늘 진행 중 시간 변경 검사: 아직 하지 않은(PENDING) 단계만, 30초 단위, 바꾼 뒤 오늘 합계 ≤ 상한."""
    if step.step_status != "PENDING":
        raise Conflict("이미 체크한 단계는 시간을 바꿀 수 없어요", "STEP_ALREADY_CHECKED")
    if duration_sec < 0 or duration_sec % codes.DURATION_STEP_SEC:
        raise ValidationFailed(f"단계 시간은 0 이상, {codes.DURATION_STEP_SEC}초 단위예요")
    total = today_total(replace(s, planned_duration_sec=duration_sec) if s.step_id == step.step_id else s for s in steps)
    if total > max_sec:
        raise ValidationFailed(f"오늘 합계 {total}초가 상한 {max_sec}초를 넘어요", "ROUTINE_OVER_LIMIT")
