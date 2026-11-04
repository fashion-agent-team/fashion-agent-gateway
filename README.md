# fashion-agent-gateway

**API Gateway · 로그인 · 회원정보** · 패션 에이전트(시각장애인용 음성 AI 옷 추천)의 한 부분입니다.

담당: (미정)

## 한눈에

- 포트: 8080, 엔드포인트: `http://localhost:8080`
- 기술: Spring Boot, Spring Cloud Gateway, Spring Security, Firebase Admin(ID 토큰 검증), Spring Data JPA, Redis(사용량 제한), PostgreSQL
- 상태 확인: `GET /actuator/health`

## API

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/auth/login` | Firebase ID 토큰 확인 → 우리 JWT 발급 (액세스 1시간, 리프레시 30일) |
| `POST /api/v1/auth/dev` | 개발용 로그인 (`APP_ENV=dev`에서만) |
| `POST /api/v1/auth/refresh` | 토큰 갱신 |
| `GET·PUT /api/v1/me/profile` | 회원정보 (모든 항목 null 가능, 온보딩 건너뛰기) |
| `PUT /api/v1/me/push-token` | FCM 토큰 등록 |
| `/api/v1/agent/** → agent:8081` | agent로 전달 |
| `/api/v1/schedules/** → mcp-schedule:8092` | 일정 조회 전달 |
| `/api/v1/me/contacts/**, /h/** → mcp-helper:8096` | 가족·친구 연락처, 가족 답장 웹 페이지 전달 |

입출력 JSON은 [OpenAPI](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)를 그대로 따릅니다.

## 이 레포가 지킬 것

- 앱이 부르는 곳은 이 게이트웨이(8080) 하나뿐입니다. 뒤쪽 서비스 포트는 밖에 열지 않습니다.
- JWT는 여기서만 만들고 확인합니다(HS256, `JWT_SECRET`). 클레임: `sub`(사용자 UUID), `provider`, `iat`, `exp`.
- 앱이 보낸 `X-User-Id`는 지우고, 확인한 사용자 id로 다시 붙여 뒤로 넘깁니다.
- 사용량 제한: 사용자당 분당 30회, 사진이 든 요청은 분당 10회로 시작. 넘으면 429 `rate_limited`.
- 오류는 `{"error": {"code", "message"}}` 모양.
- DB 스키마 `account`만 씁니다(`users`, `profiles`, `push_tokens`).

## 할 일

- [ ] Spring Boot 뼈대 + `/actuator/health` + Dockerfile (이미지 `ghcr.io/fashion-agent-team/fashion-agent-gateway:dev`)
- [ ] 앱 레포 `docs/infra/docker-compose.yml`로 postgres·redis와 같이 띄우기
- [ ] `/api/v1/auth/dev` + JWT 발급·검증 필터 + `X-User-Id` 붙이기
- [ ] agent로 라우팅 (`/api/v1/agent/**`)
- [ ] 회원정보 API (`account` 스키마)
- [ ] Firebase 로그인 (`/api/v1/auth/login`), 리프레시
- [ ] 사용량 제한 (Redis)

## 공통 약속

모든 레포가 지키는 약속은 앱 레포에 있습니다. **코드보다 약속 문서를 먼저 고칩니다.**

- [서비스 간 약속](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/README.md): 공통 규칙, 포트, 인증(`X-User-Id`), 오류 모양, DB 스키마, 옷 추천 흐름
- [MCP 도구 입출력](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/mcp-tools.md)
- [앱 ↔ 게이트웨이 API (OpenAPI)](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)
- [아키텍처와 기술 스택](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/ARCHITECTURE.md)

## 버전

| 항목 | 버전 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.9 |
| Spring Cloud | 2025.1.4 |
| Spring AI (BOM) | 2.0.2 |

## 브랜치

GitFlow를 따릅니다. `develop`에서 `feature/…` 브랜치를 따서 PR로 합칩니다. 규칙은 앱 레포의 `docs/GITFLOW.md`를 봅니다.

## 비밀 값

API 키와 DB 비밀번호는 레포에 넣지 않습니다. 로컬은 `.env`(커밋 금지), 배포는 서버 환경 변수로 둡니다. 목록은 앱 레포 `docs/infra/.env.example`에 있습니다.
