# AI 파트 CONTRACTS (정의서 11/01 기준, 2026-11-14 갱신: 아침 순서표, 스트레칭 부위, 성격·강도, 영어 수준, 자막 숫자, 판정 보고 검사 추가)

## 1. AI 파트 파일별 기능

### 1-1. 서버·앱이 부르는 곳 (요약)

| 누가 | 언제 | 부르는 것 | 받는 것 |
|---|---|---|---|
| 서버 | 밤·브리핑·코칭 토큰 발급 | `live.config.build_setup(kind, 입력)`, `start_signal(kind)`, `token_request_body(setup)` | Live 설정(프롬프트·함수·음성), 시작 신호, 임시 토큰 요청 본문 |
| 앱 | Live 대화 중 | AI가 부르는 함수를 받아 처리 | `end_session`, `start_routine`, `report_step_result`, `adjust_step_time`, `select_stretch_area`(스트레칭 알람만) |
| 서버 | 통화 종료 후 | `tasks.night_record.extract_night_record(원문, llm)` | RS_NIGHT_RECORD 칸 (`override_skip_coaching` 포함) |
| 서버 | 통화 종료 후 | `tasks.routine_changes.parse_routine_changes(원문, 항목, llm)` | 항목별 `{template_item_id, duration_sec, scope}` 목록 |
| 서버 | 콘텐츠 알람 세션 생성 | `tasks.content_steps.compose_content_steps(종류, 세부, llm, today)` | 고정 5분 단계 목록과 오늘 쓴 세부(`option`). 스트레칭·퀴즈(인물·상식)·끝말잇기는 AI 없이 정해 둔 단계(`source="fixed"`), 영어는 AI가 구성하고 실패해도 기본 단계 |
| 서버 | 아침 세션 시작(포춘 단계 전) | `tasks.fortune.compose_fortune(한마디, llm, style, tone, today, diary_text)` | 읽을 포춘 문장 1개 (`text`, `style`, `source`). 실패·한마디 없음이어도 기본 문장 |
| 서버 | 포춘 읽기 토큰 발급 | `live.config.build_setup("fortune", FortuneInput(persona, fortune_text, style))`, `start_signal("fortune")` | 문장을 그대로 읽는 짧은 Live 설정 (사용자가 끝내겠다고 할 때만 쓰는 `end_session` 외 함수 없음) |
| 서버 | 아침 세션 시작 | `personalization.agent.decide(...)`, 세션 끝나면 `learn(...)` | 오늘의 전략 문장(브리핑·코칭용), 전략 로그 한 행 |
| 서버 | 아침 세션 시작 | `tasks.morning_plan.morning_plan(has_one_liner, coaching)` | 아침 순서표(예: `["BRIEFING","ONE_LINER","COACHING","FORTUNE"]`). 세션 시작 응답에 넣고 세 입력(`plan=`)에도 넘긴다 |
| 서버 | 스트레칭 알람 세션 시작 | `tasks.stretch_sets.all_area_steps()` | 부위 5개 루틴(전신·목어깨·팔손목·등허리골반·다리). 앱에 넘기고 `CoachingInput(stretch_routines=…)`에도 넘긴다 |
| 서버 | 아침 세션 시작 | `personalization.intensity.today_intensity(설정 강도, history_from_sessions(최근 세션), 자동 여부)` | 그날 깨우는 말 강도(1~3). `Persona.intensity`로 넘기고 `APPLIED_INTENSITY`에 저장 |
| 서버 | 배치 | `tasks.music.all_music_rows()` | AI 음악 9곡의 생성 프롬프트 |

### 1-2. 파일별 기능

**`ai/live/` : Gemini Live 대화 (밤·브리핑·코칭)**

| 파일 | 기능 |
|---|---|
| `config.py` | Live 연결 설정 조립(`build_setup`), 시작 신호(`start_signal`), 임시 토큰 요청 본문(`token_request_body`) |
| `inputs.py` | 서버가 넘기는 입력 형식: `Persona`, `Weather`(지역 이름 포함), `NightInput`, `BriefingInput`, `FortuneInput`, `CoachingInput`, `Step` |
| `tools.py` | AI가 부르는 함수 선언: `end_session`, `start_routine`, `report_step_result`, `adjust_step_time`. `tools_for`가 세션 종류(밤·브리핑·포춘·코칭)에 따라 목록을 정함 |
| `version.py` | `PROMPT_VERSION`. 프롬프트를 바꿀 때마다 올리고 전략 로그에 함께 기록 |
| `prompts/night.py` | 밤 대화 프롬프트: 일정으로 시작, 하루 요약, 내일 목표·특이사항, 하루 한정 조정, 루틴 항목 시간 변경 |
| `prompts/briefing.py` | 아침 브리핑 프롬프트: 확인 질문 1개 → 시각·날씨(지역)·일정·어젯밤 이어받기 → 다음 세션 준비 확인 |
| `prompts/fortune.py` | 포춘 읽기 프롬프트: 서버가 만든 포춘 문장을 소개 한 문장 뒤에 한 글자도 바꾸지 않고 한 번만 읽고 멈춘다(함수 호출 없음) |
| `prompts/coaching.py` | 코칭 프롬프트: 단계를 하나씩 안내·판정, 루틴 세션은 오늘 시간 변경(`adjust_step_time`) 규칙 포함 |
| `prompts/common.py` | 세 프롬프트가 함께 쓰는 조각: 안전 규칙, 음성 규칙, 어조, 일정·날씨·어젯밤 기록 블록 |

