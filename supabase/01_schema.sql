-- Re:Spec 최종 DB 스키마 (최종 DB 테이블 정의서 기준)
-- 새 Supabase 프로젝트의 SQL Editor에서 01 -> 02 -> 03 순서로 실행
-- 여러 번 실행해도 에러는 나지 않지만, 이미 만든 테이블의 구조는 바꾸지 않는다
-- (구조를 바꿀 때는 00_reset_all.sql로 지우고 다시 만들거나 별도 alter 파일을 쓴다)
--
-- 결정 요약
--  - 알람은 루틴 알람(ROUTINE) 또는 콘텐츠 알람(CONTENT) 중 하나
--  - 루틴: 프리셋 기본 시간(합계 10분)에서 알람마다 항목 시간을 30초 단위로 바꾸거나 항목을 뺄 수 있음
--          알람의 루틴 합계는 rs_app_config.routine_max_sec(기본 900초) 이하
--  - 콘텐츠: 고정 시간, 단계는 AI가 구성
--  - 밤 대화의 하루 한정 변경: 내일 알람 시각 변경, 내일 코칭 안 하기


-- 테이블을 읽거나 고치는 트리거 함수는 security definer로 만든다.
-- Auth 계정을 지우면 supabase_auth_admin 권한으로 CASCADE가 실행되는데, 이 역할에는 rs_ 테이블 권한이 없기 때문

-- 공통: updated_at 자동 갱신
create or replace function rs_set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;


-- 0. 앱 설정 (1행만)
create table if not exists rs_app_config (
  config_id             boolean primary key default true check (config_id),
  routine_max_sec       int not null default 900 check (routine_max_sec > 0),
  content_duration_sec  int check (content_duration_sec is null or content_duration_sec > 0),  -- 콘텐츠 고정 시간 (팀 확정 후)
  updated_at            timestamptz not null default now()
);
insert into rs_app_config default values on conflict (config_id) do nothing;

drop trigger if exists trg_app_config_updated on rs_app_config;
create trigger trg_app_config_updated before update on rs_app_config
  for each row execute function rs_set_updated_at();


-- 1. 사용자 정보 (Supabase Auth 계정과 1:1)
create table if not exists rs_user (
  user_id          uuid primary key references auth.users(id) on delete cascade,
  user_name        varchar(50) not null,
  birth_date       date not null,
  gender           varchar(10) not null default 'NONE'
                   check (gender in ('MALE', 'FEMALE', 'NONE')),
  onboarding_done  boolean not null default false,
  created_at       timestamptz not null default now()
);


-- 2. 사용자 설정
create table if not exists rs_user_setting (
  user_id              uuid primary key references rs_user(user_id) on delete cascade,
  user_call_name       varchar(30) not null,
  tone                 varchar(10) not null default 'FORMAL'
                       check (tone in ('FORMAL', 'CASUAL')),
  speech_style         varchar(20) not null default 'KIND'
                       check (speech_style in ('KIND', 'FRIENDLY', 'FIRM')),
  voice_type           varchar(20) not null default 'FEMALE'
                       check (voice_type in ('FEMALE', 'MALE', 'NEUTRAL')),
  sleep_alarm_enabled  boolean not null default true,
  sleep_time           time not null default '23:00',
  music_genre          varchar(30) not null
                       check (music_genre in ('POP', 'JAZZ', 'CLASSIC', 'DANCE', 'ACOUSTIC')),
  theme                varchar(10) not null default 'SYSTEM'
                       check (theme in ('LIGHT', 'DARK', 'SYSTEM')),
  app_language         varchar(10) not null default 'ko',
  updated_at           timestamptz not null default now()
);

drop trigger if exists trg_user_setting_updated on rs_user_setting;
create trigger trg_user_setting_updated before update on rs_user_setting
  for each row execute function rs_set_updated_at();


-- 4. 루틴 프리셋
create table if not exists rs_routine_template (
  template_id    uuid primary key default gen_random_uuid(),
  template_name  varchar(100) not null,
  target_desc    varchar(200),
  description    text,
  is_active      boolean not null default true,
  created_at     timestamptz not null default now()
);


