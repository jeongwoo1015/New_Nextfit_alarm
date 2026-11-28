# AI 파트 CONTRACTS (정의서 11/01 기준, 2026-11-14 갱신: 아침 순서표, 스트레칭 부위, 성격·강도, 영어 수준, 자막 숫자, 판정 보고 검사 추가 / 11/28 갱신: B31~B35 앱 요청서 11/27 반영)

## 1. AI 파트 파일별 기능

### 1-1. 서버·앱이 부르는 곳 (요약)

| 누가 | 언제 | 부르는 것 | 받는 것 |
|---|---|---|---|
| 서버 | 밤·브리핑·코칭 토큰 발급 | `live.config.build_setup(kind, 입력)`, `start_signal(kind)`, `token_request_body(setup)` | Live 설정(프롬프트·함수·음성), 시작 신호, 임시 토큰 요청 본문 |
| 앱 | Live 대화 중 | AI가 부르는 함수를 받아 처리 | `end_session`, `start_routine`, `report_step_result`, `adjust_step_time`, `select_stretch_area`(스트레칭 알람만), `reschedule_alarm`, `update_setting`, `report_bedtime`(브리핑만, B33) |
| 서버 | 통화 종료 후 | `tasks.night_record.extract_night_record(원문, llm)` | RS_NIGHT_RECORD 칸 (`override_skip_coaching` 포함) |
| 서버 | 통화 종료 후 | `tasks.routine_changes.parse_routine_changes(원문, 항목, llm)` | 항목별 `{template_item_id, duration_sec, scope}` 목록 |
| 서버 | 콘텐츠 알람 세션 생성 | `tasks.content_steps.compose_content_steps(종류, 세부, llm, today)` | 고정 5분 단계 목록과 오늘 쓴 세부(`option`). 스트레칭·퀴즈(인물·상식)·끝말잇기는 AI 없이 정해 둔 단계(`source="fixed"`), 영어는 AI가 구성하고 실패해도 기본 단계 |
| 서버 | 콘텐츠 탭에서 끝말잇기를 글자로 이어 할 때(사용자가 단어를 낼 때마다) | `tasks.wordchain.judge_wordchain(words, word, llm).to_dict()` | `{"valid": true, "next": "정원"}` / `{"valid": true, "next": null}` / `{"valid": false, "reason": "…"}` / 실패면 `{"status": "FAILED"}` (B26) |
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
| `wordchain.py` | 끝말잇기 글자 판정(11/25 앱 요청). 사용자 단어가 실제 단어(사전에 있는 일반 명사)인지 보고, 맞으면 넥스티가 이을 쉬운 단어 하나를 고른다. 끝 글자·두음법칙·중복·글자 수는 코드로도 확인하고 넥스티 단어도 코드로 검사한다 |
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
| `wake_sim.py`, `coach_sim.py` | 기상 볼륨·코칭 강도 가상 사용자 시뮬레이션 (효과의 근거는 아님). 옛 `sim.py`는 11/26 삭제 |

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
  1. **아침 앞부분**: 컨디션 질문 → 잠든 시각 질문 → 수면 시간 안내(30분 단위, AI가 직접 계산) → 브리핑은 한 번만. ~~`report_bedtime` 같은 함수는 쓰지 않는다~~ **11/28 정정: 잠든 시각은 `report_bedtime`으로 넘긴다. 브리핑 반복을 피하려고 다음 순서로 넘기는 함수와 같은 응답에서 부른다(B33).**
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
- **기준 구현:** 앱의 `SubtitleNumbers.kt`(아래 코드, 앱 시험 포함)가 기준이다. AI 쪽 파이썬 원본 `ai/tasks/subtitle_numbers.py`는 앱으로 옮긴 뒤 쓰이지 않아 11/26 삭제했다. 오 초 → 5초, 다섯 번 → 5번, 열두 시 → 12시, 여섯 시간 → 6시간처럼 바꾼다.

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

### B20. 스트레칭 부위 12개 개편 (11/18 AI 반영 완료, 서버·DB·앱 반영 대기)
- **AI가 바꾼 것:** 스트레칭을 "부위 12개 × 부위마다 정해진 4단계 루틴(45초씩, 합계 180초)"으로 바꿨다. AI는 동작을 고르지 않고 사용자의 말을 12개 부위 중 하나로 정해 `select_stretch_area(area)`만 부른다(`ai/tasks/stretch_sets.py`, `all_area_steps()`가 12개 루틴을 돌려준다). 그림은 `설계/스트레칭_부위별_루틴.png`, 동작 스크립트와 근거는 `설계/스트레칭_부위별_루틴_초안.md`.
- **부위 코드 12개:** `FULL`(전신) `NECK`(목) `SHOULDER`(어깨) `CHEST_BACK`(가슴·윗등) `WRIST_HAND`(손목·손) `ELBOW_ARM`(팔꿈치·위팔) `LOW_BACK`(허리) `PELVIS`(골반) `GLUTE`(엉덩이) `THIGH`(허벅지) `CALF`(종아리) `ANKLE_FOOT`(발목·발). 옛 5개 값(`NECK_SHOULDER`, `ARM_WRIST`, `BACK_HIP`, `LEG`)은 이미 만든 기록을 위해 허용 값에 남기고, 옛 값이 오면 `normalize_area()`가 가까운 새 부위로 바꿔 읽는다(NECK_SHOULDER→NECK, ARM_WRIST→WRIST_HAND, BACK_HIP→LOW_BACK, LEG→THIGH).
- **반영 전에 일어나는 일:** 서버·DB가 옛 5개만 받으면 ① 새 부위로 부위 변경 API가 거절되어 앱은 전신 루틴을 유지하는데 AI는 고른 부위 루틴으로 안내해 어긋나고, ② 스트레칭 단계 시간이 서버 기본 300초에 맞춰 늘어나(단계당 75초), ③ `backend/tests/test_ai_gateway.py`의 5개 루틴 확인 테스트가 실패한다.
- **서버가 할 일:**
  1. 부위 코드 12개 허용: `backend/domain/codes.py`의 `STRETCH_AREAS`를 12개로, 옛 5개는 허용만(`LEGACY_STRETCH_AREAS`). 요청 검증(`schemas.py`의 `StretchArea`)과 `morning_service.py`의 `select_stretch_area` 값 검사가 같은 목록을 쓰는지 확인.
  2. 스트레칭만 180초: 콘텐츠 종류별 시간이 필요하다. 스트레칭(`EXERCISE`)은 180초(AI가 이미 합계 180초로 줌, `ai.tasks.stretch_sets.TOTAL_SEC`), 영어·퀴즈는 300초 그대로. `planned_total_sec`도 함께 확인.
  3. 세션 시작 응답의 부위별 루틴이 5개에서 12개(루틴마다 4단계)로 늘고 단계 번호(`step_order`)는 1~4다. `STRETCH_AREA_LOCKED` 규칙(이미 판정한 단계가 있으면 부위 변경 불가)은 그대로.
  4. 테스트: 12개 목록과 부위별 4단계·45초·합계 180초 확인.
