"""코칭 강도 학습 (AI 11/21: 보상 = 단계 수행 비율, 기상 성공은 기상 볼륨의 몫).

브리핑 때 정한 전략 로그(그날 1개)에 세션이 끝나면 보상을 매기고, Bandit이 고른 날이면 사용자별 학습 상태('user:{id}', AI B27)를 갱신한다.
끝내지 못한 지난 세션은 다음 알람의 세션 시작 뒤 백그라운드에서 매긴다. 한 번 매긴 날은 다시 하지 않는다.
"""
from dataclasses import replace
from datetime import date, datetime

from backend.core.timeutil import KST

from .conftest import USER_A, USER_B, auth
from .test_api_morning import FRIDAY_7, morning, start, url  # noqa: F401  (morning은 fixture)
from .test_api_users import onboard

H = auth(USER_A)
KEY_A, KEY_B = f"user:{USER_A}", f"user:{USER_B}"


def run_coaching(client, v, verdicts, user=USER_A):
    h = auth(user)
    client.post(url(v, "/briefing-token"), json={}, headers=h)                    # 전략 로그 저장
    client.post(url(v, "/coaching-token"), headers=h)                             # 단계 만들기
    for n, verdict in enumerate(verdicts, 1):
        client.post(url(v, f"/steps/{n}/verdict"), json={"verdict": verdict}, headers=h)


def strategy_log(repos, user=USER_A, day=None):
    return repos.personalization.get_strategy_log(user, day or FRIDAY_7.date())


def bandit_day(client, repos, alarm, verdicts, user=USER_A):
    """Bandit이 고른 날로 코칭을 하고 세션을 끝낸다 (가짜 AI는 rule로 기록하므로 로그만 bandit으로 바꾼다)."""
    v = start(client, alarm, user).json()
    run_coaching(client, v, verdicts, user)
    lg = strategy_log(repos, user, date.fromisoformat(v["session"]["session_date"]))
    repos.personalization.logs[lg.log_id] = replace(lg, source="bandit")
    client.post(url(v, "/end"), headers=auth(user))
    return v


def first_alarm(client, user):
    return client.get("/alarms", headers=auth(user)).json()[0]


def test_end_sets_reward_once_rule_day_does_not_train(client, morning, ai, repos):
    v = start(client, morning).json()
    run_coaching(client, v, ["done", "done", "not_done"])
    client.post(url(v, "/end"), headers=H)
    steps = len(client.get(url(v), headers=H).json()["steps"])
    assert strategy_log(repos).reward == 2 / steps                                # 수행 비율 (판정 안 한 단계는 못 한 것)
    assert repos.personalization.get_bandit_state(KEY_A) is None                   # Rule이 고른 날은 학습 안 함
    client.post(url(v, "/end"), headers=H)
    assert [c[0] for c in ai.calls].count("strategy_learn") == 1                   # 다시 끝내도 한 번만


def test_bandit_day_updates_user_state_not_global(client, morning, ai, repos):
    bandit_day(client, repos, morning, ["done"])
    state = repos.personalization.get_bandit_state(KEY_A)
    assert state["learned"][0][0] == "gentle" and 0 < state["learned"][0][1] < 1
    assert repos.personalization.get_bandit_state("global") is None                # B27: 합산 상태는 쓰지 않는다
    assert repos.personalization.get_bandit_state(f"wake:user:{USER_A}") != state  # 기상 볼륨과 섞지 않는다


def test_users_learn_separately(client, morning, ai, repos, clock):
    """B27 확인 1: A와 B가 각자 상태를 저장하고, A의 결과가 B의 선택에 쓰이지 않으며, 새 사용자는 빈 상태에서 시작한다."""
    onboard(client, user=USER_B)
    alarm_b = first_alarm(client, USER_B)
    bandit_day(client, repos, morning, ["done"])                                   # A만 학습
    state_a = repos.personalization.get_bandit_state(KEY_A)

    v = start(client, alarm_b, USER_B).json()
    client.post(url(v, "/briefing-token"), json={}, headers=auth(USER_B))
    assert ai.decide_states[USER_B] is None                                        # B는 A의 상태를 받지 않고 빈 상태로 시작
    client.post(url(v, "/end"), headers=auth(USER_B))
    assert repos.personalization.get_bandit_state(KEY_B) is None

    clock.now = datetime(2026, 11, 7, 7, 0, tzinfo=KST)                           # 다음 날
    bandit_day(client, repos, alarm_b, ["not_done"], USER_B)                      # B도 학습
    assert ai.decide_states[USER_B] is None                                        # 전날 B는 Rule이라 아직 상태 없음
    bandit_day(client, repos, morning, ["done"])
    assert ai.decide_states[USER_A] == state_a                                     # A는 자기 상태를 받는다
    state_b = repos.personalization.get_bandit_state(KEY_B)
    assert len(state_b["learned"]) == 1 and state_b["learned"][0][1] == 0          # B 상태에는 B 결과만
    assert len(repos.personalization.get_bandit_state(KEY_A)["learned"]) == 2
    assert repos.personalization.get_bandit_state("global") is None


def test_skipped_coaching_or_no_steps_has_no_reward(client, morning, ai, repos):
    v = start(client, morning).json()
    client.post(url(v, "/briefing-token"), json={}, headers=H)                    # 단계를 만들기 전에 끝남
    client.post(url(v, "/end"), headers=H)
    assert strategy_log(repos).reward is None


def test_unfinished_session_is_learned_at_next_alarm(client, morning, ai, repos, clock):
    v = start(client, morning).json()
    run_coaching(client, v, ["done", "done"])                                      # 끝내지 않고 앱이 꺼짐
    assert strategy_log(repos).reward is None
    clock.now = datetime(2026, 11, 7, 7, 0, tzinfo=KST)
    start(client, morning)                                                         # 다음 날 알람 → 백그라운드에서 지난 날 학습
    assert strategy_log(repos).reward is not None


def test_learning_failure_does_not_break_end(client, morning, ai, repos):
    v = start(client, morning).json()
    run_coaching(client, v, ["done"])

    def boom(*_):
        raise RuntimeError("AI 오류")
    ai.learn_strategy = boom
    assert client.post(url(v, "/end"), headers=H).status_code == 200
    assert strategy_log(repos).reward is None