**`ai/tasks/` : 서버가 부르는 AI 함수 (Live가 아닌 텍스트 호출)**

| 파일 | 기능 |
|---|---|
| `night_record.py` | 밤 대화 원문 → RS_NIGHT_RECORD 칸. 하루 한정 변경은 `override_wake_time`, `override_skip_coaching` |
| `routine_changes.py` | 밤 대화 원문 → 루틴 항목별 빼기(0초)·시간 변경(30초 단위)·되돌리기(null)와 범위(`TOMORROW`/`ALWAYS`) |
| `fortune.py` | 밤에 남긴 한마디 → 오늘의 포춘 문장 1개. 스타일 2종(`COOKIE` 포춘쿠키 / `HOROSCOPE` 오늘의 운세), 말투(존댓말·반말), 예언·숫자·질문 금지 검증, 위기 표현이면 담담한 문장, 실패·한마디 없음이면 기본 문장 |
| `content_steps.py` | 콘텐츠 3종(스트레칭·퀴즈 게임·영어 따라 읽기)의 고정 5분 단계 구성. 영어는 AI가 만들고 나머지는 정해 둔 단계. 옛 명상·일본어·중국어도 그대로 동작 |
| `stretch_sets.py` | 스트레칭 4세트(고정 목록, 날짜별로 번갈아). 한 세트 5단계·300초, 동작 이름은 앱 넥스티 시범 키워드에 맞춤 |
| `quiz_bank.py` | 인물 퀴즈 30문제·상식 퀴즈 30문제(검수한 고정 목록). 날짜별로 3문제씩 열흘 동안 겹치지 않게 고름 |
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
| B8 | 콘텐츠 **코드값(`content_option`) 목록이 팀 확정 전** | 5분 구성과 실제 모델 확인은 끝났다(4장 B14). 목록은 옛 값과 새 값이 함께 열려 있다. |
| B14 | **콘텐츠 3종**: 서버·앱 연결과 팀 확인이 남음 | AI 쪽(단계 구성, 코칭 규칙, 정답 신호)은 완료. 4장 B14의 요청이 필요하다. |
| B9 | Live **10분·15분 한도** 대응 없음 | 코칭은 재연결 입력(`resumed`)으로 이어서 할 수 있지만 브리핑·밤은 처음부터 다시 시작한다. 압축은 적용하지 않았다. |
| B10 | AI 음악 **mp3 9개가 없음** | 프롬프트만 준비됐다. 파일 생성은 AI 담당 몫이다. |
| B11 | **실제 사람 목소리·실제 앱 연결** 시험을 안 함 | 합성 음성과 고정 대사로만 시험했다. AI가 함수 호출을 가끔 빼먹는다(`start_routine` 누락 1회). |
| B12 | 개인화 **효과 검증 안 됨** | `sim.py`는 가상 사용자라 효과의 근거가 아니다. 스누즈 횟수는 읽기만 하고 통계·규칙에는 쓰지 않는다. |
| B13 | **오늘의 포춘**: 입력·저장·읽기 세션 연결이 팀 결정 전 | AI 쪽(문장 생성, 읽기 프롬프트)은 완료. 4장 B13의 요청 4가지가 필요하다. 한마디 받아쓴 글이 아직 서버에 없어 한 줄 일기(`diary_text`)로 대신할 수 있다. |
| B15 | **아침 단계 연결**: 서버가 새 입력 2개를 넘겨야 문구가 완전해짐 | AI 쪽(브리핑 마무리, 포춘 소개, 콘텐츠 시작 문장)은 완료. 서버가 넘기지 않아도 기본 문구로 동작한다. 4장 B15 참고. |
| B16 | **밤 대화 마무리가 '내일의 나에게 남길 한마디 녹음' 안내로 끝남** | AI 쪽(프롬프트)은 완료. 앱이 `end_session` 뒤에 한마디 녹음 화면을 이어서 열어야 한다. 4장 B16 참고. |
| B17 | **11/12 회의 반영 기능**: 서버·앱·DB가 넘기고 받아야 동작이 완성됨 | AI 쪽은 완료(순서표, 스트레칭 부위 고르기, 전체 건너뛰기, 성격 4종×강도 3단계, 영어 수준, 컨디션→잠든 시각→수면시간). 넘기지 않으면 예전처럼 동작한다. 4장 B17 참고. |
| B18 | **자막 숫자 표기**: Gemini 받아쓰기가 "오 초", "열두 시"로 적음 | 변환 함수는 AI 쪽에 있고, 앱이 자막을 그리기 전에 적용해야 한다. 4장 B18 참고. |
| B19 | **스트레칭에서 AI가 사용자 대답 전에 미리 판정하거나 넘어감** | 프롬프트·설정 조정으로는 안정되지 않았다(대략 3분의 1~절반만 정상). 앱이 판정 보고를 걸러 주어야 한다. 4장 B19 참고. |

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

### B8. 콘텐츠 코드값
- **팀이 정할 것:** `content_type`·`content_option` 값 목록. AI는 지금 아래 값을 받는다(옛 값은 목록을 줄이기 전까지 그대로 동작).
  스트레칭 `EXERCISE`/`STRETCH`, 영어 `LANGUAGE`/`ENGLISH`, 퀴즈·게임 `QUIZ`/`PERSON`·`GENERAL`·`WORDCHAIN`(세부를 안 고르면 날짜별로 셋을 번갈아), 그 밖의 `MEDITATION`, `LANGUAGE`/`JAPANESE`·`CHINESE`, `QUIZ`/`SCIENCE`·`HISTORY`·`NONSENSE`는 옛 방식.
