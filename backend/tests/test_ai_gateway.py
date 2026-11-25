from dataclasses import replace
from datetime import date, time

import pytest

from backend.clients.ai_gateway import (RealAIGateway, UnavailableAIGateway, build_ai_gateway, fit_seconds, night_view,
                                        to_night_record_fields)
from backend.core.config import Settings
from backend.core.errors import ServiceUnavailable
from backend.domain import codes
from backend.domain.models import NightRecord

AI_ROW = {"ai_summary": "발표를 마쳤다", "tomorrow_goal": "보고서 끝내기", "tomorrow_notes": ["10시 회의"],
          "condition_label": "TIRED", "condition_text": "지쳤지만 뿌듯함", "encouragement": "잘했어요", "insight": "",
          "diary_text": "발표 끝!", "override_wake_time": "06:40", "override_skip_coaching": True, "extract_status": "DONE"}


def test_extract_row_maps_to_1101_columns():
    f = to_night_record_fields(AI_ROW)
    assert f["override_wake_time"] == time(6, 40) and f["override_skip_coaching"] is True
    assert "extract_status" not in f
    assert f["tomorrow_notes"] == ["10시 회의"] and f["condition_label"] == "TIRED"


@pytest.mark.parametrize("value, skip", [(True, True), (False, False), (None, False), ("true", False)])
def test_skip_coaching_only_when_true(value, skip):
    assert to_night_record_fields({**AI_ROW, "override_skip_coaching": value})["override_skip_coaching"] is skip


def test_missing_wake_time_is_none():
    assert to_night_record_fields({**AI_ROW, "override_wake_time": None})["override_wake_time"] is None


def test_without_key_gateway_is_unavailable():
    gw = build_ai_gateway(Settings())
    assert isinstance(gw, UnavailableAIGateway)
    with pytest.raises(ServiceUnavailable):
        gw.extract_night_record([])
    assert gw.strategy_blocks({"strategy_id": "gentle"}) == ("", "")      # 전략 문장이 없으면 기본 방식


def test_with_key_and_ai_package_gateway_is_real():
    assert isinstance(build_ai_gateway(Settings(gemini_api_key="k")), RealAIGateway)   # feat/ai 병합 후


def test_fit_seconds_keeps_ratio_and_total():
    assert sum(fit_seconds([200, 400], 600)) == 600 and fit_seconds([200, 400], 600) == [200, 400]
    assert sum(fit_seconds([120, 120, 120], 480)) == 480                       # 10분 구성을 8분 고정 시간에 맞춤
    assert fit_seconds([None, 0, 300], 300)[0] >= 10


def test_night_view_only_for_extracted_record():
    rec = NightRecord("r", "u", date(2026, 11, 5), date(2026, 11, 6), tomorrow_goal="보고서", tomorrow_notes=("회의",))
    assert night_view(rec) is None                                              # PENDING
    v = night_view(replace(rec, extract_status="DONE"))
    assert v.tomorrow_goal == "보고서" and v.tomorrow_notes == ("회의",) and v.ai_summary == ""


# ---------- 11/16: 실제 ai 패키지에 새 입력이 들어가는지 (네트워크 없이 지시문만 만든다) ----------
def _capture(gw):
    setups = []
    gw._issue = lambda kind, setup: setups.append((kind, setup)) or kind
    return setups


def test_real_gateway_passes_plan_persona_and_stretch_routines():
    from backend.clients.ai_gateway import BriefingContext, CoachingContext, CoachStep, Persona
    gw = RealAIGateway("k")
    setups = _capture(gw)
    persona = Persona("정우님", "CASUAL", "KIND", "MALE", "STRICT", 3, True)
    plan = gw.morning_plan(True, True)
    assert plan == ("BRIEFING", "ONE_LINER", "COACHING", "FORTUNE")
    gw.issue_briefing_token(BriefingContext(persona, "07:00", content_name="스트레칭", plan=plan))
    routines = gw.stretch_routines(codes.DEFAULT_STRETCH_DURATION_SEC)
    assert tuple(routines) == codes.STRETCH_AREAS                          # 부위 12개 (11/18)
    assert all(len(steps) == 4 and sum(s.planned_sec for s in steps) == 180 for steps in routines.values())
    coach = tuple((a, tuple(CoachStep(n, s.name, s.planned_sec, s.response_type, s.script) for n, s in enumerate(st, 1)))
                  for a, st in routines.items())
    full = coach[0][1]
    gw.issue_coaching_token(CoachingContext(persona, "CONTENT", full, content_type="EXERCISE", content_option="STRETCH",
                                            plan=plan, stretch_routines=coach))
    text = str(setups[-1][1])
    assert "select_stretch_area" in text and "턱 당기기" in text           # 부위 고르기 함수와 다른 부위 루틴이 지시문에


def test_real_gateway_intensity_reads_morning_sessions():
    from backend.domain.models import MorningSession
    gw = RealAIGateway("k")
    hard = MorningSession("s", "u", "a", date(2026, 11, 1), "CONTENT", None, "EXERCISE", "STRETCH", time(7), time(7),
                          wake_success=False)
    assert gw.today_intensity(1, [hard, hard, hard], True) == 2               # 3번 중 2번 이상 힘들면 한 단계
    assert gw.today_intensity(1, [hard, hard, hard], False) == 1


def test_stretch_area_codes_match_ai():
    """서버 부위 목록·옛 값 변환이 AI stretch_sets와 같아야 select_stretch_area가 422 없이 통과한다 (11/18 부위 12개)."""
    from ai.tasks.stretch_sets import AREAS, LEGACY_AREAS, TOTAL_SEC, normalize_area
    assert codes.STRETCH_AREAS == AREAS and codes.LEGACY_STRETCH_AREAS == LEGACY_AREAS
    assert codes.DEFAULT_STRETCH_DURATION_SEC == TOTAL_SEC
    assert all(codes.normalize_stretch_area(a) == normalize_area(a) for a in codes.STRETCH_AREA_INPUTS)


def test_real_gateway_correct_wake_moves_one_success_to_failure():
    """서버가 AI WakeLadder 칸을 직접 고친다(11/24 미루기). AI 저장 형식이 바뀌면 여기서 깨진다."""
    from ai.personalization.wake_volume import WakeLadder
    gw = RealAIGateway("k")
    state = gw.learn_wake(None, "ok", 1, 3, True)
    state = gw.learn_wake(state, "ok", 1, 3, True)
    fixed = gw.correct_wake(state, "ok", 3)
    ladder = WakeLadder.from_dict(fixed)
    assert ladder.get("ok", 3) == (1, 1) and ladder.get("ok", 1) == (0, 2)   # 받은 단계만 바뀌고 앞 단계 실패는 그대로
    assert gw.correct_wake(gw.correct_wake(fixed, "ok", 3), "ok", 3) is None  # 옮길 성공이 없으면 None
    assert gw.correct_wake(None, "short", 2) is None