-- 5. 프리셋 항목 (기본 시간은 30초 단위, 프리셋마다 합계 600초)
create table if not exists rs_routine_template_item (
  template_item_id      uuid primary key default gen_random_uuid(),
  template_id           uuid not null references rs_routine_template(template_id) on delete restrict,
  item_name             varchar(100) not null,
  item_order            int not null default 1 check (item_order >= 1),
  default_duration_sec  int not null check (default_duration_sec >= 30 and default_duration_sec % 30 = 0),
  response_type         varchar(10) not null default 'PLAY'
                        check (response_type in ('PLAY', 'QA', 'SPEECH')),
  script                text,
  created_at            timestamptz not null default now(),
  unique (template_id, item_order)
);


-- 3. 알람 (루틴 알람 또는 콘텐츠 알람 중 하나)
create table if not exists rs_alarm (
  alarm_id           uuid primary key default gen_random_uuid(),
  user_id            uuid not null references rs_user(user_id) on delete cascade,
  alarm_time         time not null,
  repeat_days        char(7) not null default '1111111' check (repeat_days ~ '^[01]{7}$'),
  label              varchar(20),
  alarm_mode         varchar(20) not null default 'ON_TIME'
                     check (alarm_mode in ('ON_TIME', 'BEFORE_10', 'BEFORE_30')),
  morning_call_type  varchar(10) not null check (morning_call_type in ('ROUTINE', 'CONTENT')),
  template_id        uuid references rs_routine_template(template_id) on delete restrict,
  content_type       varchar(20) check (content_type in ('MEDITATION', 'EXERCISE', 'LANGUAGE', 'QUIZ')),
  content_option     varchar(30),   -- 값 목록은 팀 확정 후 CHECK 추가
  enabled            boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint rs_alarm_type_fields check (
    (morning_call_type = 'ROUTINE' and template_id is not null
       and content_type is null and content_option is null)
    or
    (morning_call_type = 'CONTENT' and content_type is not null and template_id is null)
  )
);
create index if not exists idx_alarm_user on rs_alarm (user_id);

drop trigger if exists trg_alarm_updated on rs_alarm;
create trigger trg_alarm_updated before update on rs_alarm
  for each row execute function rs_set_updated_at();


-- 6. 알람별 루틴 설정 (사용자가 바꾼 항목만 한 줄씩, 루틴 알람에만)
--  duration_sec: 30초 단위. 0이면 그 알람에서 항목을 뺌
--  설정을 기본으로 되돌리면 행을 지운다 (프리셋 기본 시간으로 진행)
create table if not exists rs_alarm_routine_item (
  alarm_id          uuid not null references rs_alarm(alarm_id) on delete cascade,
  template_item_id  uuid not null references rs_routine_template_item(template_item_id) on delete restrict,
  user_id           uuid not null references rs_user(user_id) on delete cascade,
  duration_sec      int not null check (duration_sec >= 0 and duration_sec % 30 = 0),
  updated_at        timestamptz not null default now(),
  primary key (alarm_id, template_item_id)
);

drop trigger if exists trg_alarm_routine_item_updated on rs_alarm_routine_item;
create trigger trg_alarm_routine_item_updated before update on rs_alarm_routine_item
  for each row execute function rs_set_updated_at();

-- 알람의 실제 항목 시간: 바꾼 시간이 있으면 그 시간, 없으면 프리셋 기본 시간 (0이면 뺀 항목)
drop function if exists rs_alarm_routine_items(uuid);   -- 반환 칸이 바뀌어 다시 만든다
create function rs_alarm_routine_items(p_alarm_id uuid)
returns table (template_item_id uuid, item_order int, item_name varchar, default_duration_sec int,
               duration_sec int, response_type varchar, script text)
language sql stable as $$
  select i.template_item_id, i.item_order, i.item_name, i.default_duration_sec,
         coalesce(x.duration_sec, i.default_duration_sec), i.response_type, i.script
  from rs_alarm a
  join rs_routine_template_item i on i.template_id = a.template_id
  left join rs_alarm_routine_item x on x.alarm_id = a.alarm_id and x.template_item_id = i.template_item_id
  where a.alarm_id = p_alarm_id
  order by i.item_order
$$;

