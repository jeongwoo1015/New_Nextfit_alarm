# AI 파트 CONTRACTS (정의서 11/01 기준, 2026-11-02 갱신)

## 1. AI 파트 파일별 기능

### 1-1. 서버·앱이 부르는 곳 (요약)

| 누가 | 언제 | 부르는 것 | 받는 것 |
|---|---|---|---|
| 서버 | 밤·브리핑·코칭 토큰 발급 | `live.config.build_setup(kind, 입력)`, `start_signal(kind)`, `token_request_body(setup)` | Live 설정(프롬프트·함수·음성), 시작 신호, 임시 토큰 요청 본문 |
| 앱 | Live 대화 중 | AI가 부르는 함수를 받아 처리 | `end_session`, `start_routine`, `report_step_result`, `adjust_step_time` |
| 서버 | 통화 종료 후 | `tasks.night_record.extract_night_record(원문, llm)` | RS_NIGHT_RECORD 칸 (`override_skip_coaching` 포함) |
| 서버 | 통화 종료 후 | `tasks.routine_changes.parse_routine_changes(원문, 항목, llm)` | 항목별 `{template_item_id, duration_sec, scope}` 목록 |
| 서버 | 콘텐츠 알람 세션 생성 | `tasks.content_steps.compose_content_steps(종류, 세부, llm, today)` | 고정 5분 단계 목록 (실패해도 기본 단계) |
| 서버 | 아침 세션 시작 | `personalization.agent.decide(...)`, 세션 끝나면 `learn(...)` | 오늘의 전략 문장(브리핑·코칭용), 전략 로그 한 행 |
| 서버 | 배치 | `tasks.music.all_music_rows()` | AI 음악 9곡의 생성 프롬프트 |

### 1-2. 파일별 기능

**`ai/live/` : Gemini Live 대화 (밤·브리핑·코칭)**

| 파일 | 기능 |
|---|---|
| `config.py` | Live 연결 설정 조립(`build_setup`), 시작 신호(`start_signal`), 임시 토큰 요청 본문(`token_request_body`) |
| `inputs.py` | 서버가 넘기는 입력 형식: `Persona`, `Weather`(지역 이름 포함), `NightInput`, `BriefingInput`, `CoachingInput`, `Step` |
| `tools.py` | AI가 부르는 함수 선언: `end_session`, `start_routine`, `report_step_result`, `adjust_step_time`. `tools_for`가 세션 종류에 따라 목록을 정함 |
| `version.py` | `PROMPT_VERSION`. 프롬프트를 바꿀 때마다 올리고 전략 로그에 함께 기록 |
| `prompts/night.py` | 밤 대화 프롬프트: 일정으로 시작, 하루 요약, 내일 목표·특이사항, 하루 한정 조정, 루틴 항목 시간 변경 |
| `prompts/briefing.py` | 아침 브리핑 프롬프트: 확인 질문 1개 → 시각·날씨(지역)·일정·어젯밤 이어받기 → 다음 세션 준비 확인 |
| `prompts/coaching.py` | 코칭 프롬프트: 단계를 하나씩 안내·판정, 루틴 세션은 오늘 시간 변경(`adjust_step_time`) 규칙 포함 |
| `prompts/common.py` | 세 프롬프트가 함께 쓰는 조각: 안전 규칙, 음성 규칙, 어조, 일정·날씨·어젯밤 기록 블록 |

**`ai/tasks/` : 서버가 부르는 AI 함수 (Live가 아닌 텍스트 호출)**

