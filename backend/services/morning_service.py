"""아침 흐름: 알람 → 세션 시작 → (재기상) → 브리핑 토큰 → 단계 만들기 + 코칭 토큰 → 판정 반영 → 세션 종료.

정의서 11/01: 알람은 루틴 또는 콘텐츠 중 하나라 브리핑 뒤 단계 세션은 하나다. 단계는 브리핑이 끝나고
코칭 토큰을 요청할 때 PENDING으로 만든다(이미 있으면 그대로 = 재연결).
"""
import logging
import threading
from collections import defaultdict
from dataclasses import dataclass
from datetime import timedelta

from backend.clients.ai_gateway import (AIGateway, BriefingContext, CalendarEvent, CoachingContext, CoachStep, Fortune,
                                        LiveToken, Persona, night_view)
from backend.clients.weather import NoWeather, WeatherClient
from backend.core.errors import AppError, Conflict, NotFound
from backend.core.timeutil import now_kst, to_kst, today_kst
from backend.domain.models import MorningSession, Progress, SessionStep
from backend.domain.routine_rules import build_routine
from backend.domain.session_rules import (content_duration_sec, content_steps, judge_wake, planned_total_sec,
                                          resolve_snapshot, routine_steps)
from backend.domain.step_rules import apply_verdict, check_today_duration, mark, pending_steps, progress, today_total
from backend.repositories.registry import Repositories

log = logging.getLogger(__name__)

CONTENT_NAMES = {"MEDITATION": "명상", "EXERCISE": "스트레칭", "LANGUAGE": "영어 따라 읽기", "QUIZ": "퀴즈 게임"}   # B14 이름
OPTION_NAMES = {"PERSON": "인물 퀴즈", "GENERAL": "상식 퀴즈", "WORDCHAIN": "끝말잇기", "JAPANESE": "일본어", "CHINESE": "중국어"}


def content_name(content_type: str | None, content_option: str | None) -> str:
    """브리핑에서 말할 오늘 콘텐츠 이름. 세부가 정해져 있으면 세부 이름(끝말잇기 등), 아니면 종류 이름."""
    return OPTION_NAMES.get(content_option or "") or CONTENT_NAMES.get(content_type or "", "")
BANDIT_KEY = "global"           # AI CONTRACTS 6-4: 여러 사용자를 합쳐야 경향이 보여 전체 합산 상태를 쓴다
RECENT_DAYS = 7

# 같은 세션의 포춘을 백그라운드와 토큰 요청이 동시에 만들지 않게 (서버 프로세스 하나 기준, 문장은 세션에 한 번만 저장)
_fortune_locks: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)


@dataclass(frozen=True)
class Location:
    """앱이 브리핑 토큰 요청에 보내는 현재 위치. name은 기기에서 만든 한국어 지역 이름(예: "광진구")."""
    lat: float
    lon: float
    name: str = ""


@dataclass(frozen=True)
class SessionView:
    session: MorningSession
    steps: list[SessionStep]

    @property
    def progress(self) -> Progress:
        return progress(self.steps)


