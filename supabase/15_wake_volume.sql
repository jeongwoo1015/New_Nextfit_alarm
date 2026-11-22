-- 기상 볼륨 개인화·말로 미루기·게임 기록(11/20 회의): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  1. RS_WAKE_LOG(신규): 날마다 기상 알람 시작 볼륨 결정(추천·적용 단계와 이유)과 아침 결과(시작 단계·받은 단계)
--     기상 알람 1~5단계, 재기상 알람은 기상보다 한 단계 큰 2~6단계라 받은 단계는 6까지 허용
--  2. RS_USER_SETTING: 사용자가 말이나 설정으로 정한 기상 볼륨. 상한(앞으로)과 다음 알람 한 번만(쓰고 나면 비움)
--     사용자가 정한 값이 개인화 추천보다 항상 우선한다
--  3. RS_BANDIT_STATE: 코칭 강도와 기상 볼륨을 따로 학습하므로 wake:global, wake:user:<id> 키도 허용
--  4. RS_MORNING_SESSION.SNOOZED_UNTIL: 미루기(버튼·말)로 다시 울릴 시각. 횟수는 기존 SNOOZE_COUNT 하나로 센다
--  5. RS_SESSION_STEP.SCORE: 게임 기록 = 제한 시간 안에 주고받은 횟수 (최고 기록 = 가장 큰 값)
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


-- 1. 기상 볼륨 기록
create table if not exists rs_wake_log (
  wake_log_id        uuid primary key default gen_random_uuid(),
  user_id            uuid not null references rs_user(user_id) on delete cascade,
  session_date       date not null,                     -- 알람이 울리는 아침 날짜
  session_id         uuid references rs_morning_session(session_id) on delete set null,
  context_key        varchar(50),                       -- 상황 칸 (예: 추정 수면 시간 짧음/보통/모름)
  recommended_level  smallint check (recommended_level between 1 and 5),
  applied_level      smallint check (applied_level between 1 and 5),
  source             varchar(20) check (source in ('bandit', 'user_today', 'user_max', 'hold_no_notice', 'important_day', 'default')),
  state              varchar(20) check (state in ('RECOMMENDED', 'EXPLORING', 'USER')),
  explored           boolean not null default false,
  propensity         numeric(6, 5) check (propensity is null or propensity between 0 and 1),
  previous_level     smallint check (previous_level between 1 and 5),
  prefs              jsonb,                             -- 결정할 때의 사용자 설정 {max_start_level, next_alarm_level}
  reason             text,
  start_level        smallint check (start_level between 1 and 5),      -- 아침 결과: 실제로 울리기 시작한 단계
  received_level     smallint check (received_level between 1 and 6),   -- 전화를 받은 단계 (못 받았으면 비움)
  confirmed          boolean,                           -- 받은 뒤 AI 확인 질문에 대답했는지
  learned_at         timestamptz,                       -- 학습에 반영한 시각 (두 번 반영하지 않게)
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (user_id, session_date),
  constraint rs_wake_log_received check (received_level is null or start_level is null or received_level >= start_level)
);

drop trigger if exists trg_wake_log_updated on rs_wake_log;
create trigger trg_wake_log_updated before update on rs_wake_log
  for each row execute function rs_set_updated_at();

alter table rs_wake_log enable row level security;   -- 서버만 읽고 쓴다 (정책 없음)

comment on table rs_wake_log is '기상 알람 시작 볼륨 결정과 아침 결과 (하루 1행). 학습은 서버가 RS_BANDIT_STATE의 wake 키에 저장';


-- 2. 사용자가 정한 기상 볼륨
alter table rs_user_setting
  add column if not exists wake_max_start_level smallint check (wake_max_start_level between 1 and 5),
  add column if not exists wake_next_level smallint check (wake_next_level between 1 and 5);

comment on column rs_user_setting.wake_max_start_level is '앞으로 이 단계보다 세게 시작하지 않음 (비우면 제한 없음)';
comment on column rs_user_setting.wake_next_level is '다음 알람 한 번만 이 단계로 시작 (쓰고 나면 비움)';


-- 3. 학습 상태 키
alter table rs_bandit_state drop constraint if exists rs_bandit_state_key;
alter table rs_bandit_state add constraint rs_bandit_state_key check (
  (state_key in ('global', 'wake:global') and user_id is null)
  or (user_id is not null and state_key in ('user:' || user_id::text, 'wake:user:' || user_id::text))
);


-- 4. 미루기
alter table rs_morning_session add column if not exists snoozed_until timestamptz;
comment on column rs_morning_session.snoozed_until is '미루기(버튼·말)로 다시 울릴 시각. 횟수는 SNOOZE_COUNT (하루 3번 한도는 서버가 검사)';


-- 5. 게임 기록
alter table rs_session_step add column if not exists score int check (score is null or score >= 0);
comment on column rs_session_step.score is '게임 기록: 제한 시간 안에 주고받은 횟수';


-- 확인 (7줄)
select table_name, column_name from information_schema.columns
where (table_name = 'rs_wake_log' and column_name in ('applied_level', 'received_level'))
   or (table_name = 'rs_user_setting' and column_name in ('wake_max_start_level', 'wake_next_level'))
   or (table_name = 'rs_morning_session' and column_name = 'snoozed_until')
   or (table_name = 'rs_session_step' and column_name = 'score')
union all
select 'rs_bandit_state', conname from pg_constraint where conname = 'rs_bandit_state_key'
order by 1, 2;