| 파일 | 기능 |
|---|---|
| `night_record.py` | 밤 대화 원문 → RS_NIGHT_RECORD 칸. 하루 한정 변경은 `override_wake_time`, `override_skip_coaching` |
| `routine_changes.py` | 밤 대화 원문 → 루틴 항목별 빼기(0초)·시간 변경(30초 단위)·되돌리기(null)와 범위(`TOMORROW`/`ALWAYS`) |
| `content_steps.py` | 콘텐츠 알람(명상·운동·외국어·퀴즈)의 고정 5분 단계 구성. AI 호출이 실패해도 기본 단계를 돌려줌 |
| `music.py` | AI 음악 9곡(재기상 5, 수면 4)의 생성 프롬프트와 DB 시드 행 |
| `llm.py` | Gemini 텍스트 호출(`GeminiText`): 과부하 시 다른 모델로 넘어가기, 총 대기시간 상한, JSON 응답 정리 |
| `transcript.py` | 통화 원문을 AI에 넘길 글자로 바꿈 |

**`ai/personalization/` : 개인화 (기록 → 통계 → 전략 → 프롬프트)**

| 파일 | 기능 |
|---|---|
| `records.py` | DB 행(세션·단계·밤 기록)을 읽는 형식. `coaching_skipped`, `snooze_count`, `wake_checked_at` 등 11/01 칸 |
| `user_model.py` | 기록 → 통계(기상 성공률, 수행률, 연속 달성일, 리포트 구간) |
| `rules.py` | 통계 → 전략을 정하는 if-then 규칙 (Bandit과 비교하는 기준) |
| `bandit.py` | 상황별로 전략을 고르고 결과로 학습하는 Contextual Bandit (상태는 `to_dict`/`from_dict`) |
| `reward.py` | 보상 계산 (기상 성공 + 단계 수행 비율, 코칭 안 한 날은 기상 성공만) |
| `strategy.py` | 전략 값(코칭 강도·브리핑 길이·응원 정도)과 프롬프트에 붙일 문장 |
| `agent.py` | `decide`(오늘의 전략 결정, 실패해도 기본 전략으로 폴백)와 `learn`(결과 학습). 모드 off/rule/bandit/ab |
| `logs.py` | 전략 결정 로그(`DecisionLog`) → RS_STRATEGY_LOG 한 행 |
| `sim.py` | 가상 사용자로 전체 흐름이 끝까지 도는지 확인 (효과의 근거는 아님) |

**`ai/devclient/` : 앱 없이 AI를 시험하는 PC 도구 (실제 Gemini 호출, 맥 전용)**

| 파일 | 기능 |
|---|---|
| `client.py` | 서버가 하는 일(프롬프트·토큰)과 앱이 하는 일(Live 연결, 시작 신호, 함수 응답)을 대신하는 클라이언트 |
| `scenarios.py` | 가상 사용자 시나리오(밤, 브리핑, 코칭, 콘텐츠). 사용자 대사는 고정 |
| `rules.py` | 대화 결과 검사. HARD(어기면 실패)와 SOFT(경고) |
| `run.py`, `tts.py` | 시나리오 실행기, 가상 사용자 합성 음성 |

**`ai/tests/`:** 단위 테스트 349개 (네트워크 없음). 프롬프트·입력·함수 선언·검사 규칙·개인화 계산을 확인한다.

## 2. 이번에 수정한 사항과 수정 방법

