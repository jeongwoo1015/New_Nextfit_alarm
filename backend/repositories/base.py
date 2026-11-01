"""DB 접근 인터페이스 (정의서 11/01 수정판, backend-db 초안 `repository_functions_draft.md` 기준).

인터페이스와 메모리 구현은 backend-service, Supabase 구현(`supabase_*.py`)은 backend-db가 만든다.

규칙
- 반환은 `backend.domain.models` 데이터 클래스. 필드 이름은 DB 컬럼 소문자와 같다.
- 사용자 데이터 함수는 user_id를 받고 쿼리에 `user_id = ?` 조건을 넣는다. 다른 사용자 행이면 없는 것과 같이 None
  (서버는 404로 응답한다). service_role이라 RLS가 막아 주지 않기 때문이다.
- 없으면 None 또는 빈 목록. 예외는 DB 오류일 때만, `core.errors` 형식으로 바꿔 던진다:
  상한 초과 트리거 → ValidationFailed("ROUTINE_OVER_LIMIT"), 모두 뺌 → ValidationFailed("ROUTINE_ALL_REMOVED"),
  CHECK·그 밖의 트리거 → ValidationFailed, UNIQUE → Conflict, 연결 실패·시간 초과 → ServiceUnavailable(503).
- id(gen_random_uuid), created_at·updated_at, record_date·apply_date는 DB가 채운다. 서버는 넣지 않는다.
- 여러 행을 바꾸는 함수(알람 항목 시간)는 한 번의 요청(DB 함수 rs_replace_alarm_routine_items)으로 처리한다.
  교체 함수의 '알람이 없습니다'(P0002)는 None으로 돌려준다(서버 404).
"""
from datetime import date, datetime
from typing import Protocol

from backend.domain.models import (AiMusic, Alarm, AlarmRoutineItem, AppConfig, DiaryPhoto, MorningSession, NightRaw,
                                   NightRecord, RecentRecords, RoutineTemplate, SessionSnapshot, SessionStep, StepPlan,
                                   StrategyLog, TemplateItem, User, UserSetting)


class UserRepository(Protocol):
    """1. 사용자·설정 — rs_user, rs_user_setting"""
    def get_user(self, user_id: str) -> User | None: ...
    def create_user(self, user_id: str, user_name: str, birth_date: date, gender: str) -> User: ...
    def update_user(self, user_id: str, changes: dict) -> User | None: ...          # user_name·birth_date·gender
    def set_onboarding_done(self, user_id: str) -> None: ...
    def get_setting(self, user_id: str) -> UserSetting | None: ...
    def save_setting(self, user_id: str, values: dict) -> UserSetting: ...          # 없으면 만들고 있으면 바꾼다
    def delete_account(self, user_id: str) -> None: ...                              # auth.users 삭제 → DB 행 CASCADE. Storage 파일은 먼저 지운다


class PresetRepository(Protocol):
    """2. 공용 데이터 — rs_routine_template(_item), rs_app_config, rs_ai_music (읽기 전용)"""
    def list_templates(self, active_only: bool = True) -> list[RoutineTemplate]: ...
    def get_template(self, template_id: str) -> RoutineTemplate | None: ...
    def list_template_items(self, template_id: str) -> list[TemplateItem]: ...      # item_order 오름차순
    def get_app_config(self) -> AppConfig: ...                                      # 행이 없으면 AppConfig() 기본값
    def pick_music(self, music_type: str, genre: str | None = None, mood: str | None = None) -> AiMusic | None: ...  # 같은 조건에서 무작위 1개


class AlarmRepository(Protocol):
    """3. 알람 — rs_alarm, rs_alarm_routine_item, 함수 rs_alarm_routine_items"""
    def list_alarms(self, user_id: str) -> list[Alarm]: ...                          # alarm_time 오름차순
    def get_alarm(self, user_id: str, alarm_id: str) -> Alarm | None: ...
    def create_alarm(self, user_id: str, values: dict) -> Alarm: ...
    def update_alarm(self, user_id: str, alarm_id: str, changes: dict) -> Alarm | None: ...   # template_id가 바뀌면 DB가 항목 설정을 지움
    def delete_alarm(self, user_id: str, alarm_id: str) -> None: ...                 # 지난 세션은 남고(alarm_id NULL), 하루 한정 변경은 DB가 비움
    def get_alarm_routine(self, user_id: str, alarm_id: str) -> list[AlarmRoutineItem] | None: ...   # item_order 순. 알람이 없거나 남의 것이면 None

    def replace_alarm_routine(self, user_id: str, alarm_id: str, overrides: dict[str, int]) -> list[AlarmRoutineItem] | None:
        """항목 시간 전체 교체 — DB 함수 rs_replace_alarm_routine_items 한 번(삭제와 저장이 한 트랜잭션, 합계 검사는 커밋 때).

        overrides({template_item_id: 초})에 없는 항목과 기본 시간과 같은 값은 기본 시간(행 없음), 0초 = 빼기.
        서버가 규칙 검사를 마친 값만 넘긴다. 반환값은 바뀐 뒤의 get_alarm_routine 결과.
        """
        ...


