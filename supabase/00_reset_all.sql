-- Re:Spec 최종 DB 전체 초기화 (테이블과 함수를 모두 지움, 되돌릴 수 없음)
-- 구조가 바뀌어서 01~03을 처음부터 다시 실행해야 할 때만 사용
-- 실행 전에 대시보드 왼쪽 위 프로젝트 이름이 본개발 프로젝트인지 꼭 확인
-- 지운 뒤 01 -> 02 -> 03 순서로 다시 실행
-- Storage 버킷과 파일, Authentication 계정은 지우지 않는다


drop table if exists
  rs_bandit_state,
  rs_strategy_log,
  rs_diary_photo,
  rs_night_raw,
  rs_night_record,
  rs_ai_music,
  rs_session_step,
  rs_morning_session,
  rs_alarm_routine_item,
  rs_user_routine_item,        -- 예전 구조
  rs_alarm,
  rs_routine_template_item,
  rs_routine_template,
  rs_user_setting,
  rs_user,
  rs_app_config
cascade;

drop function if exists
  rs_set_updated_at(),
  rs_alarm_routine_items(uuid),
  rs_alarm_routine_total(uuid),
  rs_alarm_routine_item_check(),
  rs_alarm_routine_total_check(),
  rs_alarm_template_changed(),
  rs_session_step_check(),
  rs_night_record_dates(),
  rs_alarm_clear_overrides()
cascade;

-- 남은 rs_ 테이블 확인 (0이어야 함)
select count(*) as rs_tables_left
from information_schema.tables
where table_schema = 'public' and table_name like 'rs\_%';
