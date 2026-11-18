"""Supabase 저장소의 '한 번 더 보내기' 규칙. 실제 DB 없이 가짜 쿼리로 확인한다."""
from types import SimpleNamespace

import httpx
import pytest

from backend.core.errors import ServiceUnavailable
from backend.repositories.supabase import _Base


class FakeQuery:
    def __init__(self, method, errors):
        self.request = SimpleNamespace(http_method=method)
        self.errors = list(errors)
        self.calls = 0

    def execute(self):
        self.calls += 1
        if self.errors:
            raise self.errors.pop(0)
        return SimpleNamespace(data=[{"ok": 1}])


def run(query):
    return _Base(client=None)._run(query)


def test_read_is_resent_once():
    q = FakeQuery("GET", [httpx.ReadError("끊김")])
    assert run(q) == [{"ok": 1}] and q.calls == 2


def test_write_is_resent_only_when_not_delivered():
    q = FakeQuery("POST", [httpx.RemoteProtocolError("Server disconnected")])
    assert run(q) == [{"ok": 1}] and q.calls == 2
    q = FakeQuery("PATCH", [httpx.ConnectError("연결 실패")])
    assert run(q) == [{"ok": 1}] and q.calls == 2


def test_write_is_not_resent_when_it_may_have_been_saved():
    q = FakeQuery("POST", [httpx.ReadTimeout("응답 없음")])                       # 이미 저장됐을 수 있음
    with pytest.raises(ServiceUnavailable):
        run(q)
    assert q.calls == 1


def test_only_one_resend():
    q = FakeQuery("GET", [httpx.ReadError("끊김"), httpx.ReadError("또 끊김")])
    with pytest.raises(ServiceUnavailable):
        run(q)
    assert q.calls == 2