| 번호 | 수정 내용 | 어떻게 수정했나 |
|---|---|---|
| 1 | 알람은 루틴 또는 콘텐츠 **하나** | 콘텐츠 뒤에 루틴이 이어지는 흐름을 삭제했다. `routine_follows`, `next_program_name` 입력과 코칭 프롬프트의 "루틴으로 넘어가는 마무리"를 지웠고, `start_routine`은 브리핑에서만 부른다. |
| 2 | 코칭 길이(10/20분) 폐지 | `BriefingInput.coaching_min`을 `coaching_skipped: bool`로 바꿨다. 코칭을 안 하는 날은 브리핑만 하고 `start_routine`을 제공하지 않는다. |
| 3 | 콘텐츠 고정 5분 | `compose_content_steps(종류, 세부, llm, today)`에서 코칭 길이 인자를 삭제하고 300초로 고정했다. 단계 수 안내와 폴백 단계도 5분 기준으로 바꿨다. |
| 4 | 날씨에 지역 이름 | `Weather.location_name`을 추가했다. 값이 있으면 "오늘 광진구 날씨는"처럼 말한다. 날씨 값이 없으면 지역만 있어도 날씨를 말하지 않는다. |
| 5 | 기상 확인 | 브리핑 첫머리를 "일어났어요?"에서 **확인 질문 1개**("잘 주무셨어요?" 등)로 바꿨다. 졸리다는 답이나 엉뚱한 답도 응답으로 보고 재촉하지 않는다. 시작 신호도 맞췄다. |
| 6 | 루틴 시간 변경과 범위 | `parse_routine_changes`의 결과를 `changes: [{template_item_id, duration_sec, scope}]`로 바꿨다. `duration_sec` 0은 빼기, 양수는 30초 단위로 맞춘 시간, null은 되돌리기다. `scope`는 기본 `TOMORROW`, "앞으로/계속"이면 `ALWAYS`. 이미 뺀 항목을 또 빼기, 지금과 같은 시간, 시간 없는 "줄여 줘", 모든 항목 빼기는 걸러낸다. `RoutineItem`에 현재 시간 `duration_sec`을 선택 칸으로 추가했다. |
| 7 | 밤 프롬프트 | "시간 변경은 할 수 없다"를 고쳐 빼기·시간 변경·되돌리기를 받는다. 범위는 기본 내일 하루만, 예시는 "내일 하루만 빠져요", 제안은 "코칭은 쉬어 갈까요?". 코칭 길이(`tomorrow_coaching_min`)를 삭제했고, 항목 현재 시간(`routine_item_secs`)을 선택 입력으로 받는다. 반말 설정에 존댓말 예시("바꿔 둘게요")가 남아 있던 이전 문제도 고쳤다. |
| 8 | 밤 기록 추출 | `override_coaching_min`(10/0)을 `override_skip_coaching`(true/false)으로 바꿨다. 문자열 "true"처럼 애매한 값은 false로 본다. |
| 9 | 코칭 중 오늘 시간 변경 | `adjust_step_time(step_order, seconds)` 함수를 루틴 세션에만 추가했다(콘텐츠는 고정 시간). 시간을 말하면 바로 호출하고, 안 말하면 한 번 묻는다. 0은 오늘만 빼기이고, 같은 단계에 판정(`report_step_result`)과 함께 부르지 않는다. |
| 10 | 개인화 기록 칸 | `SessionRecord`에서 `coaching_min`을 지우고 `coaching_skipped`, `planned_total_sec`, `snooze_count`, `wake_checked_at`을 추가했다. 코칭을 안 한 날은 수행률과 보상에서 단계를 제외한다. |
| 11 | 전략 로그·Bandit 상태 대조 | 정의서 14번(`RS_STRATEGY_LOG`), 15번(`RS_BANDIT_STATE`)의 칸이 `DecisionLog`, `ContextualBandit.to_dict()`와 이미 같음을 확인했다. 코드 변경은 없다. |
| 12 | 프롬프트 버전 | `PROMPT_VERSION`을 `2026-11-01.2`에서 `2026-11-02.3`까지 세 번 올렸다 (커밋 1, 2, 3이 각각 올림). |

## 3. 완성도 분류

### 3-1. 완료 (AI 쪽 기준)

AI 쪽 코드 + 단위 테스트 + (프롬프트가 바뀐 곳은) 실제 Gemini 시험 통과를 기준으로 한다. 합성 음성과 고정 대사로 돌린 값이라 표본이 작다.

