# DB 함수(repository) 목록

기준: DB 테이블 정의서 11/01 수정본, `supabase/01_schema.sql`(06 변경 포함)
작성: backend-db (찬미). 2차: 정우님 답변 반영 (11/01)

## 0. 공통 규칙

- 위치: `backend/repositories/`. 인터페이스(`base.py`)와 테스트용 메모리 구현, `requirements.txt`(supabase>=2.32,<3)는 backend-service(정우), Supabase 구현(`supabase_*.py`)만 backend-db(찬미). 같은 파일을 두 브랜치에서 고치지 않는다
- 서버는 service_role 키를 쓰므로 RLS가 막아 주지 않는다. 사용자 데이터 함수는 모두 `user_id`를 받아 조회 조건에 같이 건다. 본인 행이 아니면 없는 것과 같이 None을 돌려주고, 서버는 404로 응답한다(남의 데이터가 있는지 알려 주지 않음)
- 반환 형식: `domain/models.py` 데이터클래스 (정우님 30215ca에서 11/01 기준으로 수정). 필드 이름은 DB 컬럼 소문자와 같다
- 날짜 `record_date`, `apply_date`는 DB 트리거가 계산한다. 서버는 넣지 않는다
- 알람 항목 설정은 DB 함수 `rs_replace_alarm_routine_items` 한 번으로 교체한다(삭제와 저장이 한 트랜잭션). 루틴 합계 검사는 커밋할 때 하므로 중간 상태에서 실패하지 않는다

## 1. 사용자·설정 (`UserRepository`)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `get_user` | user_id | User 또는 None | rs_user | 로그인 후 온보딩 여부 확인 |
| `create_user` | user_id, user_name, birth_date, gender | User | rs_user | 온보딩 1단계 |
| `update_user` | user_id, 바꿀 값 | User 또는 None | rs_user | 내 정보 수정, 끊긴 온보딩 이어 하기 |
| `set_onboarding_done` | user_id | 없음 | rs_user | 온보딩 끝 |
| `get_setting` | user_id | UserSetting 또는 None | rs_user_setting | 설정 화면, Live 입력(Persona) |
| `save_setting` | user_id, 바꿀 값 | UserSetting | rs_user_setting | 온보딩, 설정 수정 (없으면 만들고 있으면 수정) |
| `delete_account` | user_id | 없음 | Storage 파일 → auth.users | 계정 삭제. 파일을 먼저 지우고 Auth 계정을 지우면 DB 행은 CASCADE로 삭제 |

## 2. 공용 데이터 (`PresetRepository`)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `list_templates` | active_only=True | list[Template] | rs_routine_template | 프리셋 선택 카드 |
| `get_template` | template_id | Template 또는 None | rs_routine_template | 알람 저장 시 활성 프리셋인지 검사 |
| `list_template_items` | template_id | list[TemplateItem] | rs_routine_template_item | 프리셋 상세, 알람 세부 설정 |
| `get_app_config` | 없음 | AppConfig (routine_max_sec, content_duration_sec) | rs_app_config | 알람 세부 설정의 상한 표시, 콘텐츠 단계 시간 |
| `pick_music` | music_type, genre 또는 mood | AiMusic 또는 None | rs_ai_music | 재기상·수면 음악 (같은 조건에서 무작위 1개) |

## 3. 알람 (`AlarmRepository`)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `list_alarms` | user_id | list[Alarm] | rs_alarm | 홈 알람 목록, 다음 알람 고르기 |
| `get_alarm` | user_id, alarm_id | Alarm 또는 None | rs_alarm | 알람 상세 |
| `create_alarm` | user_id, Alarm 값 | Alarm | rs_alarm | 알람 추가, 온보딩 첫 알람 |
| `update_alarm` | user_id, alarm_id, 바꿀 값 | Alarm | rs_alarm | 알람 수정, 켜기/끄기. 프리셋을 바꾸면 항목 설정은 DB가 지움 |
| `delete_alarm` | user_id, alarm_id | 없음 | rs_alarm | 알람 삭제. 지난 세션은 남고 하루 한정 변경은 DB가 비움 |
| `get_alarm_routine` | user_id, alarm_id | list[AlarmRoutineItem] 또는 None | 함수 rs_alarm_routine_items | 알람 세부 설정 화면, 세션 단계 만들기, 밤 대화 입력 |
| `replace_alarm_routine` | user_id, alarm_id, {template_item_id: 초} | list[AlarmRoutineItem] 또는 None | 함수 rs_replace_alarm_routine_items | 항목 시간 조정·빼기(0초) 전체 교체. 기본 시간과 같은 값과 넘기지 않은 항목은 기본 시간으로 |

