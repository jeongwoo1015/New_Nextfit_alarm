"""기상 알람 시작 볼륨 개인화 (11/20 회의 2·3, AI CONTRACTS B23, DB 11-22 RS_WAKE_LOG).

흐름 (그날 가장 먼저 울리는 모닝콜 하나로 하루 1행을 기록·학습한다)
1. 밤 대화 토큰: 내일 시작 단계를 정한다(올리는 날이면 AI가 알리도록 밤 입력에 넣는다).
2. 밤 대화 저장: AI가 올린다고 실제로 말했는지 보고 확정한다(안 알렸으면 직전 단계 유지, 대화로 바꾼 값은 따른다).
3. GET /wake-volume/next: 앱이 미리 받아 둔다. 밤 대화가 없던 날은 이때 정한다(알리지 못했으니 올리지 않는다).
4. 아침: 앱이 시작 단계와 첫 반응 단계를 보고하면 바로 한 번 학습한다(11/26 AI B28, 미루기·재기상·확인 답변은 볼륨 학습에서 뺀다).

같은 날 다시 정할 때는 같은 입력(prefs에 남긴 수면 시간·중요한 날)과 같은 seed로 AI를 불러 밤에 알린 추천값이 바뀌지 않게 한다.
개인화가 실패해도 알람·대화는 이어진다(로그만 남긴다).
"""
import logging
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from backend.clients.ai_gateway import AIGateway, SettingsView, WakeInputs, settings_view
from backend.core.errors import Conflict, NotFound
from backend.core.timeutil import apply_date, now_kst, record_date, to_kst
from backend.domain import wake_rules
from backend.domain.alarm_rules import pick_override_alarm, ring_at
from backend.domain.models import Alarm, MorningSession, UserSetting
from backend.repositories.registry import Repositories

log = logging.getLogger(__name__)
GLOBAL_KEY = "wake:global"
CONTEXT = "all"           # 11/26 AI B28: 수면 상황을 나누지 않아 학습 칸은 하나 (ai.personalization.wake_volume.CONTEXT)


def user_key(user_id: str) -> str:
    return f"wake:user:{user_id}"


@dataclass(frozen=True)
class WakeNext:
    session_date: date
    alarm: Alarm
    row: dict

    @property
    def start_level(self) -> int:
        return self.row.get("start_level") or self.row["applied_level"]