- **AI가 한 일:** 새 값에 맞춰 `compose_content_steps`를 바꿨다. 세부를 모르거나 비어 있어도 종류별 기본 규칙으로 구성한다.

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

### B14. 콘텐츠 3종 (새 아이디어 11/06: 루틴 폐기, 스트레칭·퀴즈 게임·영어 따라 읽기 중 하나)
- **AI가 한 일**
  - 스트레칭: 사람이 정한 4세트를 날짜별로 번갈아 쓴다(AI 없음). 한 세트 5단계·300초, 서서 하는 동작만, 한쪽 20초 안팎. 설계 근거는 아래 '스트레칭 근거'.
  - 퀴즈·게임: 인물·상식은 검수한 문제 목록(60문제)에서 고른다. AI가 문제를 만들게 했더니 사실이 틀린 문제가 나와서(예: 장영실, 로자 파크스) 고정 목록으로 바꿨다. 끝말잇기는 한 단계(300초)짜리 게임이다. 음악 퀴즈는 음원 재생이 필요해 제외했다.
  - 영어: 따라 읽기 3개 + 짧은 영어 질문에 한두 마디로 대답 1개(`표현 1~3`, `한마디 대답`, 60·60·60·120초). 요일마다 주제가 다르다. AI가 만들고 형식 검증, 실패하면 기본 문장.
  - 코칭 프롬프트에 콘텐츠별 규칙을 넣었다(스트레칭 안전 안내, 영어는 과한 교정 금지·한국어 힌트, 끝말잇기, 퀴즈 반응).
- **앱이 할 일**
  - **퀴즈 정답 신호:** 퀴즈 세션의 `report_step_result`에 `answer_correct`(true/false)가 항상 들어온다. 지금 앱은 AI의 말을 정규식(맞았/아쉬 등)으로 읽어 O/X를 정하는데, 이 값을 쓰도록 바꾸면 AI 말투가 바뀌어도 어긋나지 않는다. 서버에는 기존처럼 `verdict`만 전달하면 된다(`answer_correct`는 서버가 몰라도 된다).
  - **영어 자막:** `표현` 단계의 단계 문장(script)은 `영어 문장 / 한국어 뜻 / 한글 발음` 한 줄이고, `한마디 대답` 단계는 `질문: …`, `힌트: …`, `예시: …` 세 줄이다. 이 형식으로 자막을 띄우면 된다.
  - **스트레칭 시범:** 동작 이름의 키워드(목 / 어깨·가슴 / 옆구리 / 허리 / 기지개·팔·만세)를 앱 `stretchMotionFor`와 맞췄다. 이름에는 한 종류만 들어 있다. 동작 이름을 바꿀 때는 앱의 키워드와 함께 바꿔야 한다.
  - **끝말잇기:** 한 단계 안에서 여러 번 주고받고 판정은 마지막에 한 번(`done`)이다. 단계 타이머 표시 방식은 앱이 정한다.
- **서버가 할 일**
  - 코칭 세션 입력에 `content_type`, `content_option`을 넘긴다. `compose_content_steps` 결과의 `option`(오늘 쓴 세부)을 저장해 쓰면 된다. 비워 두어도 AI가 단계 문장으로 종류를 추정하지만, 넘겨 주는 쪽이 정확하다.
  - 허용 코드값을 위 B8 기준으로 정리한다.
- **팀이 정할 것**
  - **스트레칭 세부 동작(4세트).** 지금은 임시 세트다. 의사·물리치료사의 검토를 권한다.
  - **퀴즈 문제 목록 검수:** `quiz_bank.py`의 60문제를 팀이 한 번 훑어 보기.
  - 퀴즈·게임 세부를 사용자가 고르게 할지, 날짜별로 섞을지(지금은 섞음).
  - 음악 퀴즈를 할지(음원 준비·재생 필요).
- **실제 모델 확인(11/10):** 콘텐츠 5종을 실제 Live로 3번씩(스트레칭 통과 5/6, 스트레칭 아픔 3/3, 인물 퀴즈 7/7, 영어 6/6, 끝말잇기 3/4 이상) 돌렸다. 실패는 대부분 Live 쪽 일시 현상이었다: 연결이 AI 첫 응답 전에 끊김(`ConnectionClosedError`, 약 50번 중 4번), 사용자의 마지막 말에 빈 응답(함수 호출 누락). 앱은 연결이 끊기면 재연결(`resumed`)하고, 끝내는 말을 했는데 AI가 `end_session`을 빼먹을 수 있다는 점을 처리해야 한다(B11).
  시험 중 찾아 고친 것: 인물 퀴즈의 틀린 사실(고정 목록으로 변경), 영어 뜻의 반말(존댓말 규칙 추가), 아침 대화 끝에 "안녕히 주무세요"(아침 규칙 추가), 아프다고 할 때 건너뛴 단계를 기록하지 않음(규칙 보강). 끝말잇기는 '패스'를 `done` 또는 `skipped`로 기록하고 마무리하며 끝낼 수 있다(남은 단계가 없다).
- **끝난 기준:** 앱에서 콘텐츠를 고른 알람으로 아침에 콘텐츠 세션이 시작되어 단계가 진행된다.

