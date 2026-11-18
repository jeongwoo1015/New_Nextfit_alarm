-- 굿나잇콜 알람 서버 저장(11/18, 방향 B): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  1. RS_ALARM에 ALARM_KIND(WAKE 모닝콜 / BEDTIME 굿나잇콜) 추가. 기존 알람은 모두 WAKE
--     굿나잇콜은 MORNING_CALL_TYPE·TEMPLATE_ID·CONTENT_*를 모두 비우고, ALARM_MODE는 ON_TIME만 (모닝콜 규칙은 그대로)
--  2. 굿나잇콜 알람으로는 아침 세션을 만들 수 없고, 밤 대화의 하루 한정 변경 대상이 될 수 없다
--  3. 모닝콜을 굿나잇콜로 바꾸면 그 알람의 항목 설정과, 그 알람을 대상으로 한 하루 한정 변경을 지운다
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


-- 1. 알람 종류
alter table rs_alarm
  add column if not exists alarm_kind varchar(10) not null default 'WAKE'
    constraint rs_alarm_kind check (alarm_kind in ('WAKE', 'BEDTIME'));
alter table rs_alarm alter column morning_call_type drop not null;

alter table rs_alarm drop constraint if exists rs_alarm_type_fields;
alter table rs_alarm add constraint rs_alarm_type_fields check (
  (alarm_kind = 'WAKE' and (
     (morning_call_type = 'ROUTINE' and template_id is not null
        and content_type is null and content_option is null)
     or
     (morning_call_type = 'CONTENT' and content_type is not null and template_id is null)))
  or
  (alarm_kind = 'BEDTIME' and morning_call_type is null and template_id is null
     and content_type is null and content_option is null and alarm_mode = 'ON_TIME')
);

comment on column rs_alarm.alarm_kind is 'WAKE 모닝콜 / BEDTIME 굿나잇콜. 굿나잇콜은 모닝콜 종류·프리셋·콘텐츠를 비우고 정각(ON_TIME)에만 울린다';


-- 2. 굿나잇콜 알람을 모닝콜 자리에 쓰지 못하게 막기
create or replace function rs_session_alarm_check() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.alarm_id is not null
     and exists (select 1 from rs_alarm where alarm_id = new.alarm_id and alarm_kind = 'BEDTIME') then
    raise exception '굿나잇콜 알람으로는 모닝콜 세션을 만들 수 없습니다';
  end if;
  return new;
end $$;

drop trigger if exists trg_session_alarm_check on rs_morning_session;
create trigger trg_session_alarm_check before insert or update of alarm_id on rs_morning_session
  for each row execute function rs_session_alarm_check();

create or replace function rs_night_override_alarm_check() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.override_alarm_id is not null
     and exists (select 1 from rs_alarm where alarm_id = new.override_alarm_id and alarm_kind = 'BEDTIME') then
    raise exception '굿나잇콜 알람은 하루 한정 변경 대상이 될 수 없습니다';
  end if;
  return new;
end $$;

drop trigger if exists trg_night_override_alarm_check on rs_night_record;
create trigger trg_night_override_alarm_check before insert or update of override_alarm_id on rs_night_record
  for each row execute function rs_night_override_alarm_check();


-- 3. 모닝콜을 굿나잇콜로 바꾸면 모닝콜용 설정을 지운다
create or replace function rs_alarm_kind_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.alarm_kind = 'BEDTIME' and old.alarm_kind is distinct from 'BEDTIME' then
    delete from rs_alarm_routine_item where alarm_id = new.alarm_id;
    delete from rs_night_routine_item
    where night_record_id in (select night_record_id from rs_night_record where override_alarm_id = new.alarm_id);
    update rs_night_record
    set override_alarm_id = null, override_wake_time = null, override_skip_coaching = false
    where override_alarm_id = new.alarm_id;
  end if;
  return new;
end $$;

drop trigger if exists trg_alarm_kind_changed on rs_alarm;
create trigger trg_alarm_kind_changed after update of alarm_kind on rs_alarm
  for each row execute function rs_alarm_kind_changed();


-- 확인 (5줄)
select 'rs_alarm' as table_name, column_name as name, column_default as value from information_schema.columns
where table_name = 'rs_alarm' and column_name = 'alarm_kind'
union all
select 'rs_alarm', 'morning_call_type NULL 허용', is_nullable from information_schema.columns
where table_name = 'rs_alarm' and column_name = 'morning_call_type'
union all
select 'trigger', tgname, null from pg_trigger
where tgname in ('trg_session_alarm_check', 'trg_night_override_alarm_check', 'trg_alarm_kind_changed')
order by 1, 2;