`AlarmRoutineItem` = DB 함수 `rs_alarm_routine_items` 한 줄과 같은 평평한 모양: template_item_id, item_order, item_name, default_duration_sec, duration_sec(적용 시간, 0 = 뺌), response_type, script.
합계는 `sum(duration_sec)`로 구한다(DB 함수 `rs_alarm_routine_total`과 같은 값).
앱은 시간 선택지에서 `default_duration_sec`과 같은 값에 "(기본)"을 표시한다. 따로 "기본값으로 되돌리기" 함수는 두지 않는다.
밤 대화로 항목을 빼거나 다시 넣을 때도 하루 한정 변경과 같은 알람(`pick_override_alarm` 결과)에 `replace_alarm_routine`으로 적용한다.

## 4. 밤 기록 (`NightRepository`)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `save_night_raw` | user_id, transcript, started_at, ended_at | night_record_id | rs_night_record(PENDING), rs_night_raw | 통화 종료 후 원문 저장. 같은 밤에 다시 저장하면 덮어씀 |
| `save_night_extract` | user_id, night_record_id, 추출 결과 dict(11/01 컬럼 이름), override_alarm_id | NightRecord 또는 None | rs_night_record | 추출 결과 저장, EXTRACT_STATUS = DONE |
| `mark_extract_failed` | user_id, night_record_id | 없음 | rs_night_record | 추출 실패 (FAILED) |
| `get_night_raw` | night_record_id | NightRaw 또는 None | rs_night_raw | 다시 추출할 때 원문 읽기 |
| `list_failed_extracts` | limit | list[(user_id, night_record_id)] | rs_night_record | 재추출 배치 |
| `get_night_for_morning` | user_id, apply_date | NightRecord 또는 None | rs_night_record | 아침 브리핑 "어젯밤 이어받기", 세션 스냅샷 |
| `get_night_by_date` | user_id, record_date | NightRecord 또는 None | rs_night_record | 일기 화면 |
| `set_voice_path` | user_id, night_record_id | 경로 | rs_night_record | 목소리 업로드 후 경로 저장 (`{user_id}/{record_date}.m4a`) |
| `purge_expired_raw` | now | 지운 파일 경로 목록 | rs_night_raw + Storage | 30일 보관 배치 |

추출 결과 dict는 11/01 컬럼 이름으로 받는다. AI 출력의 `override_coaching_min` → `override_skip_coaching` 변환은 정우님 AI 어댑터에서 한다.

## 5. 모닝 세션·단계 (`SessionRepository`)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `get_or_create_session` | SessionSnapshot | MorningSession | rs_morning_session | 알람이 울릴 때. 같은 알람·날짜 세션이 있으면 그것을 돌려줌(재연결) |
| `get_session` | user_id, session_id | MorningSession 또는 None | rs_morning_session | 세션 화면, 재연결 |
| `update_session` | user_id, session_id, 바꿀 값 | MorningSession 또는 None | rs_morning_session | alarm_rang_at, first_response_at, planned_total_sec(단계를 만든 뒤), wake_success, session_end_at |
| `add_rewake` | user_id, session_id | 바뀐 횟수 | rs_morning_session | 8초 무응답으로 재기상 음악 재생 |
| `create_steps` | user_id, session_id, list[StepPlan] | list[SessionStep] | rs_session_step | 브리핑 뒤 단계 만들기 (PENDING). 이미 있으면 만들지 않음 |
| `list_steps` | user_id, session_id | list[SessionStep] | rs_session_step | 진행 개수, 재연결 시 PENDING 단계 |
| `save_step` | user_id, SessionStep | SessionStep | rs_session_step | 판정 반영·직접 체크 결과 저장 (상태 계산은 domain/step_rules) |
| `recent_records` | user_id, since, until | (세션 목록, 단계 목록, 밤 기록 목록) dict | 세 테이블 | 개인화 `build_sessions()` 입력, 리포트 |

`planned_total_sec`은 스냅샷이 아니라 단계를 만든 뒤 `update_session`으로 채운다.

## 6. 개인화 (`PersonalizationRepository`, 서버만)