- **DB가 할 일:** `rs_morning_session.stretch_area`의 허용 값 검사에 새 코드 추가(칸 추가는 없다). 새 마이그레이션 예:

```sql
alter table rs_morning_session drop constraint if exists rs_morning_session_stretch_area_check;
alter table rs_morning_session add constraint rs_morning_session_stretch_area_check
  check (stretch_area in ('FULL','NECK','SHOULDER','CHEST_BACK','WRIST_HAND','ELBOW_ARM','LOW_BACK','PELVIS','GLUTE','THIGH','CALF','ANKLE_FOOT',
                          'NECK_SHOULDER','ARM_WRIST','BACK_HIP','LEG'));
```
  (제약 이름은 Postgres 기본 이름을 가정했다. `\d rs_morning_session`으로 확인.) 열 설명과 정의서의 값 목록도 12개로 바꾼다.
- **앱이 할 일:**
  1. 새 동작 11개 그림 + 새 자세 2개. 단계 이름 전체로 동작을 고르는 방식 그대로다. 앉아서(9): 목 앞으로 숙이기, 어깨 으쓱 내리기, 날개뼈 모으기, 가슴 펴기, 앉아서 상체 비틀기, 앉아서 4자 스트레칭, 앉아서 다리 뒤쪽 늘이기, 앉아서 발끝 당기기, 앉아서 발목 돌리기 / **네발로**(새 자세): 고양이 소 자세 / **무릎 꿇고**(새 자세): 아기 자세. 그림이 준비되기 전에는 모르는 이름이면 기본 자세를 보여 주고 멈추지 않게 한다.
  2. 부위 코드는 서버가 준 값을 그대로 전달(`RespecApi.kt`의 `selectStretchArea` 주석을 12개로). 부위 고르는 중 화면(`areaPending`)과 단계 표시가 5단계를 가정하고 있지 않은지 확인.
  3. 루틴은 4단계 × 45초이고, 한 동작 안내 끝에는 항상 "끝나면 '했어요'라고 말씀해 주세요."(반말은 "'했어'라고 말해 줘")가 붙는다.
- **확인 순서:** ① DB 허용 값 검사 → ② 서버 코드 목록·시간·테스트 → ③ 앱 그림 → ④ 실제 기기에서 "뒷목이 결려요", "허리가 뻐근해요", "없어요"로 확인.
- **한계:** 신규 동작 11개의 근거는 ◎ 7개(의료기관 원문 확인) · ○ 4개(검색된 의료기관·영국 NHS 안내지 요약으로만 확인: 앉아서 4자 스트레칭, 목 앞으로 숙이기, 어깨 으쓱 내리기, 앉아서 발목 돌리기, 초안 문서 3장)다. 원문을 전부 읽은 것은 아니다. 의학적 효과는 말하지 않고 가벼운 아침 몸풀기로만 쓰며 전문가 검토는 하지 않기로 한 기존 결정(11/12)을 따른다.

### B21. 말로 알람 미루기 (11/20 회의, 11/21 AI 반영, 11/24 루틴·콘텐츠 통화로 확대, 앱·서버 대기)
- **AI가 바꾼 것(11/24 확대는 B25):** 아침 브리핑 Live에서(11/24부터 루틴·콘텐츠 통화에서도) 사용자가 "10분만 더", "9시에 다시 울려 줘"처럼 **오늘만** 알람을 미루고 싶다고 분명히 말하면 새 함수 `reschedule_alarm`을 부른다. 인자는 `minutes_later`(지금부터 1~60분) 또는 `at_time`(오늘 `"HH:MM"`, 지금보다 뒤이고 60분 안) 중 하나만. 단순히 졸리다는 말은 미루는 요청이 아니고, 시간을 말하지 않으면 한 번만 되묻는다. 60분을 넘게 말하면 한도를 알리고 되묻는다.
- **호출 방식(`end_session`과 같다):** AI는 "알람을 10분 뒤에 다시 맞췄어요."를 **먼저 말한 뒤 그 응답의 맨 끝에서** 호출한다. 앱은 이 함수에 `toolResponse`를 보내지 않고, 안내 음성이 끝나면 연결을 닫는다(응답을 보내면 AI가 이어서 말해 브리핑이 시작될 수 있다).
- **~~하루~~ 알람마다 3번 한도(11/28 정정, B32):** 서버가 ~~오늘~~ 그 알람에서 이미 미룬 횟수를 `BriefingInput.snoozes_today`로 넘긴다. 3번 이상이면 함수 목록에서 `reschedule_alarm`이 빠지고, AI는 더 미룰 수 없다고만 알린다.
- **앱이 할 일:** `reschedule_alarm` 호출을 받으면 안내 음성이 끝날 때까지 기다렸다가 연결을 닫고 서버에 미루기를 요청한다. 인자 검증(둘 중 하나만, 1~60분)도 앱이 한 번 더 한다. ~~지금 울리는 알람은 사용자가 직접 끈다(AI가 그렇게 안내한다).~~ **11/28 정정: 앱이 안내 음성이 끝나면 통화를 저절로 끝내므로 AI는 직접 종료하라고 안내하지 않는다(B31).**
- **서버가 할 일:** 미루기 요청을 받으면 **오늘만** 1회 다시 울리는 알람을 만든다(예: `POST /morning/sessions/{id}/snooze {"minutes_later": 10}` 또는 `{"at_time": "09:00"}`). 세션의 `snooze_count`를 1 올리고, 미루기 응답은 기상 성공으로 세지 않는다. **11/24 결정: 미루면 언제든(어느 통화에서든, 확인 멘트를 마친 뒤여도) 그날 기상은 실패다. 규칙과 구현은 B25.** 하루 한도는 서버에서도 한 번 더 검사한다.
- **DB가 할 일:** `rs_morning_session.snooze_count smallint not null default 0`(오늘 말로 미룬 횟수). 선택: 다시 울릴 시각 기록용 `snoozed_until timestamptz`.
- **확인한 것:** 실제 모델로 6가지(분 지정·시각 지정·되묻기·60분 초과·한도 초과·졸리다만 말함) 시나리오와 점검 규칙(함수 호출 형식, 안내 후 호출 순서, 호출 없이 "맞췄어요"라고 말하지 않기)을 통과시켰다. 사람 목소리로 한 확인은 아직 없다.