class WakeService:
    def __init__(self, repos: Repositories, ai: AIGateway, clock=now_kst):
        self.r, self.ai, self.clock = repos, ai, clock

    # ---------- 결정 ----------
    def _setting(self, user_id: str) -> UserSetting:
        st = self.r.users.get_setting(user_id)
        if not st:
            raise NotFound("온보딩을 먼저 마쳐 주세요", "SETTING_NOT_FOUND")
        return st

    def _decide(self, user_id: str, d: date, alarm: Alarm, notified: bool | None = None, record_announced: bool | None = None,
                events=()) -> tuple[dict, bool]:
        """d 아침의 시작 단계를 정해 저장한다. 이미 울려 결과가 있으면 그대로 둔다.
        notified None = 밤 대화 저장 때 확인한 값(없으면 알리지 못한 것). record_announced를 주면 그 값을 남긴다."""
        row = self.r.personalization.get_wake_log(user_id, d)
        if row and row.get("start_level") is not None:
            return row, False
        prefs = (row or {}).get("prefs") or {}
        known = bool(row and row.get("applied_level") is not None and "sleep_minutes" in prefs)
        announced = record_announced if record_announced is not None else bool(prefs.get("announced"))
        st = self._setting(user_id)
        past = self.r.personalization.list_wake_logs(user_id, d - timedelta(days=wake_rules.LOOKBACK_DAYS), d - timedelta(days=1))
        w = WakeInputs(prefs["sleep_minutes"] if known else wake_rules.sleep_minutes(self.clock(), alarm, d),
                       prefs["important_day"] if known else wake_rules.is_important_day(events),
                       st.wake_max_start_level, st.wake_next_level, wake_rules.previous_level(past, d),
                       announced if notified is None else notified)
        dec = self.ai.decide_wake_level(user_id, d, self.r.personalization.get_bandit_state(user_key(user_id)),
                                        self.r.personalization.get_bandit_state(GLOBAL_KEY), w, f"{user_id}|{d.isoformat()}")
        values = {**dec.row, "prefs": {**(dec.row.get("prefs") or {}), "sleep_minutes": w.sleep_minutes,
                                       "important_day": w.important_day, "announced": announced}}
        return self.r.personalization.save_wake_decision(user_id, d, values), dec.raise_pending

    def _tonight_target(self, user_id: str, at: datetime) -> tuple[date, Alarm] | None:
        """밤 대화가 적용되는 아침(APPLY_DATE)의 첫 모닝콜 — 하루 한정 변경을 적용하는 알람과 같다."""
        alarm = pick_override_alarm(self.r.alarms.list_alarms(user_id), at)
        return (apply_date(record_date(at)), alarm) if alarm else None

    def night_settings(self, user_id: str, st: UserSetting, events_tomorrow=()) -> SettingsView:
        """① 밤 대화 토큰: 내일 시작 단계를 정하고, 올리는 날이면 AI가 알리도록 이전 단계를 넣는다."""
        try:
            target = self._tonight_target(user_id, to_kst(self.clock()))
            if not target:
                return settings_view(st)
            row, pending = self._decide(user_id, *target, notified=True, events=events_tomorrow)
            return settings_view(st, row, row.get("previous_level") if pending else None)
        except Exception:
            log.exception("기상 볼륨 결정 실패(밤 토큰) user=%s", user_id)
            return settings_view(st)

    def after_night_talk(self, user_id: str, transcript: list[dict]) -> None:
        """② 밤 대화 저장: 올리는 날인데 AI가 실제로 알리지 않았으면 직전 단계를 유지한다. 대화로 바꾼 값(update_setting)도 여기서 반영된다."""
        try:
            target = self._tonight_target(user_id, to_kst(self.clock()))
            if not target:
                return
            d, alarm = target
            row = self.r.personalization.get_wake_log(user_id, d)
            if not row or row.get("applied_level") is None:
                row, _ = self._decide(user_id, d, alarm, notified=True)
            ai_text = " ".join(str(t.get("text") or "") for t in transcript if t.get("role") != "user")
            announced = wake_rules.raise_pending(row) and self.ai.wake_raise_announced(ai_text, row["applied_level"])
            self._decide(user_id, d, alarm, notified=announced, record_announced=announced)
        except Exception:
            log.exception("기상 볼륨 확정 실패(밤 대화 저장) user=%s", user_id)

    def next(self, user_id: str) -> WakeNext:
        """③ 앱이 미리 받아 두는 다음 아침의 시작 단계. 그날 첫 알람이 이미 울렸으면 정해 둔 값을 그대로 준다."""
        now = to_kst(self.clock())
        found = wake_rules.next_morning(self.r.alarms.list_alarms(user_id), now)
        if not found:
            raise NotFound("다음에 울릴 모닝콜이 없어요", "NO_UPCOMING_ALARM")
        d, first = found
        row = self.r.personalization.get_wake_log(user_id, d)
        if not (row and row.get("applied_level") is not None and ring_at(first, d) <= now):
            row, _ = self._decide(user_id, d, first)
        return WakeNext(d, first, row)

    def today_settings(self, st: UserSetting, s: MorningSession) -> SettingsView:
        """브리핑 입력: 오늘 시작 단계와 상태."""
        try:
            return settings_view(st, self.r.personalization.get_wake_log(s.user_id, s.session_date))
        except Exception:
            log.exception("기상 볼륨 기록 읽기 실패 user=%s", s.user_id)
            return settings_view(st)

    # ---------- 아침 ----------
    def on_session_start(self, user_id: str, s: MorningSession) -> None:
        """알람이 울렸으면 '다음 알람 한 번' 값은 쓴 것이므로 비운다(RS_USER_SETTING.WAKE_NEXT_LEVEL '쓰고 나면 비움')."""
        try:
            st = self.r.users.get_setting(user_id)
            row = self.r.personalization.get_wake_log(user_id, s.session_date)
            if st and st.wake_next_level and row and row.get("source") == "user_today" and row.get("applied_level") == st.wake_next_level:
                self.r.users.save_setting(user_id, {"wake_next_level": None})
        except Exception:
            log.exception("다음 알람 볼륨 비우기 실패 user=%s", user_id)

    def report(self, user_id: str, s: MorningSession, start_level: int, received_level: int | None) -> dict:
        """④ 앱 보고: 시작 단계와 첫 반응 단계(받기·미루기·끄기 버튼을 누른 순간의 단계, 반응 없이 꺼졌으면 None).
        그날 처음 보고한 세션만 기록하고(다시 울림·다른 알람은 그대로) 바로 학습한다. 11/26 결정(AI B28): 볼륨은 첫 반응 단계만
        보므로 확인 질문·미루기·재기상을 기다릴 필요가 없다. 자동 연결로 시작한 세션은 사용자의 반응이 아니라서 기록하지 않는다."""
        row = self.r.personalization.get_wake_log(user_id, s.session_date)
        if row and row.get("start_level") is not None:
            return row
        if s.auto_connected:
            raise Conflict("자동 연결로 시작한 모닝콜은 기상 볼륨을 기록하지 않아요", "AUTO_CONNECTED")
        row = self.r.personalization.save_wake_result(user_id, s.session_date, start_level, received_level, None, s.session_id)
        self.learn(user_id, s)
        return self.r.personalization.get_wake_log(user_id, s.session_date) or row

    def learn(self, user_id: str, s: MorningSession) -> bool:
        """그날 기록을 한 번만 학습한다(첫 반응 단계, AI B28). 내 기록과 전체 기록을 함께 갱신한다. 자동 연결 세션은 학습하지 않는다."""
        try:
            row = self.r.personalization.get_wake_log(user_id, s.session_date)
            if (s.auto_connected or not row or row.get("start_level") is None or row.get("session_id") != s.session_id
                    or row.get("learned_at")):
                return False
            if not self.r.personalization.mark_wake_learned(user_id, s.session_date):
                return False
            for key, owner in ((user_key(user_id), user_id), (GLOBAL_KEY, None)):   # 행에 남은 옛 상황 칸(short/ok)과 상관없이 하나의 칸
                state = self.ai.learn_wake(self.r.personalization.get_bandit_state(key), CONTEXT, row["start_level"],
                                           row.get("received_level"), None)
                if state is not None:
                    self.r.personalization.save_bandit_state(key, state, owner)
            return True
        except Exception:
            log.exception("기상 볼륨 학습 실패 user=%s session=%s", user_id, s.session_id)
            return False

    def finish(self, user_id: str, s: MorningSession) -> None:
        """확인 질문을 마쳤거나 세션이 끝났을 때: 학습이 빠졌으면 하고, RS_WAKE_LOG.CONFIRMED(확인 응답·재기상 없음)를 기록한다.
        CONFIRMED는 기록용이라 볼륨 학습에 다시 반영하지 않는다(AI B28). 실패해도 아침 흐름은 이어진다."""
        try:
            self.learn(user_id, s)
            row = self.r.personalization.get_wake_log(user_id, s.session_date)
            if not row or row.get("session_id") != s.session_id or row.get("start_level") is None:
                return
            ok = wake_rules.confirmed(s)
            if row.get("confirmed") is not ok:
                self.r.personalization.save_wake_result(user_id, s.session_date, row["start_level"], row.get("received_level"), ok,
                                                        s.session_id)
        except Exception:
            log.exception("기상 볼륨 확인 기록 실패 user=%s session=%s", user_id, s.session_id)
