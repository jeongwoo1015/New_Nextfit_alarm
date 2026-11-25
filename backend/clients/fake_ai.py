"""테스트·개발용 AI 어댑터. 정해 둔 값을 돌려주고 받은 입력을 기록한다."""
from dataclasses import dataclass, field

import re

from .ai_gateway import (ContentPlan, ContentStep, Fortune, LiveToken, NightContext, NightExtract, RoutineChangeRequest,
                         StrategyDecision, StrategyLearning, WakeDecision, WakeInputs, default_wake_decision,
                         fit_seconds)

STRETCH_AREAS = ("FULL", "NECK", "SHOULDER", "CHEST_BACK", "WRIST_HAND", "ELBOW_ARM", "LOW_BACK", "PELVIS", "GLUTE",
                 "THIGH", "CALF", "ANKLE_FOOT")
QUIZ = [ContentStep(f"퀴즈 {n}번", 120, "SPEECH", f"문제: 문제 {n}\n정답: 정답 {n}") for n in range(1, 4)]


def _token(kind: str) -> LiveToken:
    return LiveToken(f"tok-{kind}", "wss://example/live", "models/fake-live", f"{kind} 시작 신호", "2026-11-01T15:30:00Z")


@dataclass
class FakeAIGateway:
    extract: NightExtract = field(default_factory=lambda: NightExtract("DONE", {"ai_summary": "요약", "tomorrow_notes": []}))
    routine: RoutineChangeRequest = field(default_factory=RoutineChangeRequest)
    content: list[ContentStep] = field(default_factory=lambda: list(QUIZ))
    strategy: dict = field(default_factory=lambda: {"coaching_intensity": "soft", "briefing_length": "normal",
                                                    "encouragement": "high", "strategy_id": "gentle"})
    content_option: str = "PERSON"              # 세부를 고르지 않은 알람에 AI가 정해 주는 세부 흉내
    fortune_error: Exception | None = None      # 정하면 compose_fortune이 이 오류를 던진다 (AI 사용 불가 흉내)
    intensity: int | None = None                # 정하면 자동 강도가 켜진 사용자에게 today_intensity가 이 값을 준다
    calls: list = field(default_factory=list)
    decide_states: dict = field(default_factory=dict)       # 사용자별 decide_strategy가 받은 학습 상태
    content_extra: tuple = ()                   # 마지막 compose_content_steps의 (english_level, context)
    fortune_plan: tuple = ()                    # 마지막 포춘 토큰의 순서표
    wake_recommended: int = 1                   # decide_wake_level이 추천하는 시작 단계
    wordchain: dict = field(default_factory=lambda: {"valid": True, "next": "정원"})   # judge_wordchain 결과

    def issue_night_token(self, ctx: NightContext) -> LiveToken:
        self.calls.append(("night_token", ctx))
        return _token("night")

    def extract_night_record(self, transcript):
        self.calls.append(("extract", transcript))
        return self.extract

    def parse_routine_changes(self, transcript, items):
        self.calls.append(("routine", [i.template_item_id for i in items]))
        return self.routine

    def issue_briefing_token(self, ctx):
        self.calls.append(("briefing_token", ctx))
        return _token("briefing")

    def issue_coaching_token(self, ctx):
        self.calls.append(("coaching_token", ctx))
        return _token("coaching")

    def compose_content_steps(self, content_type, content_option, duration_sec, today, english_level="", context=""):
        self.calls.append(("content", (content_type, content_option, duration_sec)))
        self.content_extra = (english_level, context)
        secs = fit_seconds([s.planned_sec for s in self.content], duration_sec)
        return ContentPlan([ContentStep(s.name, sec, s.response_type, s.script) for s, sec in zip(self.content, secs)],
                           content_option or self.content_option)

    def decide_strategy(self, user_id, records, today, condition, speech_style, mode, bandit_state):
        self.calls.append(("decide", (user_id, today, condition, speech_style, mode, len(records.sessions))))
        self.decide_states[user_id] = bandit_state                    # 사용자마다 받은 학습 상태 (B27)
        b, c = self.strategy_blocks(self.strategy)
        row = {"user_id": user_id, "session_date": today.isoformat(), "source": "rule", "mode": mode,
               "strategy_id": self.strategy["strategy_id"], "strategy": self.strategy}
        return StrategyDecision(self.strategy, b, c, row)

    def strategy_blocks(self, strategy):
        sid = (strategy or {}).get("strategy_id", "default")
        return (f"[브리핑 전략 {sid}]", f"[코칭 전략 {sid}]") if sid != "default" else ("", "")

    def compose_fortune(self, one_liner, tone, today, diary_text):
        self.calls.append(("fortune", (one_liner, tone, today, diary_text)))
        if self.fortune_error:
            raise self.fortune_error
        if not (one_liner or diary_text):
            return Fortune("오늘은 작은 시작이 큰 하루를 만들어요.", "COOKIE", "fallback")
        return Fortune(f"포춘: {one_liner or diary_text}", "HOROSCOPE" if today.toordinal() % 2 else "COOKIE", "ai")

    def issue_fortune_token(self, persona, fortune, based_on="", plan=()):
        self.calls.append(("fortune_token", (persona, fortune, based_on)))
        self.fortune_plan = plan
        return _token("fortune")

    def stretch_routines(self, duration_sec):
        """AI stretch_sets처럼 부위 12개, 부위마다 4단계 (이름에 부위를 붙여 구분)."""
        secs = fit_seconds([45, 45, 45, 45], duration_sec)
        return {area: [ContentStep(f"{area} 동작 {n}", sec, "PLAY", f"{area} 안내 {n}") for n, sec in enumerate(secs, 1)]
                for area in STRETCH_AREAS}

    def morning_plan(self, has_one_liner, coaching):
        return tuple(s for s in ("BRIEFING", "ONE_LINER", "COACHING", "FORTUNE")
                     if (s != "ONE_LINER" or has_one_liner) and (s != "COACHING" or coaching))

    def today_intensity(self, base, sessions, auto):
        self.calls.append(("intensity", (base, len(sessions), auto)))
        return self.intensity if (self.intensity is not None and auto) else base

    def decide_wake_level(self, user_id, session_date, personal, global_, w: WakeInputs, seed):
        """AI decide_start_level의 사용자 우선·상승 보류 규칙만 흉내 낸다 (추천은 wake_recommended 고정)."""
        self.calls.append(("wake_decide", (session_date, w, seed)))
        rec = self.wake_recommended
        row = default_wake_decision(user_id, session_date, w).row
        source, applied = ("important_day" if w.important_day else "bandit"), rec
        if w.next_level:
            applied, source = w.next_level, "user_today"
        elif w.max_start_level and rec > w.max_start_level:
            applied, source = w.max_start_level, "user_max"
        pending = bool(w.previous_level and applied > w.previous_level and source in ("bandit", "important_day"))
        if pending and not w.notified:
            applied, source, pending = w.previous_level, "hold_no_notice", False
        row.update(context_key="short" if (w.sleep_minutes or 0) < 360 else "ok", recommended_level=rec, applied_level=applied,
                   source=source, state="USER" if source.startswith("user") else "RECOMMENDED",
                   propensity=0.5 if source == "bandit" else None, reason=f"가짜 결정 {source}")
        return WakeDecision(row, pending)

    def wake_raise_announced(self, ai_text, level):
        return bool(re.search(rf"{level}\s*단계", ai_text or "") and re.search("소리|볼륨|알람", ai_text or ""))

    def learn_strategy(self, bandit_state, log, session, steps):
        """AI coaching_reward처럼 단계 수행 비율, Bandit이 고른 날만 상태를 바꾼다."""
        self.calls.append(("strategy_learn", (log.log_id, session.session_id)))
        if session.coaching_skipped or not steps:
            return StrategyLearning(None, None)
        reward = sum(s.step_status == "DONE" for s in steps) / len(steps)
        if log.source != "bandit":
            return StrategyLearning(reward, None)
        return StrategyLearning(reward, {"learned": [*(bandit_state or {}).get("learned", []), [log.strategy_id, reward]]})

    def learn_wake(self, state, context_key, start_level, received_level, confirmed):
        self.calls.append(("wake_learn", (context_key, start_level, received_level, confirmed)))
        if start_level not in range(1, 6) or (received_level is not None and received_level not in range(start_level, 6)):
            return None
        return {"days": [*(state or {}).get("days", []), [context_key, start_level, received_level, confirmed]]}

    def judge_wordchain(self, words, word):
        self.calls.append(("wordchain", (list(words), word)))
        return dict(self.wordchain)

    def correct_wake(self, state, context_key, received_level):
        self.calls.append(("wake_correct", (context_key, received_level)))
        days = [list(d) for d in (state or {}).get("days", [])]
        hit = next((d for d in reversed(days) if d[0] == context_key and d[2] == received_level and d[3] is True), None)
        if hit is None:
            return None
        hit[3] = False
        return {**(state or {}), "days": days}