### 스트레칭 근거와 한계
4세트는 아래 자료의 공통 내용을 바탕으로 설계했다. 특정 정형외과 의사 한 사람의 루틴을 그대로 가져온 것이 아니다.
- **유지 시간·반복:** 한 번에 15~30초, 같은 근육에 합계 30~60초(Harvard Health, ACSM 권고 10~30초). 세게 당기지 않고 약한 당김까지만, 반동 없이, 통증이 있으면 멈춘다. 그래서 정적 동작은 한쪽 20초 안팎으로 했다.
- **순서:** 가벼운 동적 동작을 먼저 하고 정적 늘리기를 한 뒤 호흡으로 마무리한다(Cleveland Clinic의 동적·정적 스트레칭 설명, Yoga With Adriene의 기상 플로우 구성).
- **동작:** 문틀(벽) 가슴 늘리기, 팔 교차 어깨 늘리기 약 20초, 어깨 돌리기, 어깨뼈 모으기, 서서 옆구리 늘리기(골반은 가운데에 두고 3~5초씩 3~5번)는 Cleveland Clinic. 목 옆으로 기울이기·턱 당기기·몸통 회전·골반 기울이기·벽에 붙어 서서 하는 동작은 영국 NHS 병원들의 물리치료 안내문(통증 없는 범위에서)과 AAOS OrthoInfo 척추 프로그램.
- **기상 직후 안전:** 일어난 직후에는 깊게 숙이는 동작과 바닥 동작, 목을 뒤로 젖히거나 크게 돌리는 동작을 넣지 않았다. 가볍게 시작하라는 한국 건강 기사들과 일어난 직후 허리를 깊게 숙이는 것을 피하라는 척추 연구자들의 의견(다만 근거가 논쟁 중이라 보수적으로 반영)을 따랐다. 어지러우면 벽이나 침대를 잡고, 아프거나 저리면 멈추라는 안내는 코칭 프롬프트가 첫 안내에서 한 번 말한다.
- **확인하지 못한 것:** Mayo Clinic 페이지는 접근이 막혀 검색 요약으로만 확인했다. Harvard Health의 전체 동작 목록은 유료 보고서라 읽지 못했다. 한국 정형외과학회 같은 국내 공식 지침과 의사 개인이 만든 기상 루틴은 확인하지 못했다.
- **출시 전:** 의학적 조언이 아니다. 디스크·협착증·최근 부상이 있는 사용자에 대한 안내와 동작은 의사나 물리치료사의 검토를 받는 것을 권한다.

### B13. 오늘의 포춘 (새 아이디어 11/06)
- **AI가 한 일:** `tasks.fortune.compose_fortune`(두 스타일), 읽기 세션(`build_setup("fortune", …)`). 실제 모델로 예시 입력 8개를 두 스타일로 만들어 확인했고, 읽기 세션은 12번 중 12번 문장을 한 글자도 바꾸지 않고 읽었다(다시 읽어 달라는 요청 포함).
- **앱이 할 일:** 밤의 '내일의 한 마디'를 기기에서 받아쓴 글(`VoiceTranscriber`)을 목소리 업로드와 함께 서버에 보낸다. 포춘 단계에서 포춘 읽기 Live를 열고, **AI 음성이 끝나면(turnComplete) 앱이 다음 단계(콘텐츠)로 넘어간다.** 이 세션에서 AI는 함수를 부르지 않는다(말하면서 함수를 부르는 방식이 불안정해서).
- **서버가 할 일(요청 4가지):**
  1. 한마디 받아쓴 글을 저장할 칸과, 그것을 받는 방법(목소리 업로드 요청에 함께 보내거나 별도 필드).
  2. 아침 세션을 만들 때 `compose_fortune(한마디, llm, style, tone, today, diary_text=한 줄 일기)`를 부르고 결과 문장을 세션에 저장해 앱에 내려준다. 한마디가 없는 날은 한 줄 일기로 대신한다(함수가 처리).
  3. 포춘 읽기 토큰 엔드포인트(브리핑 토큰과 같은 형태): `build_setup("fortune", FortuneInput(persona, fortune_text, style))`와 `start_signal("fortune")`.
  4. 스타일 정책. 기본은 날짜에 따라 두 스타일을 번갈아 쓴다(`style=None`). 고정하거나 사용자 설정으로 고르게 하려면 정해 주면 `style`만 넘기면 된다.
- **팀이 정할 것:** 한마디를 건너뛴 날의 포춘(일기로 대신하는 현재 방식 / 건너뛰기), 포춘을 읽는 동안 앱 화면(문장 자막 표시 여부), 스타일 고정 여부.
- **참고:** 입력에 자해·자살 표현이 있으면 포춘 대신 담담한 문장(`source="safe"`)을 돌려준다. 위기 대응 자체는 밤·아침 대화의 안전 규칙이 맡는다. 짧은 시간에 호출이 몰리면 모델이 429를 돌려줄 수 있고, 그때도 기본 문장(`source="fallback"`)이 나온다.
- **끝난 기준:** 앱에서 한마디를 말한 다음 날 아침, 포춘 단계에서 AI가 문장을 읽고 콘텐츠로 넘어간다.