-- 알람의 실제 루틴 합계(초)
create or replace function rs_alarm_routine_total(p_alarm_id uuid) returns int
language sql stable as $$
  select coalesce(sum(duration_sec), 0)::int from rs_alarm_routine_items(p_alarm_id)
$$;

-- 넣기 전 확인: 루틴 알람인지, 알람 주인과 같은지, 알람 프리셋의 항목인지
create or replace function rs_alarm_routine_item_check() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  a record;
  item_template uuid;
begin
  select user_id, morning_call_type, template_id into a
  from rs_alarm where alarm_id = new.alarm_id;

  if a.morning_call_type is distinct from 'ROUTINE' then
    raise exception '루틴 알람에만 항목 설정을 할 수 있습니다';
  end if;
  if a.user_id is distinct from new.user_id then
    raise exception '항목 설정의 user_id가 알람의 user_id와 다릅니다';
  end if;
  select template_id into item_template
  from rs_routine_template_item where template_item_id = new.template_item_id;
  if item_template is distinct from a.template_id then
    raise exception '알람 프리셋에 없는 항목입니다';
  end if;
  return new;
end $$;

drop trigger if exists trg_alarm_routine_item_check on rs_alarm_routine_item;
create trigger trg_alarm_routine_item_check before insert or update on rs_alarm_routine_item
  for each row execute function rs_alarm_routine_item_check();

-- 바꾼 뒤 확인: 루틴 합계가 상한 이하인지, 켜진 항목이 하나 이상인지
--  커밋할 때 검사한다(deferred). 여러 문장으로 바꾸는 중간 상태에서는 막지 않는다
create or replace function rs_alarm_routine_total_check() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  target uuid;
  total int;
  max_sec int;
  enabled_count int;
begin
  if tg_op = 'DELETE' then
    target := old.alarm_id;
  else
    target := new.alarm_id;
  end if;

  if not exists (select 1 from rs_alarm where alarm_id = target and morning_call_type = 'ROUTINE') then
    return null;   -- 알람이 지워졌거나 콘텐츠 알람으로 바뀌는 중
  end if;

  total := rs_alarm_routine_total(target);
  select routine_max_sec into max_sec from rs_app_config;
  if total > max_sec then
    raise exception '루틴 합계 %초가 상한 %초를 넘습니다', total, max_sec;
  end if;

  select count(*) into enabled_count
  from rs_alarm_routine_items(target) where duration_sec > 0;
  if enabled_count = 0 then
    raise exception '루틴 항목을 모두 뺄 수는 없습니다';
  end if;
  return null;
end $$;

drop trigger if exists trg_alarm_routine_total_check on rs_alarm_routine_item;
create constraint trigger trg_alarm_routine_total_check after insert or update or delete on rs_alarm_routine_item
  deferrable initially deferred
  for each row execute function rs_alarm_routine_total_check();

-- 알람의 항목 설정 전체 교체 (서버 전용)
--  p_items: [{"template_item_id": "...", "duration_sec": 300}, ...] 화면에 보이는 최종 값
--  기본 시간과 같은 항목은 저장하지 않는다(행이 없으면 기본 시간)
--  넘기지 않은 항목도 기본 시간으로 돌아간다
drop function if exists rs_replace_alarm_routine_items(uuid, uuid, jsonb);
create function rs_replace_alarm_routine_items(p_user_id uuid, p_alarm_id uuid, p_items jsonb)
returns table (template_item_id uuid, item_order int, item_name varchar, default_duration_sec int,
               duration_sec int, response_type varchar, script text)
language plpgsql as $$
begin
  if not exists (select 1 from rs_alarm a where a.alarm_id = p_alarm_id and a.user_id = p_user_id) then
    raise exception '알람이 없습니다' using errcode = 'P0002';
  end if;
  if jsonb_typeof(p_items) is distinct from 'array' then
    raise exception 'p_items는 JSON 배열이어야 합니다';
  end if;

  delete from rs_alarm_routine_item x where x.alarm_id = p_alarm_id;

  insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
  select p_alarm_id, v.template_item_id, p_user_id, v.duration_sec
  from jsonb_to_recordset(p_items) as v(template_item_id uuid, duration_sec int)
  left join rs_routine_template_item i on i.template_item_id = v.template_item_id
  where i.default_duration_sec is distinct from v.duration_sec;

  return query select * from rs_alarm_routine_items(p_alarm_id);
