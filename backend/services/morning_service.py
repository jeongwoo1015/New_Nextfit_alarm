"""아침 흐름: 알람 → 세션 시작 → (재기상) → 브리핑 토큰 → 단계 만들기 + 코칭 토큰 → 판정 반영 → 세션 종료.

정의서 11/01: 알람은 루틴 또는 콘텐츠 중 하나라 브리핑 뒤 단계 세션은 하나다. 단계는 브리핑이 끝나고
코칭 토큰을 요청할 때 PENDING으로 만든다(이미 있으면 그대로 = 재연결).
"""
import logging
from dataclasses import dataclass
from datetime import timedelta

from backend.clients.ai_gateway import (AIGateway, BriefingContext, CalendarEvent, CoachingContext, CoachStep, LiveToken,
                                        Persona, night_view)
from backend.core.errors import AppError, Conflict, NotFound
from backend.core.timeutil import now_kst, to_kst, today_kst
from backend.domain.models import MorningSession, Progress, SessionStep
from backend.domain.routine_rules import build_routine
from backend.domain.session_rules import (content_duration_sec, content_steps, planned_total_sec, resolve_snapshot,
                                          routine_steps)
from backend.domain.step_rules import apply_verdict, mark, pending_steps, progress
from backend.repositories.registry import Repositories

log = logging.getLogger(__name__)

CONTENT_NAMES = {"MEDITATION": "명상", "EXERCISE": "운동", "LANGUAGE": "외국어", "QUIZ": "퀴즈"}
BANDIT_KEY = "global"           # AI CONTRACTS 6-4: 여러 사용자를 합쳐야 경향이 보여 전체 합산 상태를 쓴다
RECENT_DAYS = 7


@dataclass(frozen=True)
class SessionView:
    session: MorningSession
    steps: list[SessionStep]

    @property
    def progress(self) -> Progress:
        return progress(self.steps)