### B15. 아침 단계 연결 (아침 순서표 `morning_plan`)
- **무엇이 문제였나:** 아침이 여러 세션(대화)으로 나뉘어 있어, 세션마다 앞뒤에 무엇이 오는지 몰랐다(브리핑은 "콘텐츠를 시작할까요?"라고 묻는데 실제 다음은 한마디·포춘이었고, 포춘과 콘텐츠 세션은 앞 대화를 모른 채 곧바로 시작했다).
- **AI가 바꾼 것(`PROMPT_VERSION` 2026-11-13.1부터):** 서버가 넘긴 순서표대로 각 세션이 앞 단계에서 이어 말하고 다음 단계를 알린다.
  1. 새 기본 순서는 `BRIEFING → ONE_LINER(어젯밤 한마디) → COACHING(콘텐츠) → FORTUNE`이다. 포춘이 마지막이다. 서버가 순서표를 넘기지 않으면 예전 앱 순서(`BRIEFING → ONE_LINER → FORTUNE → COACHING`)로 동작한다.
  2. 브리핑 마무리 질문은 바로 다음 단계만 알린다. 예) "그럼 먼저 어젯밤 남긴 한마디부터 들어 볼까요?"
  3. 콘텐츠 세션의 첫 안내는 연결 문장으로 시작하고 인사를 반복하지 않는다. 예) "어젯밤 한마디 잘 들었죠? 이어서 스트레칭 시간이에요."
  4. 콘텐츠 뒤에 포춘이 이어지면 마무리 때 "마지막으로 오늘의 포춘을 들어 볼게요."로 넘기고, 작별 인사와 `end_session`은 하지 않는다. 코칭이 마지막 순서이면 하루를 응원하는 마무리를 한다.
  5. 포춘이 마지막이면 포춘을 읽은 뒤 "오늘 하루도 응원할게요, 좋은 하루 보내세요."로 끝낸다. 포춘 소개는 만든 재료를 밝힌다(예: "어젯밤 남긴 한마디로 만든 오늘의 포춘이에요.").
- **서버가 할 일:**
  - `from ai.tasks.morning_plan import morning_plan` → `morning_plan(has_one_liner, coaching)` 결과를 세션 시작 응답에 `morning_plan`으로 넣는다. `has_one_liner`는 어젯밤 한마디 녹음이 있으면 True, `coaching`은 코칭 쉬는 날·이미 다 한 날이면 False.
  - 같은 순서표를 `BriefingInput(plan=…)`, `CoachingInput(plan=…)`, `FortuneInput(plan=…)`에 넘긴다. 이미 다 한 날은 `BriefingInput(already_done=True)`.
  - 선택(안 해도 동작, 하면 문구가 정확해진다): `BriefingInput.one_liner_follows`(브리핑 뒤에 한마디를 틀 때 True), `FortuneInput.based_on`(`compose_fortune` 결과의 `used_input`: `voice`/`diary`/`none`).
- **앱이 할 일:** 서버가 준 `morning_plan` 순서대로 단계를 넘긴다. 순서표가 없으면 지금 순서 그대로다. 앱이 AI 말 뒤에 덧붙이던 연결 문구(`bridgeNote`, `listenFirstNote`, `ALREADY_DONE_NOTE`)는 AI가 직접 하므로 빼도 된다.
- **주의:** 콘텐츠 시간을 300초가 아닌 값으로 바꾸면 스트레칭 안내문의 "20초 머물러요" 같은 시간이 단계 시간과 맞지 않을 수 있다.

### B16. 밤 대화 흐름 변경 (11/10)
- **바뀐 흐름:** ① 오늘 일정 하나가 잘 끝났는지 묻는다(기분은 묻지 않음) → ② 하루가 어땠는지·뭘 했는지·기분이 어땠는지를 **한 질문**으로 열어 두고 자유롭게 말하게 한다(다른 일정을 하나씩 짚으며 묻지 않음) → ③ 사용자가 두 번 이상 말하면 **하루 요약을 되묻지 않고** 곧바로 내일 이야기로 넘어간다 → ④ 내일 첫 일정 언급, 내일 목표·특이사항, 필요하면 알람 시각 조정(기존 기능 그대로) → ⑤ "이제 내일의 나에게 남길 한마디를 녹음해 볼게요"라고 알리고 같은 응답에서 `end_session`. 한 줄 일기는 지금처럼 대화가 끝난 뒤 서버가 원문에서 추출한다(`extract_night_record`).
- **앱이 할 일:** `end_session`이 오면 AI 음성이 끝난 뒤 곧바로 '내일의 나에게 남길 한마디' 녹음 단계로 넘어간다(이미 하는 흐름이라면 변경 없음). 사용자가 먼저 "잘게요"로 끝내는 경우의 AI 말은 짧은 작별 인사만이다.
- **서버가 할 일:** 없음. 일정이 없는 날은 ①을 건너뛰고 ②의 열린 질문으로 시작한다. 내일 일정(`events_tomorrow`)을 넘기면 ④에서 가장 이른 일정을 짚는다.
- **주의:** 마무리 문장이 "편안한 밤 되세요" 같은 밤 인사로 끝나지 않는다(녹음 단계가 이어지므로).