end $$;

revoke execute on function rs_replace_alarm_routine_items(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function rs_replace_alarm_routine_items(uuid, uuid, jsonb) to service_role;

-- 알람의 프리셋이 바뀌거나 콘텐츠 알람으로 바뀌면 예전 항목 설정을 지운다
create or replace function rs_alarm_template_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.template_id is distinct from old.template_id then
    delete from rs_alarm_routine_item where alarm_id = new.alarm_id;
  end if;
  return new;
end $$;

drop trigger if exists trg_alarm_template_changed on rs_alarm;
create trigger trg_alarm_template_changed after update of template_id on rs_alarm
  for each row execute function rs_alarm_template_changed();


-- 7. 모닝 세션 (알람 1회 실행 기록, 그날 적용한 값은 스냅샷)
create table if not exists rs_morning_session (
  session_id         uuid primary key default gen_random_uuid(),
  user_id            uuid not null references rs_user(user_id) on delete cascade,
  alarm_id           uuid references rs_alarm(alarm_id) on delete set null,
  session_date       date not null default (now() at time zone 'Asia/Seoul')::date,
  morning_call_type  varchar(10) not null check (morning_call_type in ('ROUTINE', 'CONTENT')),
  template_id        uuid references rs_routine_template(template_id) on delete restrict,
  content_type       varchar(20) check (content_type in ('MEDITATION', 'EXERCISE', 'LANGUAGE', 'QUIZ')),
  content_option     varchar(30),
  base_wake_time     time not null,
  target_wake_time   time not null,
  coaching_skipped   boolean not null default false,   -- 밤 대화에서 '내일 코칭 안 하기'에 동의한 날
  planned_total_sec  int check (planned_total_sec is null or planned_total_sec >= 0),  -- 그날 단계 계획 시간 합계
  alarm_rang_at      timestamptz,                       -- 알람이 실제로 울린 시각 (반응 시간 계산용)
  first_response_at  timestamptz,
  rewake_count       smallint not null default 0 check (rewake_count >= 0),
  wake_success       boolean not null default false,
  session_end_at     timestamptz,
  unique (alarm_id, session_date),
  constraint rs_session_type_fields check (
    (morning_call_type = 'ROUTINE' and template_id is not null and content_type is null)
    or
    (morning_call_type = 'CONTENT' and content_type is not null and template_id is null)
  )
);
create index if not exists idx_session_user_date on rs_morning_session (user_id, session_date desc);


-- 8. 세션 진행 단계 (루틴 항목 또는 콘텐츠 단계)
create table if not exists rs_session_step (
  step_id               uuid primary key default gen_random_uuid(),
  user_id               uuid not null references rs_user(user_id) on delete cascade,
  session_id            uuid not null references rs_morning_session(session_id) on delete cascade,
  step_order            int not null check (step_order >= 1),
  step_name             varchar(100) not null,
  template_item_id      uuid references rs_routine_template_item(template_item_id) on delete restrict,
  planned_duration_sec  int check (planned_duration_sec is null or planned_duration_sec > 0),
  step_detail           jsonb,   -- 콘텐츠 단계 내용 (퀴즈 문제·정답, 동작 설명). 재연결 때 사용
  step_status           varchar(10) not null default 'PENDING'
                        check (step_status in ('PENDING', 'DONE', 'SKIPPED')),
  checked_at            timestamptz,
  ai_verdict            varchar(20)
                        check (ai_verdict in ('done', 'not_done', 'skipped', 'ambiguous', 'other')),
  unique (session_id, step_order),
  constraint rs_step_status_time check ((step_status = 'PENDING') = (checked_at is null))
);
create index if not exists idx_step_session on rs_session_step (session_id);

-- 단계와 세션이 서로 맞는지 확인
--  - 단계의 user_id가 세션의 user_id와 같아야 함
--  - 루틴 세션이면 template_item_id 필수, 그 항목은 세션 프리셋의 항목이어야 함
--  - 콘텐츠 세션이면 template_item_id는 비움
create or replace function rs_session_step_check() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  s record;
  item_template uuid;
begin
  select user_id, morning_call_type, template_id into s
  from rs_morning_session where session_id = new.session_id;

  if s.user_id is distinct from new.user_id then
    raise exception '단계의 user_id가 세션의 user_id와 다릅니다';
  end if;

  if s.morning_call_type = 'ROUTINE' then
    if new.template_item_id is null then
      raise exception '루틴 세션의 단계에는 template_item_id가 필요합니다';
    end if;
    select template_id into item_template
    from rs_routine_template_item where template_item_id = new.template_item_id;
    if item_template is distinct from s.template_id then
      raise exception '세션 프리셋에 없는 항목입니다';
    end if;
  elsif new.template_item_id is not null then
    raise exception '콘텐츠 세션의 단계에는 template_item_id를 넣지 않습니다';
  end if;

  return new;
end $$;

drop trigger if exists trg_session_step_check on rs_session_step;
create trigger trg_session_step_check before insert or update on rs_session_step
  for each row execute function rs_session_step_check();


-- 9. AI 생성 음악 (공용 캐시, 사용자·알람 FK 없음)
create table if not exists rs_ai_music (
  music_id      uuid primary key default gen_random_uuid(),
  music_type    varchar(10) not null check (music_type in ('REWAKE', 'SLEEP')),
  genre         varchar(30) check (genre in ('POP', 'JAZZ', 'CLASSIC', 'DANCE', 'ACOUSTIC')),
  mood          varchar(30) check (mood in ('GOOD', 'NORMAL', 'TIRED', 'STRESSED')),
  music_prompt  text not null,
  music_path    varchar(500) not null,
  created_at    timestamptz not null default now(),
  constraint rs_music_type_fields check (
    (music_type = 'REWAKE' and genre is not null and mood is null)
    or
    (music_type = 'SLEEP' and mood is not null and genre is null)
  )
);
create index if not exists idx_music_pick on rs_ai_music (music_type, genre, mood);


-- 10. 수면 전 기록 (밤 대화가 끝나면 서버가 추출해 저장)
create table if not exists rs_night_record (
  night_record_id         uuid primary key default gen_random_uuid(),
  user_id                 uuid not null references rs_user(user_id) on delete cascade,
  record_date             date not null,   -- 트리거가 계산 (KST 현재 - 12시간의 날짜)
  apply_date              date not null,   -- 트리거가 계산 (record_date + 1)
  ai_summary              text,
  tomorrow_goal           varchar(200),
  tomorrow_notes          text[] not null default '{}',
  condition_label         varchar(20) check (condition_label in ('GOOD', 'NORMAL', 'TIRED', 'STRESSED')),
  condition_text          varchar(100),
  encouragement           text,
  insight                 text,
  voice_audio_path        varchar(500),
  override_alarm_id       uuid references rs_alarm(alarm_id) on delete set null,
  override_wake_time      time,
  override_skip_coaching  boolean not null default false,   -- 내일 하루 코칭(루틴 또는 콘텐츠) 안 하기
  diary_text              text,
  extract_status          varchar(10) not null default 'PENDING'
                          check (extract_status in ('PENDING', 'DONE', 'FAILED')),
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  unique (user_id, record_date),
  unique (user_id, apply_date),
  check (apply_date = record_date + 1),
  -- 음성 경로는 voice 버킷의 {user_id}/{record_date}.m4a 와 정확히 같아야 함
  constraint rs_night_voice_path check (
    voice_audio_path is null
    or voice_audio_path = user_id::text || '/' || to_char(record_date, 'YYYY-MM-DD') || '.m4a'
  ),
  -- 하루 한정 변경이 있으면 대상 알람도 필요
  constraint rs_night_override_alarm check (
    (override_wake_time is null and not override_skip_coaching) or override_alarm_id is not null
  )
);

-- 날짜 계산과 updated_at 갱신
--  insert: record_date가 없으면 KST 현재 - 12시간의 날짜
--  update: record_date는 그대로 두고 apply_date만 다시 맞춤
create or replace function rs_night_record_dates() returns trigger
language plpgsql as $$
begin
  if tg_op = 'INSERT' and new.record_date is null then
    new.record_date := ((now() at time zone 'Asia/Seoul') - interval '12 hours')::date;
  end if;
  new.apply_date := new.record_date + 1;
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists trg_night_record_dates on rs_night_record;
create trigger trg_night_record_dates before insert or update on rs_night_record
  for each row execute function rs_night_record_dates();

-- 알람을 지우면 그 알람을 가리키던 하루 한정 변경값도 비운다
-- (비우지 않으면 override_alarm_id가 NULL이 되면서 rs_night_override_alarm 규칙에 걸려 삭제가 막힘)
create or replace function rs_alarm_clear_overrides() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  update rs_night_record
  set override_wake_time = null, override_skip_coaching = false
  where override_alarm_id = old.alarm_id;
  return old;
end $$;

drop trigger if exists trg_alarm_clear_overrides on rs_alarm;
create trigger trg_alarm_clear_overrides before delete on rs_alarm
  for each row execute function rs_alarm_clear_overrides();


-- 11. 밤 대화 원문 (서버만 읽고 쓰기, 30일 후 비움)
create table if not exists rs_night_raw (
  night_record_id  uuid primary key references rs_night_record(night_record_id) on delete cascade,
  transcript       jsonb check (transcript is null or jsonb_typeof(transcript) = 'array'),  -- [{role, text, at}]
  audio_path       varchar(500),
  started_at       timestamptz,
  ended_at         timestamptz,
  expires_at       timestamptz not null default now() + interval '30 days',
  check (ended_at is null or started_at is null or ended_at >= started_at)
);
create index if not exists idx_night_raw_expires on rs_night_raw (expires_at);


-- 12. 일기 사진 (하루 대표 사진 1장)
create table if not exists rs_diary_photo (
  diary_photo_id  uuid primary key default gen_random_uuid(),
  user_id         uuid not null references rs_user(user_id) on delete cascade,
  diary_date      date not null,
  photo_path      varchar(500) not null,
  created_at      timestamptz not null default now(),
  unique (user_id, diary_date),
  constraint rs_diary_photo_path check (
    photo_path = user_id::text || '/' || to_char(diary_date, 'YYYY-MM-DD') || '.jpg'
  )
);


-- 13. 개인화 전략 로그 (아침 세션마다 1행, 서버만)
create table if not exists rs_strategy_log (
  log_id          uuid primary key default gen_random_uuid(),
  user_id         uuid not null references rs_user(user_id) on delete cascade,
  session_id      uuid references rs_morning_session(session_id) on delete set null,
  session_date    date not null,
  source          varchar(20) not null check (source in ('rule', 'bandit', 'default', 'fallback')),
  mode            varchar(10) not null check (mode in ('off', 'rule', 'bandit', 'ab')),
  strategy_id     varchar(30),
  strategy        jsonb,
  rule_ids        jsonb,
  context_key     varchar(50),
  propensity      numeric(6, 5) check (propensity is null or propensity between 0 and 1),
  allowed_arms    jsonb,
  explored        boolean,
  ab_group        varchar(20),
  ab_propensity   numeric(6, 5) check (ab_propensity is null or ab_propensity between 0 and 1),
  stats           jsonb,
  prompt_version  varchar(30),
  reward          numeric(6, 5) check (reward is null or reward between 0 and 1),
  error           text,
  created_at      timestamptz not null default now()
);
create index if not exists idx_strategy_log_user_date on rs_strategy_log (user_id, session_date desc);


-- 14. 개인화 Bandit 학습 상태 (서버만)
--  state_key = 'global' 이면 전체 합산 1행, 'user:{user_id}' 이면 사용자별
create table if not exists rs_bandit_state (
  state_key   varchar(50) primary key,
  user_id     uuid references rs_user(user_id) on delete cascade,
  state       jsonb not null,
  updated_at  timestamptz not null default now(),
  constraint rs_bandit_state_key check (
    (state_key = 'global' and user_id is null)
    or (user_id is not null and state_key = 'user:' || user_id::text)
  )
);

drop trigger if exists trg_bandit_state_updated on rs_bandit_state;
create trigger trg_bandit_state_updated before update on rs_bandit_state
  for each row execute function rs_set_updated_at();
