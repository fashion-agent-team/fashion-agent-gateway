-- 정규화(2026-11-20, 강사님 피드백): 로그인 수단을 users에서 user_identities로 뺍니다.
-- 예전 users는 provider에 따라 firebase_uid나 dev_device_id 중 하나만 차 있었습니다(칸이 provider에 기대는 구조).
-- 이제 users는 계정 자체만, 로그인 수단은 (issuer, subject) 한 줄씩. 한 계정에 여러 수단을 붙일 수 있습니다.
CREATE TABLE user_identities (
  id                bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           uuid                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  issuer            varchar(10)              NOT NULL,   -- firebase(Apple·구글) | dev
  subject           varchar(128)             NOT NULL,   -- firebase uid 또는 POST /api/v1/auth/dev 의 deviceId
  sign_in_provider  varchar(10)              NOT NULL,   -- 마지막으로 쓴 방법 apple·google·dev. JWT provider 클레임
  created_at        timestamp with time zone NOT NULL DEFAULT now(),
  updated_at        timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT user_identities_issuer_check   CHECK (issuer IN ('firebase', 'dev')),
  CONSTRAINT user_identities_provider_check CHECK (sign_in_provider IN ('apple', 'google', 'dev')),
  CONSTRAINT user_identities_subject_uq     UNIQUE (issuer, subject)
);
CREATE INDEX user_identities_user_idx ON user_identities (user_id);

INSERT INTO user_identities (user_id, issuer, subject, sign_in_provider, created_at)
SELECT id,
       CASE WHEN provider = 'dev' THEN 'dev' ELSE 'firebase' END,
       CASE WHEN provider = 'dev' THEN dev_device_id ELSE firebase_uid END,
       provider,
       created_at
  FROM users;

ALTER TABLE users DROP CONSTRAINT users_identity_check;
ALTER TABLE users DROP CONSTRAINT users_provider_check;
ALTER TABLE users DROP COLUMN provider;
ALTER TABLE users DROP COLUMN firebase_uid;
ALTER TABLE users DROP COLUMN dev_device_id;
