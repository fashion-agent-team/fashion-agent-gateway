-- fashion-agent-gateway: account 스키마 (specs/005-database/data-model.md 1절)
-- Flyway 설정: spring.flyway.schemas=account, default-schema=account, create-schemas=true
-- 그래서 이 파일은 스키마 이름을 붙이지 않고 account 안에서 실행됩니다.
-- 이 스키마 안의 표끼리만 FK를 둡니다. 다른 서비스는 user_id 값만 같이 씁니다(FK 없음).

-- 계정. id가 JWT sub이자 모든 서비스의 user_id
CREATE TABLE users (
  id             uuid                     PRIMARY KEY DEFAULT gen_random_uuid(),
  provider       varchar(10)              NOT NULL,   -- 처음 가입한 방법. 계정 연결(Apple↔구글)은 Firebase가 같은 firebase_uid로 묶음
  firebase_uid   varchar(128)             UNIQUE,     -- apple·google. dev면 null
  dev_device_id  varchar(100)             UNIQUE,     -- POST /api/v1/auth/dev 의 deviceId. dev에서만
  last_login_at  timestamp with time zone,
  created_at     timestamp with time zone NOT NULL DEFAULT now(),
  updated_at     timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT users_provider_check CHECK (provider IN ('apple', 'google', 'dev')),
  CONSTRAINT users_identity_check CHECK (
    (provider = 'dev' AND dev_device_id IS NOT NULL) OR
    (provider <> 'dev' AND firebase_uid IS NOT NULL))
);

-- 음성 온보딩 회원정보. 사용자당 1행, 항목마다 건너뛰기 가능(null)
-- agent_name이 있으면 온보딩을 마친 것으로 봄
CREATE TABLE profiles (
  user_id     uuid                     PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
  name        varchar(50),
  birth_date  date,
  gender      varchar(20),                    -- 말로 받은 그대로(정해진 목록 아님)
  job         varchar(50),
  height_cm   numeric(4,1),
  weight_kg   numeric(4,1),
  agent_name  varchar(30),
  created_at  timestamp with time zone NOT NULL DEFAULT now(),
  updated_at  timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT profiles_height_check CHECK (height_cm BETWEEN 50 AND 250),
  CONSTRAINT profiles_weight_check CHECK (weight_kg BETWEEN 10 AND 300),
  CONSTRAINT profiles_birth_check  CHECK (birth_date >= DATE '1900-01-01'),
  CONSTRAINT profiles_agent_name_not_blank CHECK (agent_name IS NULL OR length(trim(agent_name)) > 0)
);

-- FCM 토큰 (PUT /api/v1/me/push-token). 사용자 1 : 폰 N, 폰 1대는 주인 1명
CREATE TABLE push_tokens (
  id            bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       uuid                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token         text                     NOT NULL UNIQUE,   -- 같은 폰이 다른 계정으로 로그인하면 user_id만 바꿈
  platform      varchar(10)              NOT NULL,
  last_seen_at  timestamp with time zone NOT NULL DEFAULT now(),
  created_at    timestamp with time zone NOT NULL DEFAULT now(),
  updated_at    timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT push_tokens_platform_check CHECK (platform IN ('ios', 'android'))
);
CREATE INDEX push_tokens_user_idx ON push_tokens (user_id);

-- 리프레시 토큰 30일. 원문 대신 SHA-256 hex. 갱신할 때마다 새로 발급하고 쓴 것은 revoked_at
CREATE TABLE refresh_tokens (
  id          bigint                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     uuid                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash  char(64)                 NOT NULL UNIQUE,
  expires_at  timestamp with time zone NOT NULL,
  revoked_at  timestamp with time zone,
  created_at  timestamp with time zone NOT NULL DEFAULT now(),
  updated_at  timestamp with time zone NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