### B22. 대화로 설정 바꾸기 (11/20 회의, 11/21 AI 반영 완료, 앱·서버 대기)
- **AI가 바꾼 것:** 굿나잇콜(밤)과 아침 브리핑에서 사용자가 말로 **AI 음악·AI 성격·AI 강도·기상 알람 볼륨**만 바꿀 수 있다(어조·호칭·알람 시각 등 다른 설정은 설정 화면을 안내). 새 함수 `update_setting(field, value, scope)`.

| field | value | scope |
|---|---|---|
| `music_genre` | POP / JAZZ / CLASSIC / DANCE / ACOUSTIC | 항상 ALWAYS |
| `personality` | KIND / FRIENDLY / STRICT / CHEERFUL | 항상 ALWAYS |
| `intensity` | 1 / 2 / 3 | 항상 ALWAYS |
| `wake_volume` | 1~5 | `TODAY` = 다음 알람 한 번만 그 단계로 시작, `ALWAYS` = 앞으로 그 단계보다 높게 시작하지 않는 상한 |

- **대화 규칙:** 바꿀 값을 한 번 말해 확인받고, 확인하면 **먼저 함수를 호출한 뒤** 바꿨다고 알린다(호출 없이 바꿨다고 말하지 않는다). 음악·성격·강도는 "다음 모닝콜부터 적용"이라고 알리고 지금 통화의 말투는 바꾸지 않는다. 기상 볼륨은 개인화가 정한 값이 있으면 현재 상태를 한 번만 알리되 설득하지 않고 사용자 결정을 따른다("내일은 4단계로"는 TODAY, "앞으로 ~보다 높게는 시작하지 마"는 ALWAYS, 분명하지 않을 때만 한 번 묻는다).
- **앱이 할 일:** 함수를 받으면 서버에 설정 변경을 요청하고 AI에는 평소처럼 `{"result":"ok"}`를 돌려준다(끝내는 함수가 아니라 통화는 이어진다). 설정 화면의 값도 같이 바뀌게 한다.
- **서버가 할 일:**
  1. `music_genre`·`personality`·`intensity`는 `RS_USER_SETTING`의 해당 칸을 바꾼다(성격 `PERSONALITY`, 강도 `INTENSITY`는 B17). 다음 모닝콜부터 적용. 허용 값 밖이면 거절하고 기록만 남긴다.
  2. `wake_volume` + `ALWAYS`는 사용자 상한 `max_start_level`로 저장하고, `TODAY`는 다음 알람 한 번의 시작 단계(`next_alarm_level`)로 저장한다(B23의 `UserWakePrefs`). 사용자가 바꾼 값은 개인화 추천보다 항상 우선한다.
  3. 입력: 밤·브리핑 입력의 `settings=SettingsView(music_genre, wake_start_level, wake_state, wake_max_level, wake_raise_from)`로 현재 값을 넘겨야 AI가 상태를 알리고 상대적인 말("한 단계 낮춰 줘")을 계산한다. `wake_state`는 `RECOMMENDED`(개인화가 정한 값) / `EXPLORING`(아직 맞는 단계를 찾는 중) / `USER`(사용자가 정한 값).
- **DB가 할 일:** `RS_USER_SETTING`에 기상 볼륨 상한(예: `WAKE_MAX_START_LEVEL smallint null`)과 다음 알람 한 번의 시작 단계(`WAKE_NEXT_LEVEL smallint null`, 사용 뒤 비움). 음악 장르 칸은 이미 있는 것을 쓴다.
- **확인한 것:** 실제 모델로 성격(밤)·음악·볼륨 상한·볼륨 일시 변경·지원하지 않는 설정·반말 설정 6가지 시나리오와 점검 규칙(확인 전 호출 금지, 허용 값만, 호출 없이 "바꿨어요" 금지)을 통과시켰다.

### B23. 기상 알람 볼륨 개인화와 코칭 강도 Bandit (11/20 회의 2·3, 11/21 AI 구현 완료, 서버·앱·DB 대기)
- **방향(11/20 회의 결론):** 기상 성공은 AI 대화가 아니라 **알람 소리**가 좌우한다(알람 → 전화 받기 → AI 첫 대답). 그래서 개인화를 둘로 나눈다. ① 기상 알람 **시작 볼륨 단계**(기상 성공으로 학습) ② 코칭 강도(단계 수행 비율로 학습). 둘은 서로 다른 Bandit이고 보상도 분리한다. 지금 앱은 알람이 1단계에서 시작해 10초마다 한 단계씩 5단계까지 오르므로, **시작 단계만** 사용자별로 정한다.
- **AI가 만든 것(`ai/personalization/wake_volume.py`, DB를 읽지 않는 순수 함수):**
  - 시작 단계 후보는 1단계·3단계(상한 3). 재기상 알람은 기상 알람보다 한 단계 큰 2~6단계(`rewake_level()`).
  - ~~상황 칸은 추정 수면 가능 시간 하나(짧음 6시간 미만 / 보통 / 모름, `sleep_bucket()`).~~ **11/26 정정: 상황을 나누지 않는다(`CONTEXT = "all"`). 사용자별 사다리 하나이며, 수면 시간은 학습에 쓰지 않고 서버가 사후 분석용으로만 기록한다(B28). `decide_start_level`의 `sleep_minutes`는 받기만 하고 무시한다.**
  - 학습(사다리): 시작 단계 k에서 w단계에 받았으면 k~w-1단계는 실패, w단계는 성공으로 센다(`WakeLadder.learn`). 받고도 AI 확인에 답하지 않았거나 재기상이 있었으면 w단계도 실패, 끝까지 받지 않았으면 시작~마지막이 모두 실패.
  - 선택: Thompson Sampling. 단계마다 성공 확률을 뽑아 0.7 이상인 가장 낮은 후보로 시작하고, 확실한 후보가 없으면 가장 좋아 보이는 값과 비슷한 후보 중 낮은 단계를 고른다. 전체 사용자 기록은 약한 prior로만 쓴다.
  - 사용자 우선: 일시 변경(`next_alarm_level`) > 지속 상한(`max_start_level`) > 추천. 추천값(`recommended`)과 적용값(`applied`)을 따로 기록하고 사용자가 바꾼 날도 학습한다.
  - 안전: 시작 단계는 3을 넘기지 않고(사용자가 직접 정한 값은 예외), **올리는 날은 밤 대화로 알리고 반대가 없을 때만 적용**한다. 밤 대화가 없었던 날은 올리지 않고 이전 단계를 유지한다(`hold_no_notice`). 중요한 날(`important_day=True`)은 탐색하지 않는다.