| 항목 | 검증 근거 |
|---|---|
| 루틴 또는 콘텐츠 하나, 코칭 길이 폐지 (1, 2번) | 단위 테스트, 실제 Gemini 브리핑 시나리오 통과 |
| 날씨 지역 말하기 (4번, AI 쪽) | 실제 Gemini 브리핑 3개 모두 "광진구" 언급. 지역 데이터는 서버 몫이라 B4 참고 |
| 기상 확인 질문 1개 (5번) | 5개 시나리오 × 3회 중 14회 통과 (실패 1회는 `start_routine` 호출 누락) |
| 밤 루틴 시간 변경·범위·되돌리기 (6, 7번) | 실제 Gemini 밤 시나리오 6회 통과 (빼기 2, 내일만 시간 변경 2, 앞으로 계속 2), 파서 단위 테스트 |
| 밤 기록 `override_skip_coaching` (8번) | 하루 조정 동의 2회(true), 거절 2회(false) 통과 |
| 코칭 `adjust_step_time` (9번) | 실제 Gemini 시나리오 9회 모두 통과 (시간을 바로 말함 3, 되묻기 3, 오늘 빼기 3) |
| 개인화 기록 칸, 전략 로그·Bandit 상태 (10, 11번) | 단위 테스트 349개, `sim.py` 끝까지 실행 |

### 3-2. 부족하거나 아직 안 된 것

| 번호 | 부족한 점 | 현재 상태 |
|---|---|---|
| B1 | 기상 확인 질문이 **1개** (팀 기준은 2~3개) | AI가 답 개수를 세며 진행하면 불안정해 1개로 줄였다. 2~3개는 앱이 질문 순서를 맡아야 한다. |
| B2 | 팀 설계의 `wake_check_done` **함수를 제공하지 않음** | 실제 Gemini 시험에서 함수 방식은 질문 개수와 상관없이 약 60~70%만 성공했고, 함수 없이 질문 1개만 하는 방식은 15번 중 14번 성공했다. 대신 앱이 첫 대답을 감지할 때 `wake-check`를 부르는 방식을 제안한다. 팀 합의 전이다. |
| B3 | 브리핑 연결이 끊겼다가 다시 연결되면 확인 질문부터 **다시 물음** | 브리핑 입력에 "확인 질문 이미 마침" 칸이 없다. |
| B4 | 날씨 지역 이름이 **실제로는 안 나옴** | AI는 준비됐지만 서버가 날씨(`weather=None`)와 지역을 아직 넘기지 않는다. 지역 정보를 받는 방법이 팀 결정 중이다. |
| B5 | 서버 어댑터 `ai_gateway.py`가 **옛 입력 이름을 씀** | 이 AI 버전과 합치면 `test_ai_real.py`가 실패한다. 수정 목록은 5장. |
| B6 | 루틴 항목의 **현재 시간을 서버가 안 넘김** | 선택 입력이라 동작은 하지만 "줄여 줘" 판단이 덜 정확하다. |
| B7 | `adjust_step_time`을 서버가 **거절했을 때** AI 응답 처리가 없음 | 서버가 422(30초 단위 아님, 합계 900초 초과 등)를 돌려줄 때 앱이 AI에 무엇을 돌려줄지 정해지지 않았다. |
| B8 | 콘텐츠 5분 구성을 **실제 모델로 재측정하지 않음** | 단위 테스트(가짜 LLM)만 통과. `content_option` 값 목록도 팀 확정 전이다. |
| B9 | Live **10분·15분 한도** 대응 없음 | 코칭은 재연결 입력(`resumed`)으로 이어서 할 수 있지만 브리핑·밤은 처음부터 다시 시작한다. 압축은 적용하지 않았다. |
| B10 | AI 음악 **mp3 9개가 없음** | 프롬프트만 준비됐다. 파일 생성은 AI 담당 몫이다. |
| B11 | **실제 사람 목소리·실제 앱 연결** 시험을 안 함 | 합성 음성과 고정 대사로만 시험했다. AI가 함수 호출을 가끔 빼먹는다(`start_routine` 누락 1회). |
| B12 | 개인화 **효과 검증 안 됨** | `sim.py`는 가상 사용자라 효과의 근거가 아니다. 스누즈 횟수는 읽기만 하고 통계·규칙에는 쓰지 않는다. |

## 4. 부족한 사항을 이어서 개발하는 방법 (협업)