### B17. 11/12 회의 반영 기능 (11/13~14)
- **AI가 바꾼 것:**
  1. **아침 앞부분**: 컨디션 질문 → 잠든 시각 질문 → 수면 시간 안내(30분 단위, AI가 직접 계산, 저장 안 함) → 브리핑은 한 번만. `report_bedtime` 같은 함수는 쓰지 않는다(함수를 부르면 AI가 브리핑을 반복하는 문제가 있었다).
  2. **콘텐츠 전체 건너뛰기**: "오늘은 다 건너뛸래"라고 하면 AI가 남은 단계마다 기존 `report_step_result`를 `skipped`로 부른다. 새 함수는 없다. 모든 단계가 판정되면 지금처럼 다음 순서로 넘어가고, 기상 성공·연속 달성에는 영향을 주지 않는다.
  3. **스트레칭 부위 고르기**: AI가 "오늘 어디가 불편하세요?"라고 묻고 `select_stretch_area(area)`를 부른다. 부위는 `FULL`(전신) / `NECK_SHOULDER`(목·어깨) / `ARM_WRIST`(팔·손목) / `BACK_HIP`(등·허리·골반) / `LEG`(다리). 각 루틴은 5단계(첫 단계 40초 + 65초 × 4 = 5분)이고 동작 23개로 만든다(`ai/tasks/stretch_sets.py`). 통증·부상을 말하면 그 부위는 하지 않고 전신을 가볍게 한다.
  4. **성격 4종 × 강도 3단계**: 성격 `KIND` 다정한 / `FRIENDLY` 친근한 / `STRICT` 엄격한 / `CHEERFUL` 유쾌한, 강도 1 약 / 2 중(기본) / 3 강. 강도는 브리핑에서 깨우는 말에만 적용된다. 자동 강도 올리기(`ai/personalization/intensity.py`): 계속 힘들게 일어나면 3번마다 한 단계 올리고(최대 3), 잘 일어나면 내려와 설정 강도로 돌아간다.
  5. **재기상 알림**: 앱이 8초 무응답으로 재기상 음악을 틀 때 AI에게 글자로 `(앱 알림: 재기상 음악 재생)` 한 줄을 보내면, AI가 이름을 불러 깨우는 한 문장을 하고 그 통화의 깨우는 말을 한 단계 강하게 한다(자동 강도를 끄면 올리지 않는다).
  6. **영어 수준**: `compose_content_steps(..., english_level="BEGINNER"|"INTERMEDIATE", context="오늘 이야기")`. 초급은 영어 3~6단어, 중급은 7~12단어. `context`에는 어젯밤 기록의 내일 목표·특이사항, 오늘 일정 한두 개를 넣는다.
  7. **콘텐츠 선택지 정리**: 새로 고를 수 있는 것은 스트레칭(`STRETCH`)과 영어(`ENGLISH`)다(`ai/tasks/content_steps.py`의 `OFFERED_CONTENT`). 명상·마사지·일본어·중국어는 새 알람에서 뺀다(이미 만든 알람은 그대로 동작). 퀴즈는 당분간 보류.
- **서버가 할 일:**
  - 순서표는 B15. 스트레칭 알람이면 `from ai.tasks.stretch_sets import all_area_steps`의 5개 루틴을 세션 시작 응답에 넣고(앱이 받아 둠), `CoachingInput(stretch_routines=tuple((area, tuple(steps)) for area, steps in all_area_steps().items()))`로 넘긴다.
  - 앱이 부위를 고르면 그 세션의 단계(`RS_SESSION_STEP`)를 그 부위 루틴으로 바꾸는 API가 필요하다(예: `POST /morning/sessions/{id}/stretch-area {"area": "NECK_SHOULDER"}`). `compose_content_steps("EXERCISE", …)`는 지금처럼 전신 루틴을 돌려준다.
  - `Persona`에 `personality`, `intensity`, `auto_intensity`를 넘긴다(맨 뒤에 추가된 칸이라 앞의 4칸 순서는 그대로). 그날 강도는 `today_intensity`로 구해 `Persona.intensity`에 넣고 세션에 `APPLIED_INTENSITY`로 저장한다. 전략 결정(`decide_strategy`)의 `speech_style` 자리에는 성격 값을 넘겨도 그대로 동작한다.
  - 영어: `compose_content_steps`에 `english_level`과 `context`를 넘긴다.
- **앱이 할 일:**
  - 새 함수 `select_stretch_area {"area": …}`: 서버가 미리 준 5개 루틴 중 그 부위로 화면·타이머·단계 기록을 바꾼다. 이후 `report_step_result`의 `step_order`는 그 루틴의 번호다. 응답은 `{"result": "ok"}`.
  - 재기상 알림 한 줄(위 5번).
  - 스트레칭 동작 23개는 단계 이름 전체로 고른다. 누워서 14(누워서 기지개, 무릎 좌우로 넘기기, 무릎 가슴으로 당기기, 두 무릎 가슴으로 당기기, 턱 당기기, 고개 좌우로 돌리기, 주먹 쥐었다 펴기, 손목 돌리기, 골반 기울이기, 브릿지, 4자 스트레칭, 누워서 비틀기, 발목 당겼다 밀기, 허벅지 뒤 늘이기), 옆으로 누워서 2(팔 열어 가슴 펴기, 허벅지 앞 늘이기), 앉아서 5(목 옆으로 기울이기, 어깨 교차 당기기, 손목 앞쪽 늘이기, 손목 뒤쪽 늘이기, 팔꿈치 머리 뒤로 당기기), 서서 2(만세 기지개, 종아리 늘이기). 목 옆으로 기울이기·어깨 교차 당기기는 지금 서서 하는 동작을 앉은 자세로 바꾼다.
  - 설정 화면: 아침 콘텐츠 선택지는 스트레칭과 영어만. "말투"를 성격 + 강도 + "자동 강도 올리기"(기본 켜짐)로 바꾸고, 영어 수준(`BEGINNER` 기본 / `INTERMEDIATE`)을 추가한다. 굿나잇콜 끝에 앱이 읽는 고정 문구는 반말 설정이어도 존댓말이므로 어조에 맞춘다.