- **서버가 할 일(밤 대화 전 → 아침 → 아침 뒤):**
  1. 밤 대화 시작 전: `d = decide_start_level(personal, global_, sleep_minutes, prefs, previous_level, notified, important_day)`로 내일 시작 단계를 정한다. `personal`은 사용자별 사다리(`rs_bandit_state` 같은 저장 위치의 `WakeLadder.to_dict()`), `global_`은 전체 합산(`WakeLadder.merge`). 사용자가 대화로 정한 값은 `UserWakePrefs(max_start_level, next_alarm_level)`로 넘긴다. `notified`는 "AI가 올린다고 실제로 말했는가"다. 밤 대화 글에서 `raise_announced(AI 발화 글, d.applied)`로 확인한다(AI가 알림을 빠뜨리는 날이 실제 모델 시험에서 5번 중 1~2번 있었다). False면 같은 날 다시 `decide_start_level(..., notified=False)`를 불러 이전 단계를 유지한다. 올린다고 알린 뒤 사용자가 반대하면 AI가 `update_setting(wake_volume, 이전 단계, TODAY)`(지속이면 ALWAYS)를 부른다(B22). `d.raise_pending`이 True면 밤 입력의 `SettingsView(wake_start_level=d.applied, wake_raise_from=previous_level, wake_state=d.state)`를 채운다.
  2. 알람 예약: `d.applied`를 내일 기상 알람의 시작 단계로 앱에 전달한다. 재기상 알람은 `rewake_level(d.applied)`.
  3. 아침 뒤: 앱이 알려 준 값으로 `learn_wake(ladder, d.context_key, 시작 단계, 첫 반응 단계 또는 None)`를 부르고 저장한다(11/26: `confirmed`는 받지만 쓰지 않는다. 받은 단계 = 첫 알람에서 자발적으로 처음 반응한 단계이고 받기·미루기·끄기 모두 반응이다. 3번 미룬 뒤 자동 연결로 시작한 날은 부르지 않는다). 전략 로그는 `make_log(d, user_id, 날짜, prefs, previous_level).to_row()`를 저장한다(추천값·적용값·source·탐색 여부·선택 확률).
  4. 코칭 강도 Bandit: `ContextualBandit`의 기본 정책이 Thompson Sampling으로 바뀌었다(공개 API와 저장 형식 호환, 옛 상태는 그대로 읽는다). 보상은 `reward.coaching_reward(session)`(단계 수행 비율, 코칭 안 한 날은 None)로 바꾸고 기상 성공은 넣지 않는다. **11/25: 이 Bandit은 사용자별(`user:{id}`)로 학습하고 상황은 2칸(B27).**
- **앱이 할 일:** 서버가 준 시작 단계(1~5)로 알람 볼륨을 시작한다(지금은 1 고정). 사용자가 **전화를 받은 순간의 볼륨 단계**와 알람이 울리기 시작한 **시작 단계**, 재기상 여부를 서버에 보고한다(학습 입력이다).
- **DB가 할 일:** ① 사용자별·전체 사다리 저장(기존 Bandit 상태 테이블에 키 하나 추가하거나 JSON 칸) ② 세션에 `wake_start_level`, `wake_received_level`(없으면 NULL), 시작 단계의 결정 로그(`recommended_level`, `applied_level`, `source`, `explored`, `propensity`, `previous_level`) ③ 중요한 날 판단용 값이 필요하면 서버 쪽 기준(캘린더 일정 등)을 정한다.
- **효과 검증의 한계(중요):** `python -m ai.personalization.wake_sim`은 가상 사용자(설정용 4종과 검증용 5종(깊게 자는 극단·습관 변화·효과 없음·새 사용자·미루는 사람)을 나눔)로 알고리즘이 사용자별 단계를 찾아가는지만 본다. 가상 사용자의 반응은 우리가 정한 값이라 **효과의 증거가 아니다.** 실제 사용자로 기상률 20%p 차이를 확인하려면 한 쪽에 약 82일(양쪽 164일)이 필요해서 소수 실사용자로는 증명할 수 없다. 실사용자는 "실제 서버에서 학습과 선택이 작동한다"를 보이는 용도로만 쓴다.

### B24. 말투 유지·날씨 표기·영어 따라 말하기 (11/21, AI만 변경)
- **AI가 바꾼 것(서버·앱이 할 일 없음):** ① 사용자가 반말로 말하든 "못 들었어, 다시 설명해 줘"처럼 되묻든 설정 어조를 대화 끝까지 유지한다. ② 날씨 용어를 알아듣기 쉬운 말로 바꿔 읽는다(튼구름 → 구름 많음, 구름많음 → 구름 많음, 구름조금 → 구름 조금, 온흐림 → 흐림). ③ 영어 콘텐츠에서 사용자가 따라 말하면 "짧은 반응 → 방금 따라 한 문장을 한 번 더 → 다음 단계 안내" 순서로 말한다. ④ 일정 제목이 이상해도 일반 규칙으로만 다루고 제목을 지어내지 않는다.
- **서버가 확인할 것:** 날씨 값은 기상청식 원문을 그대로 넘겨도 된다(AI가 바꿔 읽는다). 영어 안내 문장은 앱 음소거 문구 기준 그대로다.

