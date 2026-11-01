"""DB 접근 인터페이스. Supabase 구현은 backend-db 담당이 같은 메서드로 만든다 (정의서 11/01 수정판 기준).

규칙
- 테이블 이름·컬럼은 정의서(소문자). 반환은 `backend.domain.models`의 데이터 클래스.
- 없으면 None(get) 또는 빈 목록·빈 dict(list). 예외는 DB 오류일 때만.
- PK가 gen_random_uuid()인 테이블은 create가 id를 채운 객체를 돌려준다(인자로 받은 id는 무시해도 된다).
- created_at·updated_at은 DB 기본값/트리거가 채운다. 반환 객체에 그 값을 담아 준다.
- 사용자 확인(USER_ID 비교)과 규칙 검사는 서비스가 먼저 한다. Repository는 service_role로 그대로 읽고 쓴다.
"""
from typing import Protocol

from backend.domain.models import Alarm, AppConfig, RoutineTemplate, TemplateItem, User, UserSetting


class UserRepository(Protocol):
    """rs_user"""
    def get(self, user_id: str) -> User | None: ...
    def create(self, user: User) -> User: ...
    def update(self, user: User) -> User: ...


class UserSettingRepository(Protocol):
    """rs_user_setting (PK = user_id)"""
    def get(self, user_id: str) -> UserSetting | None: ...
    def upsert(self, setting: UserSetting) -> UserSetting: ...


class AppConfigRepository(Protocol):
    """rs_app_config (1행). 행이 없으면 AppConfig() 기본값."""
    def get(self) -> AppConfig: ...


class AlarmRepository(Protocol):
    """rs_alarm"""
    def list_by_user(self, user_id: str) -> list[Alarm]: ...      # alarm_time 오름차순
    def get(self, alarm_id: str) -> Alarm | None: ...
    def create(self, alarm: Alarm) -> Alarm: ...
    def update(self, alarm: Alarm) -> Alarm: ...                   # alarm_id 기준 전체 컬럼 갱신 (template_id가 바뀌면 DB 트리거가 항목 설정을 지움)
    def delete(self, alarm_id: str) -> None: ...                    # 세션 alarm_id는 SET NULL, 밤 기록 override 값은 트리거가 비움


class RoutineTemplateRepository(Protocol):
    """rs_routine_template + rs_routine_template_item (공용, 읽기 전용)"""
    def list_templates(self, active_only: bool = True) -> list[RoutineTemplate]: ...
    def get_template(self, template_id: str) -> RoutineTemplate | None: ...
    def list_items(self, template_id: str) -> list[TemplateItem]: ...   # item_order 오름차순


class AlarmRoutineItemRepository(Protocol):
    """rs_alarm_routine_item — 알람별로 기본 시간과 다르게 바꾼 항목만 저장 (duration_sec 0 = 빼기)."""
    def get_overrides(self, alarm_id: str) -> dict[str, int]: ...      # {template_item_id: duration_sec}

    def replace(self, alarm_id: str, user_id: str, overrides: dict[str, int]) -> None:
        """그 알람의 행을 정확히 overrides로 맞춘다(없는 키의 행은 삭제). 서비스가 규칙 검사를 마친 값만 넘긴다.

        DB 트리거가 행마다 합계 상한·최소 1개를 검사하므로 한 트랜잭션(DB 함수/RPC)으로 처리하는 것을 권장한다.
        RPC 전에는 `domain.routine_rules.safe_write_order(items, 현재, overrides)` 순서로 한 행씩 쓰면 대부분 통과한다.
        """
        ...