class MorningService:
    def __init__(self, repos: Repositories, ai: AIGateway, clock=now_kst, mode: str = "rule"):
        self.r, self.ai, self.clock, self.mode = repos, ai, clock, mode

    # ---------- 세션 ----------
    def start(self, user_id: str, alarm_id: str) -> SessionView:
        """알람이 울릴 때. 오늘 이 알람의 세션이 있으면 그대로(재연결), 없으면 그날 값으로 스냅샷을 만든다."""
        alarm = self.r.alarms.get_alarm(user_id, alarm_id)
        if not alarm:
            raise NotFound("알람을 찾을 수 없어요", "ALARM_NOT_FOUND")
        now = to_kst(self.clock())
        today = today_kst(now)
        snapshot = resolve_snapshot(alarm, self.r.nights.get_night_for_morning(user_id, today), today)
        s = self.r.sessions.get_or_create_session(snapshot)
        if s.alarm_rang_at is None:
            s = self.r.sessions.update_session(user_id, s.session_id, {"alarm_rang_at": now})
        return self.view(user_id, s.session_id)

    def _session(self, user_id: str, session_id: str) -> MorningSession:
        s = self.r.sessions.get_session(user_id, session_id)
        if not s:
            raise NotFound("모닝 세션을 찾을 수 없어요", "SESSION_NOT_FOUND")
        return s

    def view(self, user_id: str, session_id: str) -> SessionView:
        return SessionView(self._session(user_id, session_id), self.r.sessions.list_steps(user_id, session_id))

    def today(self, user_id: str) -> SessionView:
        """루틴 화면 "오늘의 체크리스트": 오늘 가장 최근에 울린 세션."""
        today = today_kst(self.clock())
        sessions = self.r.sessions.recent_records(user_id, today, today).sessions
        if not sessions:
            raise NotFound("오늘 진행한 모닝 세션이 없어요", "SESSION_NOT_FOUND")
        latest = max(sessions, key=lambda s: s.alarm_rang_at.timestamp() if s.alarm_rang_at else 0)
        return self.view(user_id, latest.session_id)

    def rewake(self, user_id: str, session_id: str) -> int:
        """8초 무응답으로 재기상 음악을 틀 때마다."""
        self._session(user_id, session_id)
        return self.r.sessions.add_rewake(user_id, session_id)

    def respond(self, user_id: str, session_id: str) -> MorningSession:
        """알람 후 첫 음성 응답 (처음 한 번만 기록)."""
        s = self._session(user_id, session_id)
        if s.first_response_at is None:
            s = self.r.sessions.update_session(user_id, session_id, {"first_response_at": to_kst(self.clock())})
        return s

    def end(self, user_id: str, session_id: str) -> SessionView:
        """세션 종료. 기상 성공 기본안(팀 확정 전) = 알람 뒤 사용자가 한 번이라도 응답했는가."""
        s = self._session(user_id, session_id)
        if s.session_end_at is None:
            self.r.sessions.update_session(user_id, session_id, {"session_end_at": to_kst(self.clock()),
                                                                 "wake_success": s.first_response_at is not None})
        return self.view(user_id, session_id)

    # ---------- 브리핑 ----------
    def _persona(self, user_id: str) -> Persona:
        st = self.r.users.get_setting(user_id)
        if not st:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        return Persona(st.user_call_name, st.tone, st.speech_style, st.voice_type)

    def briefing_token(self, user_id: str, session_id: str, events_today: list[CalendarEvent]) -> LiveToken:
        s = self._session(user_id, session_id)
        persona = self._persona(user_id)
        night = self.r.nights.get_night_for_morning(user_id, s.session_date)
        briefing_block, _ = self._strategy(user_id, s, night)
        program = ""
        if s.morning_call_type == "ROUTINE":
            t = self.r.presets.get_template(s.template_id)
            program = t.template_name if t else ""
        ctx = BriefingContext(persona, to_kst(self.clock()).strftime("%H:%M"), tuple(events_today), night_view(night),
                              program, CONTENT_NAMES.get(s.content_type or "", ""), s.coaching_skipped, briefing_block)
        return self.ai.issue_briefing_token(ctx)

    def _strategy(self, user_id: str, s: MorningSession, night) -> tuple[str, str]:
        """오늘의 말하는 방식. 그날 처음 정한 전략을 재연결에서도 다시 쓴다(로그의 strategy로 같은 문장을 만든다).
        개인화가 실패해도 대화는 기본 방식으로 이어진다."""
        saved = self.r.personalization.get_strategy_log(user_id, s.session_date)
        if saved:
            return self.ai.strategy_blocks(saved.strategy)
        if self.mode == "off":
            return "", ""
        try:
            records = self.r.sessions.recent_records(user_id, s.session_date - timedelta(days=RECENT_DAYS), s.session_date - timedelta(days=1))
            setting = self.r.users.get_setting(user_id)
            d = self.ai.decide_strategy(user_id, records, s.session_date, night.condition_label if night else None,
                                        setting.speech_style if setting else None, self.mode,
                                        self.r.personalization.get_bandit_state(BANDIT_KEY))
            self.r.personalization.save_strategy_log(user_id, s.session_id, d.log_row)
            return d.briefing_block, d.coaching_block
        except AppError as e:
            log.warning("개인화 전략 결정 실패 user=%s: %s", user_id, e.message)
            return "", ""

    # ---------- 단계·코칭 ----------
    def ensure_steps(self, user_id: str, session_id: str) -> list[SessionStep]:
        s = self._session(user_id, session_id)
        steps = self.r.sessions.list_steps(user_id, session_id)
        if steps or s.coaching_skipped:
            return steps
        if s.morning_call_type == "ROUTINE":
            plans = routine_steps(self._routine_items(user_id, s))
        else:
            duration = content_duration_sec(self.r.presets.get_app_config())
            plans = content_steps(self.ai.compose_content_steps(s.content_type, s.content_option, duration, s.session_date))
        steps = self.r.sessions.create_steps(user_id, session_id, plans)
        self.r.sessions.update_session(user_id, session_id, {"planned_total_sec": planned_total_sec(plans)})
        return steps

    def _routine_items(self, user_id: str, s: MorningSession):
        """그날 알람의 항목 시간. 알람이 지워졌거나 그사이 프리셋이 바뀌었으면 세션 프리셋의 기본 시간."""
        alarm = self.r.alarms.get_alarm(user_id, s.alarm_id) if s.alarm_id else None
        if alarm and alarm.template_id == s.template_id:
            items = self.r.alarms.get_alarm_routine(user_id, s.alarm_id)
            if items is not None:
                return items
        return build_routine(self.r.presets.list_template_items(s.template_id), {})

    def coaching_token(self, user_id: str, session_id: str) -> tuple[LiveToken, list[SessionStep]]:
        """브리핑에서 start_routine이 오면(또는 재연결) 호출. 남은 PENDING 단계만 넘긴다."""
        s = self._session(user_id, session_id)
        if s.coaching_skipped:
            raise Conflict("오늘은 코칭을 하지 않기로 한 날이에요", "COACHING_SKIPPED")
        steps = self.ensure_steps(user_id, session_id)
        pending = pending_steps(steps)
        if not pending:
            raise Conflict("모든 단계를 마쳤어요", "ALL_STEPS_CHECKED")
        night = self.r.nights.get_night_for_morning(user_id, s.session_date)
        _, coaching_block = self._strategy(user_id, s, night)
        ctx = CoachingContext(self._persona(user_id), s.morning_call_type, tuple(self._coach_steps(s, pending)),
                              resumed=len(pending) < len(steps), night=night_view(night), strategy_block=coaching_block)
        return self.ai.issue_coaching_token(ctx), steps

    def _coach_steps(self, s: MorningSession, steps: list[SessionStep]) -> list[CoachStep]:
        """루틴 단계의 응답 유형·고정 스크립트는 프리셋 항목에서, 콘텐츠 단계는 step_detail에서."""
        items = {i.template_item_id: i for i in self.r.presets.list_template_items(s.template_id)} if s.template_id else {}
        out = []
        for st in steps:
            if st.template_item_id and st.template_item_id in items:
                it = items[st.template_item_id]
                rtype, script = it.response_type, it.script or ""
            else:
                detail = st.step_detail or {}
                rtype, script = detail.get("response_type") or "QA", detail.get("script") or ""
            out.append(CoachStep(st.step_order, st.step_name, st.planned_duration_sec, rtype, script))
        return out

    def _step(self, user_id: str, session_id: str, step_order: int) -> SessionStep:
        self._session(user_id, session_id)
        step = next((x for x in self.r.sessions.list_steps(user_id, session_id) if x.step_order == step_order), None)
        if not step:
            raise NotFound("단계를 찾을 수 없어요", "STEP_NOT_FOUND")
        return step

    def report_verdict(self, user_id: str, session_id: str, step_order: int, verdict: str) -> SessionView:
        """Live 코칭의 report_step_result(step_order, verdict)를 앱이 전달. 같은 단계에 여러 번 오면 마지막 값."""
        step = apply_verdict(self._step(user_id, session_id, step_order), verdict, to_kst(self.clock()))
        self.r.sessions.save_step(user_id, step)
        return self.view(user_id, session_id)

    def mark_step(self, user_id: str, session_id: str, step_order: int, status: str) -> SessionView:
        """체크리스트에서 사용자가 직접 체크·해제."""
        step = mark(self._step(user_id, session_id, step_order), status, to_kst(self.clock()))
        self.r.sessions.save_step(user_id, step)
        return self.view(user_id, session_id)