### B25. 어느 통화에서든 미루면 기상 실패 (11/24 시연 피드백, AI 반영 완료, 서버·앱 대기)
- **무슨 일이 있었나(11/23 밤 시연 기록):** ① 첫 알람에서 첫 대답이 "2분만 미뤄줘"였는데 서버가 그 대답 0.15초 뒤 **기상 성공**으로 판정했다. 미루기 요청은 30초 뒤에 도착해 `snooze_count`가 1이 됐지만 이미 내린 판정은 그대로였다. ② 두 번째 알람에서 사용자가 영어 콘텐츠 통화 중에 "늦춰 달라"고 말했는데, 그 통화에는 `reschedule_alarm`이 없어서 AI가 미루지 못했다(서버 기록 없음, 세 번째 알람 안 울림).
- **11/24 결정:** 미루면 **언제든** 기상 실패로 처리한다. 11/01 팀 확정("확인 멘트를 마친 시점에 한 번 판정하고 그 뒤의 스누즈는 판정을 바꾸지 않는다")은 이 결정으로 바뀐다.
- **AI가 바꾼 것:** `reschedule_alarm`을 브리핑뿐 아니라 **루틴·콘텐츠(코칭) 통화에도** 넣었다. 입력 `CoachingInput.snoozes_today`(새 필드)가 3 이상이면 코칭 통화에서도 함수가 빠진다. 단계 진행 중 미루기 요청이 오면 그 응답에서 `report_step_result`·`adjust_step_time`·`select_stretch_area`는 부르지 않는다(현재 단계는 끝내지 않은 채 남는다). 영어 따라 말하기·퀴즈 정답·끝말잇기 단어, "잠깐만", "다시 말해 줘"는 미루는 요청으로 보지 않는다. 포춘 통화는 함수를 부르지 않는 읽기 세션이라 넣지 않았고, 밤 통화에는 해당 없다.
- **서버가 할 일(가장 중요):**
  1. 판정 규칙을 바꾼다. 지금 `session_rules.judge_wake`는 `wake_checked_at` 시점에 한 번만 부르고 그 뒤를 반영하지 않는다. **미루기(`POST /morning/sessions/{id}/snooze`, 버튼·말 모두)를 받으면 `wake_checked_at` 전후와 상관없이 그 세션의 `wake_success`를 False로 바꾼다.** 이미 성공으로 저장됐으면 되돌린다(같은 날 여러 세션이면 그날 첫 세션의 판정도 포함).
  2. 같은 날 다시 울린 알람의 세션이 기상 성공이 되는지도 정한다. 권장: 그날 미룬 적이 한 번이라도 있으면 그날 기상은 실패로 센다(`snoozes_today > 0`).
  3. ~~기상 볼륨 학습 기록(`save_wake_result`의 `confirmed`)도 같이 False로 고친다.~~ **(11/26 정정: 이 항목은 취소됐다. 미루기는 볼륨 학습에 영향을 주지 않는다. 아래 B28)** 옛 설명: 이미 학습 입력으로 들어간 뒤라면 한 번만 수정되도록 한다(`WakeService`의 이미 확정된 기록 처리 참고). 실패를 성공으로 학습하면 볼륨 사다리가 잘못 올라간다.
  4. 코칭 입력을 만들 때 `snoozes_today`(오늘 버튼·말 합산)를 `CoachingInput.snoozes_today`로 넘긴다(브리핑 입력과 같은 값, 어댑터 5장).
- **앱이 할 일:** 지금 `reschedule_alarm` 처리는 브리핑 단계에서만 있다(`MorningChatScreen`의 `briefingEvent`). **루틴·콘텐츠 통화 단계에서도 같은 처리**를 한다: 함수를 받으면 `toolResponse` 없이 안내 음성이 끝날 때까지 기다렸다가 연결을 닫고 `POST /morning/sessions/{id}/snooze`를 보낸 뒤 응답의 `snoozed_until`에 알람을 한 번 다시 예약한다. 이때 이어서 열려던 다음 단계(한마디·콘텐츠·포춘)는 열지 않는다. 사용자가 다시 울린 알람을 받으면 `POST /morning/sessions`로 같은 세션을 이어 받는다(B21과 같음).
- **확인한 것:** 실제 모델로 4가지(영어 첫 질문에서 미루기, 영어 따라 말하기 한 번 한 뒤 미루기, 루틴 통화에서 미루기, 한도 초과)를 통과시켰다(`snooze_in_english`, `snooze_in_english_midway`, `snooze_in_routine`, `snooze_in_english_daily_limit`). 사람 목소리로 한 확인은 아직 없다. 서버 판정과 앱 처리는 AI 쪽에서 시험할 수 없어 위 요청대로 팀이 확인해야 한다.
- **확인 순서:** ① 서버: 미루기 요청 뒤 `wake_success`가 False인지, 학습 기록이 같이 바뀌는지 시험 ② 앱: 영어 통화 중 "10분 뒤에 다시 울려 줘"로 알람이 다시 예약되는지 ③ 실제 기기에서 첫 질문 미루기 → 두 번째 알람 콘텐츠 중 미루기 → 세 번째 알람이 울리는지(11/23 시연 순서 그대로).

### B26. 끝말잇기 글자 판정 (11/25 앱 요청, AI 반영 완료, 서버·앱 대기)
- **무엇인가:** 콘텐츠 탭에서 모닝콜 때 끝나지 않은 끝말잇기를 **글자로** 이어 하는 화면용이다. 실시간 음성(Live)이 아니라 서버가 사용자가 단어를 낼 때마다 한 번씩 AI(`ai/tasks/llm.py`의 `GeminiText`)를 부른다.
- **호출:** `from ai.tasks.wordchain import judge_wordchain` → `judge_wordchain(words, word, llm).to_dict()`
  - `words`: 지금까지 이어진 단어(순서대로, 시작 단어 포함). 예 `["기차", "차표"]`
  - `word`: 사용자가 이번에 낸 단어. 예 `"표정"`
  - `llm`: 다른 서버 함수와 같은 `GeminiText` (호출 한 번에 판정과 넥스티 단어를 같이 받는다)
- **출력(JSON):**

| 상황 | 결과 |
|---|---|
| 맞고 이을 단어가 있음 | `{"valid": true, "next": "정원"}` |
| 맞고 이을 단어가 없음(앱은 넥스티 패배로 처리) | `{"valid": true, "next": null}` |
| 틀림 | `{"valid": false, "reason": "사전에 없는 말이에요"}` (`reason`은 40자 이내 짧은 존댓말 한 문장, 화면에 그대로 보여도 된다) |
| **AI 호출 실패**(서버가 다시 시도하거나 오류로 돌려줄 때 쓰는 신호, 요청에 없던 값) | `{"status": "FAILED"}` |