- **DB가 할 일:** AI 코드에서 쓰는 이름에 맞춰 칸을 추가한다.

| 테이블 | 칸 | 형식 | 기본 | 설명 |
|---|---|---|---|---|
| RS_USER_SETTING | PERSONALITY | VARCHAR(20) | 'KIND' | KIND / FRIENDLY / STRICT / CHEERFUL (SPEECH_STYLE을 대신함, FIRM → STRICT) |
| RS_USER_SETTING | INTENSITY | SMALLINT | 2 | 1 / 2 / 3 |
| RS_USER_SETTING | AUTO_INTENSITY | BOOLEAN | TRUE | 자동 강도 올리기 |
| RS_USER_SETTING | ENGLISH_LEVEL | VARCHAR(20) | 'BEGINNER' | BEGINNER / INTERMEDIATE |
| RS_MORNING_SESSION | APPLIED_INTENSITY | SMALLINT | NULL | 그날 실제로 쓴 강도 |
| RS_MORNING_SESSION | STRETCH_AREA | VARCHAR(20) | NULL | 그날 고른 스트레칭 부위(선택, 리포트용) |

  잠든 시각·수면 시간은 저장하지 않아 칸이 필요 없다. `CONTENT_OPTION`은 스트레칭 `STRETCH` 하나(부위는 통화 중에 고름), 영어 `ENGLISH`.

### B18. 자막 숫자 표기 (앱)
- **무엇이 문제인가:** AI는 "5초"를 "오 초"로 바르게 읽는데, Gemini 받아쓰기(`outputTranscription`)가 글자를 "오 초", "다섯 번", "열두 시"로 적어 자막이 어색하다. 같은 대화 안에서도 어떤 때는 "20초", 어떤 때는 "이십 초"로 적힌다. 음성은 고칠 것이 없다.
- **앱이 할 일:** 받아쓰기 조각을 이어 붙인 한 줄을 화면에 그리기 직전(`splitSubtitlePages` 전)에 변환을 한 번 적용한다. 조각마다 바꾸면 "열"과 "두 시"가 나뉘어 틀린다. 서버 저장용 원문(`transcriptJson`)은 그대로 둔다.
- **기준 구현:** `ai/tasks/subtitle_numbers.py`의 `subtitle_numbers(text)`(테스트 포함). 오 초 → 5초, 다섯 번 → 5번, 열두 시 → 12시, 여섯 시간 → 6시간, 십일 도 → 11도로 바꾸고, "한 번 더", "이 분들", "초록", "시작", "번호"처럼 수가 아닌 말은 그대로 둔다. 같은 규칙의 Kotlin 초안(컴파일은 해 보지 못했다):

```kotlin
object SubtitleNumbers {
    private val SINO_DIGIT = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
    private val NATIVE_ONES = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9)
    private val NATIVE_TENS = listOf("스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "열" to 10, "쉰" to 50)
    private const val NOT_WORD = "록콜대위명석야리착전움작장켜험절선원역호개"
    private const val SINO = "(?:[이삼사오육칠팔구]?백)?(?:[이삼사오육칠팔구]?십)?[일이삼사오육칠팔구]?"
    private const val NATIVE = "(?:열|스물|스무|서른|마흔|쉰)?(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉)?"
    private val SINO_RE = Regex("(?<![가-힣0-9])($SINO) ?(초|분|도|퍼센트)(?![$NOT_WORD])")
    private val NATIVE_RE = Regex("(?<![가-힣0-9])($NATIVE) ?(시간|시|번|세트|가지)(?![$NOT_WORD])")

    private fun sino(word: String): Int? {
        if (word.isEmpty()) return null
        var total = 0; var num = 0
        for (ch in word) when {
            ch in SINO_DIGIT -> num = SINO_DIGIT.getValue(ch)
            ch == '십' -> { total += (if (num == 0) 1 else num) * 10; num = 0 }
            ch == '백' -> { total += (if (num == 0) 1 else num) * 100; num = 0 }
            else -> return null
        }
        return total + num
    }

    private fun native(word: String): Int? {
        if (word.isEmpty()) return null
        for ((tens, v) in NATIVE_TENS) if (word.startsWith(tens)) {
            val rest = word.removePrefix(tens)
            return if (rest.isEmpty()) v else NATIVE_ONES[rest]?.let { v + it }
        }
        return NATIVE_ONES[word]
    }

    fun apply(text: String): String {
        val a = SINO_RE.replace(text) { m ->
            val (word, unit) = m.destructured
            val v = sino(word)
            if (v == null || (word.length == 1 && word in "이사구일" && (unit == "분" || unit == "도"))) m.value else "$v$unit"
        }
        return NATIVE_RE.replace(a) { m ->
            val (word, unit) = m.destructured
            val v = native(word)
            if (v == null || (word == "한" && unit != "시")) m.value else "$v$unit"
        }
    }
}
// 사용: splitSubtitlePages(SubtitleNumbers.apply(text))
```

