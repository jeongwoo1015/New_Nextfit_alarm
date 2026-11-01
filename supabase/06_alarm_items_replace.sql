-- 11/01 추가 변경: 이미 01을 실행한 DB에 적용 (테이블과 데이터는 그대로)
--  1. 항목 조회 함수가 기본 시간(default_duration_sec)도 돌려줌
--  2. 루틴 합계 검사를 커밋 때 하도록 변경, 콘텐츠 알람으로 바꿀 때는 검사하지 않음
--  3. 항목 설정 전체 교체 함수 rs_replace_alarm_routine_items 추가 (서버 전용)
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


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

-- 바꾼 뒤 확인: 루틴 합계가 상한 이하인지, 켜진 항목이 하나 이상인지
--  커밋할 때 검사한다(deferred). 여러 문장으로 바꾸는 중간 상태에서는 막지 않는다
create or replace function rs_alarm_routine_total_check() returns trigger
language plpgsql as $$
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
