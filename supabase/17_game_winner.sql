-- 끝말잇기 승패(11/28 서버 요청): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  GAME_WINNER: 끝말잇기 한 판의 승패. USER = 사용자 승, NEXTY = 넥스티 승. 승패가 안 난 판은 비워 둔다
--  앱은 게임 기록 저장(PUT .../steps/{n}/score)에 승패를 같이 보내고, 서버는 넥스티를 이긴 횟수(user_wins)를 돌려준다
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


alter table rs_session_step
  add column if not exists game_winner varchar(5) check (game_winner in ('USER', 'NEXTY'));

comment on column rs_session_step.game_winner is '끝말잇기 승패: USER(사용자 승) / NEXTY(넥스티 승). 승패가 안 난 판은 비움';


-- 확인 (1줄)
select column_name, data_type, character_maximum_length, is_nullable
from information_schema.columns
where table_name = 'rs_session_step' and column_name = 'game_winner';
