-- 에이전트가 사용자를 부르는 말(호칭). 예: "진행 님". null이면 따로 부르지 않음.
ALTER TABLE profiles ADD COLUMN call_name varchar(30);
