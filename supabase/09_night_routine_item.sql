-- 밤 대화로 내일 하루만 루틴 항목 시간 바꾸기·빼기 (11/01 팀 결정): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  새 테이블 rs_night_routine_item, 함수 rs_night_routine_items / rs_replace_night_routine_items
--  알람을 지우거나 프리셋을 바꾸면, 밤 기록의 대상 알람이 바뀌면 내일만 바꾼 항목도 지운다
-- 01_schema.sql, 02_security.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


-- 10-1. 내일 하루만 바꾸는 루틴 항목 시간 (밤 대화로 "내일 스트레칭 줄여 줘", "내일 이불 정리 빼 줘")
--  대상 알람은 밤 기록의 override_alarm_id. 알람 설정(rs_alarm_routine_item) 위에 덮어쓰고 그날만 적용한다
--  duration_sec: 30초 단위. 0이면 내일만 그 항목을 뺌. 알람 설정과 같은 값은 저장하지 않는다
create table if not exists rs_night_routine_item (
  night_record_id   uuid not null references rs_night_record(night_record_id) on delete cascade,
  template_item_id  uuid not null references rs_routine_template_item(template_item_id) on delete restrict,
  user_id           uuid not null references rs_user(user_id) on delete cascade,
  duration_sec      int not null check (duration_sec >= 0 and duration_sec % 30 = 0),
  primary key (night_record_id, template_item_id)
);

-- 내일 실제로 진행할 항목: 알람 설정 시간에 내일만 바꾼 시간을 덮어씀 (대상 알람이 없으면 0행)
create or replace function rs_night_routine_items(p_night_record_id uuid)
returns table (template_item_id uuid, item_order int, item_name varchar, default_duration_sec int,
               alarm_duration_sec int, duration_sec int, response_type varchar, script text)
language sql stable as $$
  select a.template_item_id, a.item_order, a.item_name, a.default_duration_sec,
         a.duration_sec, coalesce(o.duration_sec, a.duration_sec), a.response_type, a.script
  from rs_night_record n
  cross join lateral rs_alarm_routine_items(n.override_alarm_id) a
  left join rs_night_routine_item o
    on o.night_record_id = n.night_record_id and o.template_item_id = a.template_item_id
  where n.night_record_id = p_night_record_id
  order by a.item_order
$$;

-- 넣기 전 확인: 밤 기록 주인과 같은지, 대상 알람이 있는 루틴 알람인지, 그 알람 프리셋의 항목인지
create or replace function rs_night_routine_item_check() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  n record;
  item_template uuid;
begin
  select r.user_id, r.override_alarm_id, a.morning_call_type, a.template_id into n
  from rs_night_record r left join rs_alarm a on a.alarm_id = r.override_alarm_id
  where r.night_record_id = new.night_record_id;

  if n.user_id is distinct from new.user_id then
    raise exception '항목 변경의 user_id가 밤 기록의 user_id와 다릅니다';
  end if;
  if n.override_alarm_id is null then
    raise exception '하루 한정 변경 대상 알람이 없습니다';
  end if;
  if n.morning_call_type is distinct from 'ROUTINE' then
    raise exception '루틴 알람에만 항목 변경을 할 수 있습니다';
  end if;
  select template_id into item_template
  from rs_routine_template_item where template_item_id = new.template_item_id;
  if item_template is distinct from n.template_id then
    raise exception '알람 프리셋에 없는 항목입니다';
  end if;
  return new;
end $$;

drop trigger if exists trg_night_routine_item_check on rs_night_routine_item;
create trigger trg_night_routine_item_check before insert or update on rs_night_routine_item
  for each row execute function rs_night_routine_item_check();

-- 커밋할 때 확인: 내일 루틴 합계가 상한 이하인지, 시간이 있는 항목이 하나 이상인지
create or replace function rs_night_routine_total_check() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  target uuid;
  total int;
  max_sec int;
  enabled_count int;