### B19. 판정 보고 순서 검사 (앱, 스트레칭에서 화면이 먼저 넘어가는 문제)
- **증상:** 스트레칭에서 AI가 사용자 대답을 기다리지 않고 다음 동작으로 넘어간다. 같은 안내를 반복하거나, 마지막 동작을 안내하면서 마무리까지 말해 버리기도 한다.
- **원인(AI 쪽):** AI가 사용자가 답하기 전에 동작을 미리 판정해서 `report_step_result`를 보낸다. 한 응답에서 두 단계를 동시에 보내기도 한다. 프롬프트 문구, 함수 선언(`NON_BLOCKING`), 응답에 붙이는 표시와 문장, 모델 온도를 바꿔 가며 실제 모델로 시험했지만 어느 것도 확실히 고치지 못했다. 정상으로 끝난 비율이 대략 3분의 1~절반이다. 글자 입력으로 시험한 결과라 실제 음성에서는 다를 수 있다.
- **앱이 막아 줄 것:** AI가 잘못 보낸 보고 때문에 화면이 먼저 넘어가거나 서버에 잘못 기록되지 않게, `report_step_result`를 받아들일지 먼저 검사한다.
  - 받아들일 조건: ① 보고한 `step_order`가 지금 진행 중인 단계(아직 완료되지 않은 첫 단계)이고, ② 직전에 받아들인 보고 이후로 사용자의 말(`inputTranscription`)이 한 번 이상 있었다.
  - 조건에 안 맞는 보고는 서버 기록과 화면 갱신을 건너뛰고, AI에게는 평소처럼 `{"result":"ok"}`만 돌려준다. 에러로 답하면 AI가 이유를 설명하려고 또 말할 수 있어서 조용히 무시하는 편이 안전하다.
  - 예외 ① 전체 건너뛰기: AI가 남은 단계를 한꺼번에 `skipped`로 보낸다. 사용자 말이 있었다면 `verdict`가 `skipped`인 보고는 순서와 관계없이 받는다.
  - 예외 ② 마지막 단계: AI가 마무리 말을 끝낸 뒤 마지막 단계를 보고하므로 이 보고가 막히면 안 된다.
  - 스트레칭 부위를 고르면(`select_stretch_area`) 단계 목록이 새 루틴으로 바뀌므로 "현재 단계"도 새 목록 기준으로 계산한다.
- **코드 초안**(컴파일은 해 보지 못했다, `MorningChatScreen.kt`의 `report_step_result` 처리 부분):

```kotlin
// LiveClient: 사용자 말 받아쓰기(InputText 이벤트)가 들어올 때마다 userTurns를 1씩 올린다
// MorningChatScreen: 마지막으로 받아들인 보고 시점의 userTurns를 기억한다
var lastAcceptedUserTurns = -1

"report_step_result" -> {
    val order = call.args.optInt("step_order", -1)
    val verdict = call.args.optString("verdict")
    val current = items.firstOrNull { !it.done }?.stepOrder
    val userSpoke = client.userTurns > lastAcceptedUserTurns
    val accept = userSpoke && (order == current || verdict == "skipped")
    if (accept) {
        lastAcceptedUserTurns = client.userTurns
        // 기존 처리: (퀴즈 answer_correct 반응) → RespecApi.reportVerdict → applyStepView
    }
    JSONObject().put("result", "ok")      // 받아들이지 않은 경우에도 ok만 돌려준다
}
```
- **한계:** 이 검사는 화면이 잘못 넘어가는 것을 막아 준다. AI가 같은 말을 반복하거나 다음 동작 안내를 먼저 말해 버리는 것은 막지 못한다. 그쪽은 AI 쪽에서 계속 줄여 본다.

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
+   compose_content_steps(type, option, self.llm, today=today)     # 항상 300초로 구성. 결과의 option을 코칭 입력(content_option)에 넘긴다

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
5. **오늘의 포춘:** 서버가 순서표(`morning_plan`, B15)를 주면 그 순서(기본: 브리핑 → 전날 녹음 듣기 → 콘텐츠 → 포춘)로, 안 주면 예전 순서(브리핑 → 전날 녹음 듣기 → 포춘 → 콘텐츠)로 진행한다. 포춘 단계에서 앱이 포춘 읽기 Live(함수 호출 없음)를 열고, AI 음성이 끝나면 다음 단계로 넘어간다. 자세한 요청은 4장 B13.
6. **콘텐츠 3종:** 루틴 대신 알람에 붙은 콘텐츠 하나를 진행한다. 코칭 세션 입력에 `content_type`, `content_option`을 넘기고, 퀴즈는 `report_step_result`의 `answer_correct`로 O/X를 정한다. 자세한 내용은 4장 B14.
7. **`select_stretch_area(area)`:** 스트레칭 알람에서 AI가 부위를 정하면 부른다. 앱은 미리 받아 둔 5개 루틴 중 그 부위로 화면·타이머·단계 기록을 바꾸고 `{"result":"ok"}`를 돌려준다. 서버에는 세션 단계를 그 루틴으로 바꾸는 API가 필요하다(B17).
8. **재기상 알림:** 8초 무응답으로 재기상 음악을 틀 때 앱이 AI에게 글자로 `(앱 알림: 재기상 음악 재생)`을 보낸다(B17).
9. **자막 숫자 표기:** 앱이 자막을 그리기 전에 `SubtitleNumbers.apply`를 적용한다(B18).
10. **`report_step_result` 검사:** 앱이 지금 단계가 아니거나 사용자 말이 없던 보고는 기록하지 않고 `ok`만 돌려준다(B19).