| 함수 | 입력 | 출력 | 테이블 | 쓰는 곳 |
|---|---|---|---|---|
| `save_strategy_log` | user_id, session_id, `d.log.to_row()` dict | log_id | rs_strategy_log | 브리핑 직전 decide() 결과 저장. JSON 문자열 칸은 JSON으로 바꿔 저장 |
| `get_strategy_log` | user_id, session_date | StrategyLog 또는 None | rs_strategy_log | 같은 날 재연결 때 처음 정한 전략 다시 쓰기. 문장은 `strategy_block(Strategy.from_dict(log.strategy), ...)`로 다시 만듦(DB 칸 추가 없음) |
| `set_reward` | log_id, reward | 없음 | rs_strategy_log | 하루 끝 결과 점수 |
| `get_bandit_state` | state_key ('global' 또는 'user:{id}') | dict 또는 None | rs_bandit_state | decide() 전 |
| `save_bandit_state` | state_key, state dict | 없음 | rs_bandit_state | learn() 후 |

## 7. Storage (`StorageClient`)

| 함수 | 입력 | 출력 | 버킷 | 쓰는 곳 |
|---|---|---|---|---|
| `upload_voice` | user_id, record_date, 파일 | 경로 | voice | 밤 대화 목소리 |
| `upload_photo` | user_id, diary_date, 파일 | DiaryPhoto | photo + rs_diary_photo | 일기 사진 (다시 올리면 덮어씀) |
| `get_photo` | user_id, diary_date | DiaryPhoto 또는 None | rs_diary_photo | 일기 화면 |
| `list_photos` | user_id, since, until | list[DiaryPhoto] | rs_diary_photo | 일기 캘린더 한 달 치 |
| `signed_url` | bucket, path, 만료 초 | URL | 전체 | 앱에서 재생·보기 |
| `delete_user_files` | user_id | 지운 개수 | voice, night-raw, photo | `delete_account`가 먼저 부름 |

## 8. DB 오류 → 서버 오류

DB가 막은 요청은 `core/errors.py` 형식으로 바꿔 돌려준다.

| DB 오류 | 서버 오류 | code |
|---|---|---|
| 루틴 합계 상한 초과 (트리거) | ValidationFailed 422 | `ROUTINE_OVER_LIMIT` |
| 모든 항목을 뺌 (트리거) | ValidationFailed 422 | `ROUTINE_ALL_REMOVED` |
| 30초 단위가 아님, 값 목록 밖 (CHECK) | ValidationFailed 422 | `VALIDATION_FAILED` |
| 알람 프리셋에 없는 항목, 콘텐츠 알람에 항목 설정 (트리거) | ValidationFailed 422 | `VALIDATION_FAILED` |
| 교체 함수의 '알람이 없습니다' (P0002) | NotFound 404 | `NOT_FOUND` |
| 같은 날 중복 (UNIQUE) | Conflict 409 | `CONFLICT` |
| 없는 행, 다른 사용자 행 (Repository가 None) | NotFound 404 | `NOT_FOUND` |
| 연결 실패·시간 초과 | 503 | `DB_UNAVAILABLE` |

## 9. 정해진 것 (11/01 정우님 답변)

1. 함수 목록: 위 목록으로 확정. update_user, get_template, list_photos, delete_account 추가, 항목 저장은 전체 교체 함수 하나
2. `base.py`, `requirements.txt`, 메모리 구현은 정우님. 찬미는 `supabase_*.py`만
3. Supabase 연결은 supabase-py
4. 모델은 정우님이 수정 중. 남은 것: MorningSession, NightRaw, DiaryPhoto, AiMusic, StrategyLog, 평평한 AlarmRoutineItem
5. 본인 데이터가 아니면 None → 404
6. 오류 코드는 8절 표로 통일 (ROUTINE_OVER_LIMIT, ROUTINE_ALL_REMOVED, DB_UNAVAILABLE 503)
7. 재연결 전략 문장은 로그의 strategy로 다시 만듦. DB 칸 추가 없음
8. 하루 한정 변경과 대화로 바꾼 루틴 항목은 `pick_override_alarm`이 고른 같은 알람에 적용
9. DB 변경(찬미): 루틴 합계 검사를 커밋 때 하도록 바꾸고 전체 교체 함수 추가 (`supabase/06_alarm_items_replace.sql`). 루틴 알람을 콘텐츠 알람으로 바꿀 때 항목 설정이 있으면 막히던 문제도 같이 고침