- **AI가 보는 것:** 사용자 단어가 **사전에 있는 두 글자 이상의 일반 명사**인지. 뜻 없는 글자 나열, 동사·형용사·부사·감탄사, 줄임말·은어, 인명·지명·상표(고유명사)는 틀린 것으로 본다. 확실히 아닐 때만 틀림으로 하고 애매하면 통과시킨다(게임이 즐거워야 해서).
- **코드로도 확인하는 것(AI를 부르지 않고 거른다):** 한글만, 두 글자 이상, 이미 나온 단어, 앞 단어의 끝 글자로 시작(두음법칙 포함: 리→이, 라→나, 녀→여, 니→이, 로→노, 류→유 등). 어긋나면 `valid:false`와 이유(예: "'리' 또는 '이'(으)로 시작하는 단어를 말해 주세요")를 돌려준다. 앱이 먼저 확인하므로 보통 걸리지 않는다.
- **넥스티 단어:** 사용자 단어 끝 글자로 시작(두음법칙 허용)하는 쉬운 일상 명사 하나. 코드로 한 번 더 검사하고(한글, 두 글자 이상, 이미 나온 단어 아님, 시작 글자) 어긋나면 AI에게 다시 묻는다(최대 3번). 이을 단어가 없다고 하면 한 번 더 찾아 보게 한 뒤에도 없을 때만 `null`로 한다.
- **서버가 할 일:** ① 앱 요청 `{words, word}`를 받아 위 함수를 부르고 결과 JSON을 그대로 돌려준다. ② `{"status": "FAILED"}`가 오면 앱이 "잠시 후 다시 해 주세요"를 보여 줄 수 있게 오류(예: 503)로 바꿔 준다. 사용자 단어를 통과시키거나 넥스티 패배로 처리하지 않는다. ③ 호출은 한 번에 보통 1초 안팎이고 최악의 경우(재시도 포함) 몇 초까지 걸린다.
- **앱이 할 일:** 앱은 AI가 준 넥스티 단어도 규칙을 한 번 더 확인하고, 틀리면 앱 단어 목록에서 대신 고른다(요청대로). `{"status": "FAILED"}`(서버 오류)일 때의 화면을 정한다.
- **확인한 것:** 가짜 AI로 44가지(두음법칙, 규칙 위반은 AI를 안 부름, 넥스티 단어 재검사, 이을 단어 없음, 호출 실패)와 실제 모델로 약 80번의 판정을 확인했다. 정상 단어·틀린 단어(뜻 없는 말, 동사·형용사·부사·감탄사, 인명·지명)는 기대대로 갈렸고 호출은 보통 0.7~1.5초였다.
- **한계:** AI가 사전을 대신 판단하는 것이라 틀릴 수 있다. 실제로 "구글" 같은 상표가 통과했고, 시작 글자가 드문 경우(예: "시리즈" 뒤 "즈")에는 어려운 단어("즈음")가 나올 수 있다. 사람이 눈으로 확인하는 시험과 실제 사용자 시험은 아직 없다. 사용량 한도(HTTP 429)에 걸리면 `FAILED`가 된다(시험 중 연속 호출로 한 번 겪었다).

### B27. 코칭 강도를 사용자별로 학습 + 상황 2칸 (11/25 결정, AI 반영 완료, 서버 대기)
- **결정:** 개인화 두 개(기상 볼륨, 코칭 강도)는 결과·학습 데이터가 모두 따로이고, 코칭 강도도 **사용자별로** 학습한다. 지금 서버는 모든 사용자를 합친 `global` 상태 하나로 학습한다(`morning_service.BANDIT_KEY`). 성향이 섞인 가상 사용자 20명으로 보면 합산 학습은 표준만 쓸 때와 같았고(단계 수행 비율 0.661 대 0.661), 사용자별 학습은 0.753이었다(각자에게 맞는 전략을 아는 이론상 최고 0.808). 가상 사용자의 반응은 우리가 정한 가정이라 효과의 근거는 아니다.
- **AI가 바꾼 것:** 상황 칸 기본을 4칸(최근 기상 성공률 × 피곤)에서 **2칸(어젯밤 피곤 여부)**으로 줄였다(`bandit.CONTEXT_FEATURES = ("tired",)`, 칸 이름 `tired`/`ok`). 시뮬레이션(`ai/personalization/coach_sim.py`)에서 2칸이 4칸보다 평균 수행 비율이 0.010 높았고 기상 성공률 칸은 이득이 작았다. 저장된 Bandit 상태는 칸 구성(`features`)을 함께 담고 있어 옛 상태도 그대로 읽힌다.
- **서버가 할 일:**
  1. 코칭 강도 상태의 저장 키를 `global`에서 **`user:{user_id}`**로 바꾼다. `decide_strategy`에 넘기는 상태와 `learn_strategy` 뒤 저장(`save_bandit_state(key, state, user_id)`) 모두 같은 키를 쓴다. DB 정의서의 `rs_bandit_state` 키 규칙(`user:{id}`)과 서버 저장소가 이미 이 키를 받는다.
  2. 이미 쌓인 `global` 상태는 읽지 않는다(지우지 않아도 된다). 사용자마다 빈 상태에서 시작한다.
  3. 기상 볼륨 상태(`wake:user:{id}`, `wake:global`)와 섞지 않는다(키가 다르므로 그대로 두면 된다).
- **앱이 할 일:** 없음.
- **주의:** 사용자별 기록은 적어서 학습이 느리다(시뮬레이션에서 60일에 설정용 유형의 이론상 개선 폭 중 65%). 소수 실사용(7~14일)으로는 코칭 강도의 효과를 볼 수 없고 "사용자별 기록이 쌓이고 전략이 기록된다"만 확인한다.