**진행 순서 제안:** B5(서버 어댑터)를 가장 먼저 한다 → B2·B1(기상 확인 방식을 팀이 합의) → B4·B6(서버가 날씨 지역·항목 시간 전달) → B7·B3 → B8·B9 → B10 → B11·B12는 사용자 테스트 기간.

### B5. 서버 어댑터 수정
- **담당:** 서버 담당. **AI:** 수정 목록 제공(5장).
- **끝난 기준:** `backend/tests/test_ai_real.py`가 새 AI 버전에서 통과.

### B2·B1. 기상 확인 방식과 질문 2~3개
- **합의할 것:** `wake_check_done` 함수 대신 앱이 사용자의 첫 대답을 감지할 때 `POST …/wake-check`를 부르는 방식으로 간다(6장).
- **2~3개로 늘릴 때:** 앱이 질문 순서를 맡는다. 연결 직후 "확인 질문 1 해 주세요", 답을 감지하면 "확인 질문 2 해 주세요", 마지막 답 뒤에 "브리핑을 시작해 주세요"를 텍스트 신호로 보낸다.
- **AI가 할 일:** `START_SIGNALS`를 단계별 신호로 늘리고, 브리핑 프롬프트를 "신호마다 한 가지만 말하고 멈춘다"로 바꾼다. devclient에 단계별 신호 시뮬레이션을 추가한다.
- **앱(프론트엔드)이 할 일:** 답 감지 후 다음 신호 전송, 마지막 답 뒤 `wake-check` 호출.
- **끝난 기준:** devclient 5개 시나리오 × 3회에서 현재 수준(14/15) 이상, 이후 실제 앱에서 팀 시험.

### B3. 브리핑 재연결
- **AI가 할 일:** `BriefingInput`에 `wake_checked: bool`을 추가하고, 참이면 확인 질문을 건너뛰고 바로 브리핑하는 프롬프트로 바꾼다.
- **서버가 할 일:** 세션의 `wake_checked_at`이 있으면 `BriefingContext`에 참으로 전달한다.
- **끝난 기준:** devclient에 "재연결" 브리핑 시나리오 추가 후 통과.

### B4. 날씨 지역
- **팀이 정할 것:** 지역 정보를 받는 방법(앱 위치 권한, 사용자 설정 등)과 날씨 데이터 출처.
- **서버가 할 일:** 결정되면 `Weather(summary=…, location_name="광진구")`를 만들어 `BriefingInput.weather`에 넣는다. AI는 이미 준비돼 있다.
- **끝난 기준:** 실제 앱에서 브리핑이 지역 이름과 함께 날씨를 말함.

### B6. 항목 현재 시간
- **서버가 할 일:** `RoutineItem(..., duration_sec)`와 `NightInput.routine_item_secs`에 내일 진행할 항목의 현재 시간(초)을 채운다.
- **AI:** 이미 읽을 준비가 됐다. 끝난 기준은 밤 시나리오에서 "줄여 줘"류 요청이 현재 시간 기준으로 해석되는지 확인.

### B7. `adjust_step_time` 거절 응답
- **합의할 것:** 앱이 서버 거절을 AI에 알리는 응답 형식(예: `{"result":"error","reason":"TOTAL_OVER"}`).
- **AI가 할 일:** 코칭 프롬프트에 "오류 응답이면 사용자에게 어렵다고 짧게 말한다"를 추가하고 devclient의 함수 응답에 오류 경우를 추가한다.
- **앱이 할 일:** 서버 응답에 따라 `ok` 또는 오류 응답을 Live에 돌려준다.

### B8. 콘텐츠 5분 구성
- **팀이 정할 것:** `content_option` 값 목록 확정.
- **AI가 할 일:** `CONTENT_OPTIONS`를 확정 목록으로 갱신하고, 종류별 3회씩 실제 모델로 구성해 5분 합계·단계 수를 확인한다.

### B9. Live 한도(10분·15분)
- **팀이 정할 것:** 앱이 한도 전에 재연결할지, 대화를 압축할지.
- **AI가 할 일:** 정해지면 브리핑·밤에도 재연결 입력과 프롬프트를 추가한다(코칭은 `resumed`로 이미 가능).

