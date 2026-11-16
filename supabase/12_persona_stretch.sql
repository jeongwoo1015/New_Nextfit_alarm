-- 성격·강도·영어 수준, 스트레칭 부위(11/13): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  1. 사용자 설정: 말투(SPEECH_STYLE)를 성격(PERSONALITY) + 강도(INTENSITY)로 나누고, 자동 강도 올리기·영어 수준 추가
--     기존 말투는 성격으로 옮긴다(FIRM → STRICT). SPEECH_STYLE은 서버가 바뀔 때까지 남겨 둔다
--  2. 아침 세션: 그날 실제로 쓴 강도(APPLIED_INTENSITY), 고른 스트레칭 부위(STRETCH_AREA)
--  3. 통화 중 부위를 고르면 아직 판정하지 않은 단계만 새 루틴으로 바꾸는 함수 rs_replace_pending_steps (서버 전용)
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


-- 1. 사용자 설정
alter table rs_user_setting
  add column if not exists personality varchar(20) not null default 'KIND'
    check (personality in ('KIND', 'FRIENDLY', 'STRICT', 'CHEERFUL')),
  add column if not exists intensity smallint not null default 2 check (intensity between 1 and 3),
  add column if not exists auto_intensity boolean not null default true,
  add column if not exists english_level varchar(20) not null default 'BEGINNER'
    check (english_level in ('BEGINNER', 'INTERMEDIATE'));

update rs_user_setting
set personality = case speech_style when 'FIRM' then 'STRICT' else speech_style end;

comment on column rs_user_setting.personality is '넥스티 성격: KIND 다정한 / FRIENDLY 친근한 / STRICT 엄격한 / CHEERFUL 유쾌한';
comment on column rs_user_setting.intensity is '깨우는 말의 강도 1 약 / 2 중 / 3 강';
comment on column rs_user_setting.auto_intensity is '잘 못 일어나는 날이 이어지면 강도를 자동으로 올림';
comment on column rs_user_setting.english_level is '영어 따라 읽기 수준: BEGINNER 초급 / INTERMEDIATE 중급';
comment on column rs_user_setting.speech_style is '(11/13부터 PERSONALITY로 대체, 서버 전환 후 삭제 예정)';


-- 2. 아침 세션
alter table rs_morning_session
  add column if not exists applied_intensity smallint check (applied_intensity between 1 and 3),
  add column if not exists stretch_area varchar(20)
    check (stretch_area in ('FULL', 'NECK_SHOULDER', 'ARM_WRIST', 'BACK_HIP', 'LEG'));

comment on column rs_morning_session.applied_intensity is '그날 실제로 쓴 강도 (자동 강도 반영 후)';
comment on column rs_morning_session.stretch_area is '그날 고른 스트레칭 부위: FULL 전신 / NECK_SHOULDER 목·어깨 / ARM_WRIST 팔·손목 / BACK_HIP 등·허리·골반 / LEG 다리';


-- 3. 판정하지 않은 단계만 새 단계로 교체 (서버 전용)
--  p_steps: [{"step_name": "...", "planned_duration_sec": 60, "step_detail": {...}}, ...] 새로 진행할 단계
--  DONE·SKIPPED 단계는 그대로 두고, PENDING 단계를 지운 뒤 남은 단계 다음 번호부터 넣는다
--  (아무것도 판정하지 않았으면 1번부터)
drop function if exists rs_replace_pending_steps(uuid, uuid, jsonb);
create function rs_replace_pending_steps(p_user_id uuid, p_session_id uuid, p_steps jsonb)
returns setof rs_session_step
language plpgsql as $$
declare
  base int;
begin
  if not exists (select 1 from rs_morning_session s where s.session_id = p_session_id and s.user_id = p_user_id) then
    raise exception '세션이 없습니다' using errcode = 'P0002';
  end if;
  if jsonb_typeof(p_steps) is distinct from 'array' or jsonb_array_length(p_steps) = 0 then
    raise exception 'p_steps는 비어 있지 않은 JSON 배열이어야 합니다';
  end if;

  delete from rs_session_step x where x.session_id = p_session_id and x.step_status = 'PENDING';
  select coalesce(max(x.step_order), 0) into base from rs_session_step x where x.session_id = p_session_id;

  insert into rs_session_step (user_id, session_id, step_order, step_name, template_item_id, planned_duration_sec, step_detail)
  select p_user_id, p_session_id, base + e.n::int, v.step_name, v.template_item_id, v.planned_duration_sec, v.step_detail
  from jsonb_array_elements(p_steps) with ordinality as e(item, n),
       jsonb_to_record(e.item) as v(step_name varchar, template_item_id uuid, planned_duration_sec int, step_detail jsonb);

  return query select * from rs_session_step x where x.session_id = p_session_id order by x.step_order;
end $$;

revoke execute on function rs_replace_pending_steps(uuid, uuid, jsonb) from public, anon, authenticated;
grant execute on function rs_replace_pending_steps(uuid, uuid, jsonb) to service_role;


-- 확인 (7줄)
select table_name, column_name, data_type, column_default from information_schema.columns
where (table_name = 'rs_user_setting' and column_name in ('personality', 'intensity', 'auto_intensity', 'english_level'))
   or (table_name = 'rs_morning_session' and column_name in ('applied_intensity', 'stretch_area'))
union all
select 'function', routine_name, null, null from information_schema.routines where routine_name = 'rs_replace_pending_steps'
order by 1, 2;
