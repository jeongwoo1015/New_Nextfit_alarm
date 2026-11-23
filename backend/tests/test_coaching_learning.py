"""코칭 강도 학습 (AI 11/21: 보상 = 단계 수행 비율, 기상 성공은 기상 볼륨의 몫).

브리핑 때 정한 전략 로그(그날 1개)에 세션이 끝나면 보상을 매기고, Bandit이 고른 날이면 학습 상태('global')를 갱신한다.
끝내지 못한 지난 세션은 다음 알람의 세션 시작 뒤 백그라운드에서 매긴다. 한 번 매긴 날은 다시 하지 않는다.
"""
from dataclasses import replace
from datetime import datetime

from backend.core.timeutil import KST

from .conftest import USER_A, auth
from .test_api_morning import FRIDAY_7, morning, start, url  # noqa: F401  (morning은 fixture)

H = auth(USER_A)


def run_coaching(client, v, verdicts):
    client.post(url(v, "/briefing-token"), json={}, headers=H)                    # 전략 로그 저장
    client.post(url(v, "/coaching-token"), headers=H)                             # 단계 만들기
    for n, verdict in enumerate(verdicts, 1):
        client.post(url(v, f"/steps/{n}/verdict"), json={"verdict": verdict}, headers=H)


def strategy_log(repos):
    return repos.personalization.get_strategy_log(USER_A, FRIDAY_7.date())


def test_end_sets_reward_once_rule_day_does_not_train(client, morning, ai, repos):
    v = start(client, morning).json()
    run_coaching(client, v, ["done", "done", "not_done"])
    client.post(url(v, "/end"), headers=H)
    steps = len(client.get(url(v), headers=H).json()["steps"])
    assert strategy_log(repos).reward == 2 / steps                                # 수행 비율 (판정 안 한 단계는 못 한 것)
    assert repos.personalization.get_bandit_state("global") is None                # Rule이 고른 날은 학습 안 함
    client.post(url(v, "/end"), headers=H)
    assert [c[0] for c in ai.calls].count("strategy_learn") == 1                   # 다시 끝내도 한 번만


def test_bandit_day_updates_global_state(client, morning, ai, repos):
    v = start(client, morning).json()
    run_coaching(client, v, ["done"])
    lg = strategy_log(repos)
    repos.personalization.logs[lg.log_id] = replace(lg, source="bandit")
    client.post(url(v, "/end"), headers=H)
    state = repos.personalization.get_bandit_state("global")
    assert state["learned"][0][0] == "gentle" and 0 < state["learned"][0][1] < 1


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