### B10. 음악 mp3 9개
- **담당:** AI 담당(생성), 서버 담당(업로드). 파일 이름은 `{REWAKE 또는 SLEEP}_{장르 또는 기분}.mp3`.
- **프롬프트:** `tasks/music.py`의 `all_music_rows()`. REWAKE는 POP, JAZZ, CLASSIC, DANCE, ACOUSTIC, SLEEP은 GOOD, NORMAL, TIRED, STRESSED.
- **끝난 기준:** 서버 담당이 `music` 버킷에 올리고 `rs_ai_music` 9행을 넣은 뒤 음악 API가 404가 아님.

### B11. 실제 사람 목소리·앱 연결 시험
- **팀이 할 일:** 앱이 서버·AI와 연결되면 팀원이 실제 목소리로 시험하고 통화 로그를 공유한다.
- **AI가 할 일:** 로그에서 규칙을 어긴 사례를 모아 devclient 검사와 프롬프트에 반영한다. 함수 호출 누락에 대비해 앱에 수동 시작·종료 경로를 두는 것을 권장한다.

### B12. 개인화 효과 검증
- **서버가 할 일:** 사용자 테스트 기간에 `rs_strategy_log`와 보상(`REWARD`)을 쌓는다.
- **AI가 할 일:** 모드 `ab`로 규칙과 Bandit을 비교하는 분석을 만든다. 스누즈 통계는 데이터가 쌓인 뒤 필요하면 반영한다.

## 5. 서버 어댑터(`backend/clients/ai_gateway.py`) 수정 목록

옛 이름을 새 이름으로 바꾸면 된다. 아래 외의 부분은 그대로 동작한다.

```
# issue_briefing_token
-   program_name=…, content_name=…, routine_follows=routine,
-   coaching_min=0 if ctx.coaching_skipped else 10,
+   program_name=…, content_name=…,
+   coaching_skipped=ctx.coaching_skipped,

# issue_coaching_token
-   routine_follows=False,                       (이 인자 삭제)

# compose_content_steps
-   compose_content_steps(type, option, 10 if duration_sec <= 900 else 20, self.llm, today=today)
+   compose_content_steps(type, option, self.llm, today=today)     # 항상 300초로 구성

# issue_night_token
-   tomorrow_coaching_min=None,                  (이 인자 삭제)
+   routine_item_secs=…                          (선택: 항목 현재 시간, routine_items와 같은 순서)

# parse_routine_changes
-   RoutineItem(i.template_item_id, i.item_name, i.duration_sec > 0)
+   RoutineItem(i.template_item_id, i.item_name, i.duration_sec > 0, i.duration_sec)
    (결과의 .changes는 기존 to_item_changes가 이미 읽는다)

# to_night_record_fields
    override_skip_coaching만 읽으면 된다 (override_coaching_min 항목은 삭제 가능)
```

## 6. 앱·서버 연결 변경

1. **기상 확인:** 브리핑 Live에서 AI는 확인 질문 1개를 하고 사용자의 대답을 기다린다. 앱은 첫 대답을 감지하면 기존대로 `POST …/response`를 부르고, **이어서 `POST …/wake-check`를 부른다.** AI는 `wake_check_done` 함수를 부르지 않는다. 8초 무응답은 기존대로 재기상 음악과 `…/rewake`다.
2. **`start_routine`:** 기존과 같다. 코칭을 안 하는 날(`coaching_skipped`)에는 함수 목록에 없다.
3. **`adjust_step_time(step_order, seconds)`:** 루틴 코칭 Live에서 AI가 부른다. 앱은 `PATCH …/steps/{n}/duration`으로 서버에 전달하고 AI에는 `{"result":"ok"}`를 돌려준다. 콘텐츠 코칭에는 이 함수가 없다. 거절 시 응답 형식은 B7에서 정한다.
4. **`report_step_result`, `end_session`:** 기존과 같다.