class MorningService:
    def __init__(self, repos: Repositories, ai: AIGateway, clock=now_kst, mode: str = "rule",
                 weather: WeatherClient | None = None):
        self.r, self.ai, self.clock, self.mode = repos, ai, clock, mode
        self.weather = weather or NoWeather()

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
        """8초 무응답으로 재기상(AI) 음악을 틀 때마다. 확인 멘트를 마치기 전이면 기상 실패가 된다."""
        self._session(user_id, session_id)
        return self.r.sessions.add_rewake(user_id, session_id)

    def snooze(self, user_id: str, session_id: str) -> int:
        """"5분 뒤 다시 울리기"를 누를 때마다 (횟수 제한 없음, 한 번이라도 하면 기상 실패). 다시 울리면 같은 세션이 이어진다."""
        self._session(user_id, session_id)
        return self.r.sessions.add_snooze(user_id, session_id)

    def respond(self, user_id: str, session_id: str) -> MorningSession:
        """알람 후 첫 음성 응답 (처음 한 번만 기록). 기상 성공 시각 = 이 시각."""
        s = self._session(user_id, session_id)
        if s.first_response_at is None:
            s = self.r.sessions.update_session(user_id, session_id, {"first_response_at": to_kst(self.clock())})
        return s

    def wake_check(self, user_id: str, session_id: str) -> SessionView:
        """브리핑 전 확인 멘트를 모두 마쳤을 때 (AI의 wake_check_done을 앱이 전달). 이때 기상 성공을 정한다.
        처음 한 번만 판정하고, 실패해도 브리핑·루틴은 그대로 이어진다."""
        s = self._session(user_id, session_id)
        if s.wake_checked_at is None:
            now = to_kst(self.clock())
            if s.first_response_at is None:      # 확인 멘트에 답했다는 뜻이므로 첫 응답이 빠졌으면 지금으로 채운다
                s = self.r.sessions.update_session(user_id, session_id, {"first_response_at": now})
            self.r.sessions.update_session(user_id, session_id, {"wake_checked_at": now, "wake_success": judge_wake(s)})
        return self.view(user_id, session_id)

    def end(self, user_id: str, session_id: str) -> SessionView:
        """세션 종료. 확인 멘트를 끝내지 못한 채 끝나면 기상 실패로 남는다(WAKE_SUCCESS 기본값 FALSE)."""
        s = self._session(user_id, session_id)
        if s.session_end_at is None:
            self.r.sessions.update_session(user_id, session_id, {"session_end_at": to_kst(self.clock())})
        return self.view(user_id, session_id)

    # ---------- 브리핑 ----------
    def _persona(self, user_id: str) -> Persona:
        st = self.r.users.get_setting(user_id)
        if not st:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        return Persona(st.user_call_name, st.tone, st.speech_style, st.voice_type)

    def briefing_token(self, user_id: str, session_id: str, events_today: list[CalendarEvent],
                       location: Location | None = None) -> LiveToken:
        """location은 앱이 매번 보내는 현재 위치(저장하지 않음). 없거나 날씨를 못 가져오면 날씨 없이 브리핑."""
        s = self._session(user_id, session_id)
        persona = self._persona(user_id)
        night = self.r.nights.get_night_for_morning(user_id, s.session_date)
        briefing_block, _ = self._strategy(user_id, s, night)
        program = ""
        if s.morning_call_type == "ROUTINE":
            t = self.r.presets.get_template(s.template_id)
            program = t.template_name if t else ""
        weather = self.weather.today(location.lat, location.lon, location.name) if location else None
        ctx = BriefingContext(persona, to_kst(self.clock()).strftime("%H:%M"), tuple(events_today), night_view(night),
                              program, content_name(s.content_type, s.content_option), s.coaching_skipped, briefing_block,
                              weather)
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

    # ---------- 오늘의 포춘 (CONTRACTS B13, 11/10 결정) ----------
    def prepare_fortune(self, user_id: str, session_id: str) -> MorningSession:
        """세션에 오늘의 포춘 문장이 없으면 만들어 저장한다. 세션 생성 직후 백그라운드에서, 포춘 토큰 때 아직 없으면 그 자리에서.
        재료 = 어젯밤 받아쓴 한마디(VOICE_TEXT), 없으면 한 줄 일기. 둘 다 없거나 AI가 실패해도 AI 쪽이 기본 문장을 준다."""
        with _fortune_locks[session_id]:
            s = self._session(user_id, session_id)
            if s.fortune_text:
                return s
            night = self.r.nights.get_night_for_morning(user_id, s.session_date)
            setting = self.r.users.get_setting(user_id)
            one_liner = (night.voice_text or "") if night else ""
            diary = (night.diary_text or "") if night else ""
            f = self.ai.compose_fortune(one_liner, setting.tone if setting else "FORMAL", s.session_date, diary)
            return self.r.sessions.update_session(user_id, session_id, {"fortune_text": f.text, "fortune_style": f.style,
                                                                        "fortune_source": f.source})

    def prepare_fortune_quietly(self, user_id: str, session_id: str) -> None:
        """백그라운드용: 실패해도 알람 흐름에 영향 없이 로그만 남긴다 (포춘 토큰 때 다시 시도)."""
        try:
            self.prepare_fortune(user_id, session_id)
        except Exception as e:   # noqa: BLE001 — 응답이 이미 나간 백그라운드 작업이라 여기서 멈춘다
            log.warning("포춘 미리 만들기 실패 user=%s session=%s: %s", user_id, session_id, e)

    def fortune_token(self, user_id: str, session_id: str) -> tuple[LiveToken, MorningSession]:
        """포춘 단계에서 호출. 저장된 문장을 AI가 그대로 읽는 Live 토큰 (재연결도 같은 문장)."""
        s = self.prepare_fortune(user_id, session_id)        # 다른 사용자의 세션이면 여기서 404
        persona = self._persona(user_id)
        token = self.ai.issue_fortune_token(persona, Fortune(s.fortune_text, s.fortune_style, s.fortune_source))
        return token, s

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
            plan = self.ai.compose_content_steps(s.content_type, s.content_option, duration, s.session_date)
            plans = content_steps(plan.steps)
        steps = self.r.sessions.create_steps(user_id, session_id, plans)
        changes = {"planned_total_sec": planned_total_sec(plans)}
        if s.morning_call_type == "CONTENT" and plan.option and plan.option != s.content_option:
            changes["content_option"] = plan.option   # 오늘 실제로 한 세부 (세부 없는 퀴즈 알람이면 AI가 정한 값). 재연결·앱 표시용
        self.r.sessions.update_session(user_id, session_id, changes)
        return steps

    def _routine_items(self, user_id: str, s: MorningSession):
        """그날 진행할 항목 시간: ① 어젯밤 대화로 이 알람의 내일만 바꾼 값(알람 설정 위에 덮어씀) ② 알람 설정
        ③ 알람이 지워졌거나 그사이 프리셋이 바뀌었으면 세션 프리셋의 기본 시간."""
        alarm = self.r.alarms.get_alarm(user_id, s.alarm_id) if s.alarm_id else None
        if alarm and alarm.template_id == s.template_id:
            night = self.r.nights.get_night_for_morning(user_id, s.session_date)
            if night and night.override_alarm_id == s.alarm_id:
                items = self.r.nights.get_night_routine(user_id, night.night_record_id)
                if items:
                    return items
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
        s = self._session(user_id, session_id)            # 단계를 만들며 정해진 세부(content_option)를 다시 읽는다
        ctx = CoachingContext(self._persona(user_id), s.morning_call_type, tuple(self._coach_steps(s, pending)),
                              resumed=len(pending) < len(steps), night=night_view(night), strategy_block=coaching_block,
                              content_type=s.content_type or "", content_option=s.content_option or "")
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

    def change_step_duration(self, user_id: str, session_id: str, step_order: int, duration_sec: int) -> SessionView:
        """오늘 진행 중 변경(코칭 중 "오늘 스트레칭 좀 적게") — Live 함수를 앱이 전달. 알람 설정·내일 값은 그대로.
        0초 = 오늘만 빼기(SKIPPED). 이미 연결된 Live 지시문은 바뀌지 않으므로 다시 연결할 때부터 새 시간이 들어간다."""
        steps = self.r.sessions.list_steps(user_id, session_id)
        step = self._step(user_id, session_id, step_order)
        check_today_duration(steps, step, duration_sec, self.r.presets.get_app_config().routine_max_sec)
        if duration_sec == 0:
            self.r.sessions.save_step(user_id, mark(step, "SKIPPED", to_kst(self.clock())))
        else:
            self.r.sessions.update_step_duration(user_id, session_id, step_order, duration_sec)
        self.r.sessions.update_session(user_id, session_id, {"planned_total_sec": today_total(
            self.r.sessions.list_steps(user_id, session_id))})
        return self.view(user_id, session_id)