begin
  if tg_op = 'DELETE' then
    target := old.night_record_id;
  else
    target := new.night_record_id;
  end if;

  if not exists (select 1 from rs_night_routine_item where night_record_id = target) then
    return null;   -- 내일만 바꾼 값이 없으면 알람 설정 그대로 (알람 쪽에서 이미 검사)
  end if;

  select coalesce(sum(duration_sec), 0)::int, count(*) filter (where duration_sec > 0)
  into total, enabled_count
  from rs_night_routine_items(target);
  select routine_max_sec into max_sec from rs_app_config;
  if total > max_sec then
    raise exception '루틴 합계 %초가 상한 %초를 넘습니다', total, max_sec;
  end if;
  if enabled_count = 0 then
    raise exception '루틴 항목을 모두 뺄 수는 없습니다';
  end if;
  return null;
end $$;

drop trigger if exists trg_night_routine_total_check on rs_night_routine_item;
create constraint trigger trg_night_routine_total_check after insert or update or delete on rs_night_routine_item
  deferrable initially deferred
  for each row execute function rs_night_routine_total_check();

-- 밤 기록의 대상 알람이 바뀌거나 비워지면 내일만 바꾼 항목도 지운다
create or replace function rs_night_alarm_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.override_alarm_id is distinct from old.override_alarm_id then
    delete from rs_night_routine_item where night_record_id = new.night_record_id;
  end if;
  return new;
end $$;

drop trigger if exists trg_night_alarm_changed on rs_night_record;
create trigger trg_night_alarm_changed after update of override_alarm_id on rs_night_record
  for each row execute function rs_night_alarm_changed();

-- 내일만 바꾸는 항목 전체 교체 (서버 전용)
--  p_items: [{"template_item_id": "...", "duration_sec": 180}, ...]. 알람 설정과 같은 값과 넘기지 않은 항목은 알람 설정대로
drop function if exists rs_replace_night_routine_items(uuid, uuid, jsonb);
create function rs_replace_night_routine_items(p_user_id uuid, p_night_record_id uuid, p_items jsonb)
returns table (template_item_id uuid, item_order int, item_name varchar, default_duration_sec int,
               alarm_duration_sec int, duration_sec int, response_type varchar, script text)
language plpgsql as $$
begin
  if not exists (select 1 from rs_night_record r
                 where r.night_record_id = p_night_record_id and r.user_id = p_user_id) then
    raise exception '밤 기록이 없습니다' using errcode = 'P0002';
  end if;
  if jsonb_typeof(p_items) is distinct from 'array' then
    raise exception 'p_items는 JSON 배열이어야 합니다';
  end if;

  delete from rs_night_routine_item x where x.night_record_id = p_night_record_id;

  insert into rs_night_routine_item (night_record_id, template_item_id, user_id, duration_sec)
  select p_night_record_id, v.template_item_id, p_user_id, v.duration_sec
  from jsonb_to_recordset(p_items) as v(template_item_id uuid, duration_sec int)
  left join rs_night_routine_items(p_night_record_id) cur on cur.template_item_id = v.template_item_id
  where cur.alarm_duration_sec is distinct from v.duration_sec;

  return query select * from rs_night_routine_items(p_night_record_id);
end $$;

revoke execute on function rs_replace_night_routine_items(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function rs_replace_night_routine_items(uuid, uuid, jsonb) to service_role;


-- 기존 함수 수정: 알람 프리셋 변경·알람 삭제 때 내일만 바꾼 항목도 정리
create or replace function rs_alarm_template_changed() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.template_id is distinct from old.template_id then
    delete from rs_alarm_routine_item where alarm_id = new.alarm_id;
    delete from rs_night_routine_item   -- 이 알람을 대상으로 내일만 바꾼 항목도 무효
    where night_record_id in (select night_record_id from rs_night_record where override_alarm_id = new.alarm_id);
  end if;
  return new;
end $$;

create or replace function rs_alarm_clear_overrides() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  delete from rs_night_routine_item
  where night_record_id in (select night_record_id from rs_night_record where override_alarm_id = old.alarm_id);
  update rs_night_record
  set override_wake_time = null, override_skip_coaching = false
  where override_alarm_id = old.alarm_id;
  return old;
end $$;


-- 접근 권한: 본인 행만 (02_security.sql과 같음)
alter table rs_night_routine_item enable row level security;
drop policy if exists "own_rows" on rs_night_routine_item;
create policy "own_rows" on rs_night_routine_item for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);


-- 확인: rs_ 테이블 16개
select count(*) as tables from information_schema.tables where table_schema = 'public' and table_name like 'rs\_%';
