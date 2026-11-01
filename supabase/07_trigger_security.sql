-- 계정 삭제(탈퇴) 오류 수정: 이미 01을 실행한 DB에 적용 (테이블과 데이터는 그대로)
--  Auth 계정을 지우면 supabase_auth_admin 권한으로 CASCADE가 실행되는데, 트리거 함수가 rs_ 테이블을 읽거나 고칠 권한이 없어
--  알람이 있는 사용자는 "Database error deleting user"로 삭제가 실패했다
--  테이블을 쓰는 트리거 함수를 security definer로 변경 (DB 주인 권한으로 실행)
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


alter function rs_alarm_routine_item_check() security definer set search_path = public;
alter function rs_alarm_routine_total_check() security definer set search_path = public;
alter function rs_alarm_template_changed() security definer set search_path = public;
alter function rs_session_step_check() security definer set search_path = public;
alter function rs_alarm_clear_overrides() security definer set search_path = public;