class NightRepository(Protocol):
    """4. 밤 기록 — rs_night_record, rs_night_raw"""
    def save_night_raw(self, user_id: str, transcript: list, started_at: datetime | None,
                       ended_at: datetime | None) -> str: ...                        # rs_night_record(PENDING) + rs_night_raw. 같은 밤이면 덮어씀. night_record_id
    def save_night_extract(self, user_id: str, night_record_id: str, fields: dict,
                           override_alarm_id: str | None) -> NightRecord | None: ...  # 11/01 컬럼 이름의 추출 결과, extract_status = DONE
    def mark_extract_failed(self, user_id: str, night_record_id: str) -> None: ...
    def get_night(self, user_id: str, night_record_id: str) -> NightRecord | None: ...   # 초안에 추가: 저장 직후 응답, 목소리 업로드
    def get_night_routine(self, user_id: str, night_record_id: str) -> list[AlarmRoutineItem] | None: ...
    # 내일 진행할 항목 = 대상 알람(override_alarm_id) 설정 + 밤 대화로 내일만 바꾼 시간(rs_night_routine_items).
    # 남의 기록이면 None, 대상 알람이 없거나 콘텐츠 알람이면 [].

    def replace_night_routine(self, user_id: str, night_record_id: str, overrides: dict[str, int]) -> list[AlarmRoutineItem] | None:
        """내일만 바꿀 항목 시간 전체 교체 — DB 함수 rs_replace_night_routine_items (0초 = 내일만 빼기).
        알람 설정과 같은 값과 넘기지 않은 항목은 알람 설정대로(행 없음). 대상 알람이 바뀌면 DB가 지운다. 남의 기록이면 None."""
        ...

    def get_night_raw(self, night_record_id: str) -> NightRaw | None: ...            # 재추출 배치용 (서버 내부)
    def list_failed_extracts(self, limit: int) -> list[tuple[str, str]]: ...         # [(user_id, night_record_id)]
    def get_night_for_morning(self, user_id: str, apply_date: date) -> NightRecord | None: ...
    def get_night_by_date(self, user_id: str, record_date: date) -> NightRecord | None: ...
    def set_voice_path(self, user_id: str, night_record_id: str) -> str: ...         # {user_id}/{record_date}.m4a
    def purge_expired_raw(self, now: datetime) -> list[str]: ...                     # 비운 원본 음성 경로 (파일은 Storage에서 먼저 지움)


class SessionRepository(Protocol):
    """5. 모닝 세션·단계 — rs_morning_session, rs_session_step"""
    def get_or_create_session(self, snapshot: SessionSnapshot) -> MorningSession: ...   # 같은 알람·날짜가 있으면 그것(재연결)
    def get_session(self, user_id: str, session_id: str) -> MorningSession | None: ...
    def update_session(self, user_id: str, session_id: str, changes: dict) -> MorningSession | None: ...  # alarm_rang_at, first_response_at, planned_total_sec, wake_checked_at, wake_success, session_end_at
    def add_rewake(self, user_id: str, session_id: str) -> int: ...                  # rewake_count + 1 (원자적으로), 바뀐 횟수
    def add_snooze(self, user_id: str, session_id: str) -> int: ...                  # snooze_count + 1 (원자적으로), 바뀐 횟수
    def create_steps(self, user_id: str, session_id: str, plans: list[StepPlan]) -> list[SessionStep]: ...  # 이미 있으면 만들지 않고 기존 단계
    def list_steps(self, user_id: str, session_id: str) -> list[SessionStep]: ...    # step_order 순
    def save_step(self, user_id: str, step: SessionStep) -> SessionStep: ...         # step_status·checked_at·ai_verdict
    def update_step_duration(self, user_id: str, session_id: str, step_order: int,
                             duration_sec: int) -> SessionStep | None: ...          # 오늘 진행 중 변경(코칭 중). 0 이하는 막고 오늘 빼기는 save_step(SKIPPED)
    def recent_records(self, user_id: str, since: date, until: date) -> RecentRecords: ...   # session_date / apply_date 기준, 양 끝 포함


class PersonalizationRepository(Protocol):
    """6. 개인화 (서버만) — rs_strategy_log, rs_bandit_state"""
    def save_strategy_log(self, user_id: str, session_id: str | None, row: dict) -> str: ...  # DecisionLog.to_row(). JSON 문자열 칸은 JSON으로. log_id
    def get_strategy_log(self, user_id: str, session_date: date) -> StrategyLog | None: ...   # 그날 가장 먼저 만든 로그
    def set_reward(self, log_id: str, reward: float) -> None: ...
    def get_bandit_state(self, state_key: str) -> dict | None: ...                   # 'global' 또는 'user:{user_id}'
    def save_bandit_state(self, state_key: str, state: dict, user_id: str | None = None) -> None: ...


class StorageClient(Protocol):
    """7. Storage — voice, night-raw, photo 버킷(비공개) + rs_diary_photo"""
    def upload_voice(self, user_id: str, record_date: date, data: bytes) -> str: ...  # 경로 {user_id}/{record_date}.m4a
    def upload_photo(self, user_id: str, diary_date: date, data: bytes) -> DiaryPhoto: ...   # 다시 올리면 덮어씀
    def get_photo(self, user_id: str, diary_date: date) -> DiaryPhoto | None: ...
    def list_photos(self, user_id: str, since: date, until: date) -> list[DiaryPhoto]: ...   # 일기 캘린더
    def signed_url(self, bucket: str, path: str, expires_in: int = 3600) -> str | None: ...   # 파일이 없으면 None
    def delete_files(self, bucket: str, paths: list[str]) -> int: ...                # 경로를 아는 파일 지우기(30일 원문 정리 등), 지운 개수
    def delete_user_files(self, user_id: str) -> int: ...                            # 계정 삭제 전에