### B28. 볼륨과 AI 강도의 보상 분리 (11/26 회의, AI 반영 완료, 서버 대기)
- **결정:** 볼륨은 "그날 첫 알람에서 사용자가 자발적으로 처음 반응한 단계"만 학습한다. 반응 뒤의 미루기·재기상·AI 확인 질문 답변은 볼륨 학습에 넣지 않는다(그것은 AI 깨우는 말 강도의 몫). 사용자가 3단계에서 미루기를 눌렀다면 볼륨에서는 3단계 반응 성공이다. B25의 "미루면 볼륨 학습도 실패" 부분은 이 결정으로 바뀐다(미루면 그날 기상 실패, 즉 지표 쪽 결정은 그대로).
- **AI가 바꾼 것:** `WakeLadder.learn`/`learn_wake`가 `confirmed`를 더 쓰지 않고 첫 반응 단계만 본다(받은 단계 w = 성공, 시작~w-1 = 실패, 끝까지 반응 없음 = 시작~마지막 모두 실패). 인자는 옛 호출이 깨지지 않도록 받기만 한다. 시뮬레이션의 snoozer 유형은 같은 아침을 겪은 normal과 학습 기록이 같아야 한다는 시험으로 바꿨다.
- **서버가 할 일:** (1) `wake_rules.confirmed`에서 미루기 조건(`snooze_count`, `snoozes_today`)을 뺀다(볼륨 쪽만, 기상 성공 판정과 별개). (2) 미루기를 받을 때 이미 학습한 볼륨 기록을 실패로 고치는 `WakeService.on_snooze`와 `correct_wake` 호출을 없앤다. (3) 앱이 보낸 반응 단계(`reaction_level`, `reaction_type` = answer/snooze/dismiss)를 `learn_wake`의 받은 단계로 넘긴다. (4) 3번 미룬 뒤 자동 연결로 시작한 날은 `learn_wake`를 부르지 않는다.
- **수면시간 context 제거(같은 날 회의 4-3):** 사용자별 사다리 하나만 쓴다. 서버는 `WakeInputs`의 수면시간을 학습 입력으로 쓰지 않아도 된다(넘겨도 AI가 무시). `rs_wake_log.context_key`는 이제 항상 `all`이고, 수면시간은 `prefs` 등 사후 분석용 칸에만 남긴다. 리포트 취침시간추이는 서버 몫이다.
- **앱이 할 일:** 없음(반응 단계 보고는 11/25 반영 완료).
- **서버 현재 상태(11/26 확인, new/feat/backend-service 3f9b0ca "미루기 때 받은 단계를 바로 확인 실패로 학습"):** 서버는 아직 11/24 B25 방식으로, 미루기를 받으면 미룬 순간의 단계를 `confirmed=False`(확인 실패)로 바로 학습하고, 이미 성공으로 학습한 날은 `on_snooze`·`correct_wake`로 실패로 되돌린다. 이 결정(B28)과 반대다.
  - AI 쪽 `learn_wake`는 `confirmed`를 무시하므로 서버가 `False`를 넘겨도 볼륨 학습은 반응 단계 성공으로 들어간다(시작~받은 단계 앞까지 실패, 받은 단계 성공).
  - 하지만 서버의 `WakeService.on_snooze`와 `correct_wake`(서버가 사다리 칸을 직접 고침)는 AI 함수를 거치지 않아, 남아 있으면 미룬 날의 성공 1을 실패로 옮긴다. 서버는 위 "서버가 할 일" (1)·(2)대로 미루기 조건과 되돌리기 로직을 지워야 한다. `rs_wake_log.confirmed` 칸도 미루기와 상관없이 기록되도록 맞춘다(기상 성공 지표는 B25 그대로).

### B29. 깨우는 말 강도는 프롬프트 밖에서만 정한다 (11/26, AI 반영 완료, 서버 변경 없음)
- **결정:** 강도는 서버가 통화를 시작할 때 기존 자동 강도 규칙(`intensity.today_intensity`, 최근 알람 3번 중 2번 힘들면 다음 알람부터 +1 등)으로만 정한다. 규칙은 바꾸지 않았다. 통화 중에는 강도를 바꾸지 않는다.
- **AI가 바꾼 것:** 프롬프트의 "재기상 알림 뒤 이 통화의 깨우는 말을 한 단계 강하게" 조건 규칙을 지웠다. 실제 모델 시험에서 6번 중 2번만 따랐고(11/26), 프롬프트와 서버 계산이 강도를 따로 바꾸면 충돌할 수 있어서다. 재기상 알림에는 이름을 부르며 깨우는 한 문장으로만 답하고 강도는 그대로다.
- **참고:** 개선안 4-5의 "당일 상태 기준 강도"는 반영하지 않았다(규칙 변경 없음).
- **서버·앱이 할 일:** 없음.

### B30. 밤에 말한 "오늘 챙길 일"을 아침 브리핑에서 리마인드 (11/26 실사용 피드백, AI 반영 완료, 서버·앱 변경 없음)
- **문제:** 밤 대화의 "내일 미리 챙겨야 할 일정이나 걱정되는 일이 있어요?"에 사용자가 답한 내용은 `tomorrow_notes`로 정확히 추출되고 서버도 아침 입력으로 넘겼다. 그런데 아침 브리핑 지시문이 일정은 캘린더에서만 말하게 하고 밤 특이사항은 "응원"하라고만 해서, 캘린더가 비면 "오늘 등록된 일정은 없어요"로 끝나고 빠졌다(실제 모델: 캘린더 없음 4번 중 1번, 짧은 브리핑 2번 중 0번 언급).
- **AI가 바꾼 것:** `reminders_block(night)`가 `tomorrow_notes`를 [오늘 챙길 일] 블록으로 따로 보여 준다. 브리핑 일정 단계(c)는 이것을 하나도 빼지 않고 리마인드로 말한다(기록에 시각이 있으면 시각과 함께, 기록에 없는 장소·시각·결과는 덧붙이지 않음). 캘린더가 비어도 "일정이 없다"로 끝내지 않고, 짧은 브리핑 전략에서도 빼지 않으며, 어젯밤 이어받기 단계(d)에서는 되풀이하지 않는다. `night_record_block(n, with_notes=True)`의 기본 동작은 그대로라 코칭 등 다른 프롬프트는 바뀌지 않는다.
- **확인:** 같은 밤 기록으로 실제 모델 10번 모두 시각과 함께 언급(캘린더 없음 4/4, 캘린더 있음 2/2, 짧은 브리핑 4/4).
- **서버·앱이 할 일:** 없음(`tomorrow_notes`는 이미 `NightView`로 전달 중).

### B31. 말로 미루기 뒤 "직접 종료" 안내 삭제 (11/27 앱 요청서 1번, 11/28 AI 반영 완료, 서버·앱 변경 없음)
- **문제:** 말로 미루면 AI가 "지금 울리는 알람은 직접 종료해 주세요"라고 안내했지만, 앱은 안내 음성이 끝나면 통화를 저절로 끝낸다.
- **AI가 바꾼 것:** 미루기 안내 문장을 "알람을 ○분 뒤에(○시 ○분에) 다시 맞췄어요."로 줄였다(`common.snooze_block`). `reschedule_alarm` 설명의 "직접 종료하도록 먼저 말한 뒤"를 지웠고, 시험 규칙도 "다시 맞췄다고 알리고 종료 안내는 하지 않음"으로 바꿨다.
- **확인:** 실제 모델 말로 미루기 7번 모두 통과(11/28).

### B32. 말로 미루기 한도를 하루 3번에서 알람마다 3번으로 (11/26 회의, 11/28 AI 반영 완료, 서버 반영 완료)
- **AI가 바꾼 것:** 상수 이름을 `SNOOZE_MAX_PER_DAY`에서 `SNOOZE_MAX_PER_ALARM`(=3)으로 바꿨다. 프롬프트 문구를 "오늘 이미 n번 미뤘다", "하루 최대 3번"에서 "이 알람은 이미 n번 미뤘다", "알람마다 최대 3번"으로 바꿨다. 한도에 닿으면 함수 목록에서 `reschedule_alarm`이 빠지는 방식은 그대로다.
- **입력 칸 이름:** `BriefingInput.snoozes_today`, `CoachingInput.snoozes_today`는 **이름을 바꾸지 않았다**(서버 수정 없이 맞추려고). 의미만 "그 알람에서 이미 미룬 횟수"로 바뀌었다. 서버는 지금처럼 그 알람의 미룬 횟수를 이 칸에 넣으면 된다.
- **서버·앱이 할 일:** 없음(서버는 11/27 커밋에서 알람마다 3번으로 바꿨다).

### B33. 모닝콜에서 말한 잠든 시각을 `report_bedtime`으로 넘김 (11/26 회의, 11/27 앱 요청서 6번, 11/28 AI 반영 완료)
- **함수:** `report_bedtime { time: "HH:MM" }`(24시간). 아침 브리핑 Live에만 있다(밤·코칭·포춘에는 없음). 미루기 한도와 상관없이 항상 들어 있다.
- **부르는 시점(중요):** 잠든 시각을 들은 그 응답에서는 부르지 않는다. 그 자리에서 부르면 함수 응답 뒤 AI가 브리핑을 한 번 더 하고 다음 순서로 넘기지 못했다(실제 모델 5번 중 4번). 그래서 **다음 순서로 넘기거나 통화를 끝내는 함수(`start_routine`·`end_session`·`reschedule_alarm`)를 부르는 응답에서 그 함수 바로 앞에 함께** 부른다. 즉 앱에는 "준비됐어요"에 대한 응답에서 `report_bedtime`과 `start_routine`이 같이 온다.
- **부르지 않는 경우:** 사용자가 잠든 시각을 모르겠다거나 기억이 안 난다고 한 날. 사용자가 시각을 고쳐 말하면 고친 값으로 부른다. 사용자가 다음 순서로 넘어가지 않고 통화를 그냥 끊으면 넘어오지 않는다.
- **앱이 할 일:** 지금처럼 `{"result":"ok"}`를 바로 돌려주고 서버에 저장한다(이미 연결됨). 같은 응답에 `start_routine`·`end_session`·`reschedule_alarm`이 함께 오므로 **`report_bedtime`을 먼저 처리하고** 세션을 닫아야 한다.
- **서버가 할 일:** `PUT /morning/sessions/{id}/bedtime`(이미 있음). 날짜 붙이기와 `avg_bed_clock` 덮어쓰기도 이미 반영됨.
- **확인:** 실제 모델 6번 모두 `"00:30"`을 넘기고 다음 순서로 넘어감. 6번 중 1번은 잠든 시각을 들은 응답에서 일찍 불러 함수 응답 뒤에 영어 한 문장이 섞였다. 모른다고 한 날 3번은 모두 부르지 않음.

### B34. 기상 성공률을 날 기준으로 (11/26 개선안 4-6, 11/27 서버 요청, 11/28 AI 반영 완료)
- **AI가 바꾼 것:** `user_model.aggregate`의 `wake_success_rate`를 세션 평균에서 **날 평균**으로 바꿨다. 그날 세션 중 하나라도 성공이면 그날은 성공, 성공한 날 ÷ 기록이 있는 날(`day_wake_success_rate`). 세션 하나하나의 성공 여부는 지금처럼 서버가 정해 넘긴다(리포트는 포춘까지 끝까지 마친 세션, `flow_completed_at`).
- **영향:** 리포트의 기간 합계·전체 누적·그래프 칸(`period_series`)이 모두 날 기준이 된다. 코칭 규칙 R1(기상 성공률이 낮으면)도 같은 값을 쓰므로 날 기준이 된다. 코칭 강도 Bandit은 상황 칸이 '피곤'만이라(B27) 영향이 없다. `n_sessions`는 그대로 세션 수다.
- **서버가 할 일:** 없음(같은 함수를 그대로 부르면 된다).

### B35. 목소리 매핑은 그대로 (11/27 앱 요청서 5번, 11/28 결정)
- 같은 모델·같은 방식으로 후보 13개를 들어 보고 지금 매핑을 유지하기로 했다: `FEMALE→Kore`, `MALE→Puck`, `NEUTRAL→Zephyr`(`live.config.VOICES`).
- 미리 듣기 샘플이 필요하면 AI 파트가 세 목소리로 짧은 wav를 만들어 줄 수 있다. 샘플 API(`GET /voices/sample`)는 서버 몫이다.

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
11. **`reschedule_alarm`(말로 미루기):** 브리핑과 루틴·콘텐츠 통화에서(11/24 확대, B25) AI가 안내를 먼저 말한 뒤 끝에서 부른다. 미루면 언제든 그날 기상은 실패로 센다(B25). 앱은 `toolResponse` 없이 안내 음성이 끝나면 연결을 닫고, 서버에 오늘만 1회 다시 울리는 알람을 요청한다(B21). 한도는 알람마다 3번이고 AI는 직접 종료하라고 안내하지 않는다(B31·B32).
12. **`update_setting`(대화로 설정 변경):** 밤·브리핑에서 AI가 확인을 받은 뒤 부른다. 앱은 서버 설정을 바꾸고 `{"result":"ok"}`를 돌려준다(B22).
13. **기상 알람 시작 볼륨:** 서버가 밤마다 `wake_volume.decide_start_level`로 내일 시작 단계를 정해 알람에 전달하고, 앱이 받은 단계를 보고하면 `learn_wake`로 학습한다(B23).
14. **스트레칭 부위 12개:** 서버·DB 허용 값과 180초, 앱 그림 11개가 필요하다(B20).
15. **끝말잇기 글자 판정:** 서버가 `{words, word}`로 `judge_wordchain`을 불러 결과 JSON을 앱에 그대로 돌려주고, AI 호출 실패(`FAILED`)는 오류로 바꿔 준다(B26).
16. **코칭 강도 사용자별 학습:** 서버가 코칭 강도 Bandit 상태를 `global` 대신 `user:{id}`로 읽고 저장한다. 상황은 2칸(B27).
17. **밤에 말한 오늘 챙길 일:** 아침 브리핑이 캘린더와 별개로 반드시 리마인드한다. 서버·앱 변경 없음(B30).
18. **`report_bedtime`(잠든 시각):** 브리핑에서 `start_routine`·`end_session`·`reschedule_alarm`과 같은 응답에 함께 온다. 앱은 먼저 서버에 저장하고 세션을 닫는다(B33).
19. **기상 성공률:** `user_model`의 `wake_success_rate`가 날 기준이 됐다. 서버 변경 없음(B34).
