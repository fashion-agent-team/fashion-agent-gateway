# fashion-agent-gateway

**API Gateway · 로그인 · 회원정보** · 패션 에이전트(시각장애인용 음성 AI 옷 추천)의 한 부분입니다.

담당: 이진행

## 한눈에

- 포트: 8080, 엔드포인트: `http://localhost:8080`
- 기술: Spring Boot 4.0.9, Spring Cloud Gateway Server Web MVC(가상 스레드), JdbcClient + Flyway(PostgreSQL `account` 스키마), Nimbus JOSE(우리 JWT, Firebase ID 토큰 확인)
- Spring Security·Firebase Admin SDK·JPA·Redis는 쓰지 않았습니다. 필터 하나와 구글 공개 키(JWKS) 확인이면 충분했고(서비스 계정 키도 필요 없음), 게이트웨이가 한 대라 사용량 제한은 메모리로 셉니다. 여러 대로 늘릴 때 Redis로 옮깁니다.
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

입출력 JSON은 [OpenAPI](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)를 그대로 따릅니다.

## 이 레포가 지킬 것

- 앱이 부르는 곳은 이 게이트웨이(8080) 하나뿐입니다. 뒤쪽 서비스 포트는 밖에 열지 않습니다.
- JWT는 여기서만 만들고 확인합니다(HS256, `JWT_SECRET`). 클레임: `sub`(사용자 UUID), `provider`, `iat`, `exp`.
- 앱이 보낸 `X-User-Id`는 지우고, 확인한 사용자 id로 다시 붙여 뒤로 넘깁니다.
- 사용량 제한: 사용자당 분당 30회, 사진이 든 요청(64KB 넘는 요청)은 분당 10회로 시작. 넘으면 429 `rate_limited`.
- 리프레시 토큰은 한 번만 씁니다(DB에는 SHA-256만). 이미 쓴 토큰이 다시 오면 훔친 것으로 보고 그 사용자의 리프레시 토큰을 모두 끊습니다.
- `FIREBASE_PROJECT_ID`가 없으면 `/auth/login`은 503 `not_configured`, dev가 아니면 `/auth/dev`는 404.
- 뒤쪽 서비스가 꺼졌으면 502 `upstream_failed`, 30초 안에 답이 없으면 504 `upstream_timeout`.
- 오류는 `{"error": {"code", "message"}}` 모양.
- DB 스키마 `account`만 씁니다(`users`, `profiles`, `push_tokens`).

## 실행

```bash
# PostgreSQL이 localhost:5432/fashion에 있을 때 (스키마·표는 Flyway가 만듦)
SPRING_PROFILES_ACTIVE=dev SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... ./gradlew bootRun
curl -X POST localhost:8080/api/v1/auth/dev -H 'Content-Type: application/json' -d '{"deviceId":"sim-iphone-15"}'
```

| 환경 변수 | 기본값 |
| --- | --- |
| `JWT_SECRET` | dev는 비우면 켤 때마다 임시 값(다시 켜면 토큰 무효), prod는 꼭 필요(32바이트 이상) |
| `FIREBASE_PROJECT_ID` | 없음 → Apple·구글 로그인 꺼짐 |
| `AGENT_URL`, `SCHEDULE_URL` | `http://localhost:8081`, `:8092` |
| `SPRING_DATASOURCE_URL`·`_USERNAME`·`_PASSWORD` | `jdbc:postgresql://localhost:5432/fashion`, `fashion` |

테스트는 `./gradlew test`. DB가 필요한 흐름 테스트(로그인 → JWT → X-User-Id → 뒤쪽 서비스)는 `GATEWAY_TEST_DB_URL=jdbc:postgresql://localhost:5432/fashion`(`GATEWAY_TEST_DB_USER`, `GATEWAY_TEST_DB_PASSWORD`)이 있을 때만 돕니다.

## 할 일

- [x] Spring Boot 뼈대 + `/actuator/health` + Dockerfile
- [x] `/api/v1/auth/dev` + JWT 발급·검증 필터 + `X-User-Id` 붙이기
- [x] agent·mcp-schedule로 라우팅 (mcp-helper는 2026-11-11 뺌)
- [x] 회원정보 API (`account` 스키마), 푸시 토큰
- [x] Firebase 로그인 (`/api/v1/auth/login`, 구글 공개 키로 확인), 리프레시
- [x] 사용량 제한 (메모리)
- [ ] Firebase 프로젝트를 만들어 `FIREBASE_PROJECT_ID`를 넣고 앱의 Apple·구글 로그인으로 실제 확인
- [ ] 앱 레포 `docs/infra/docker-compose.yml`로 함께 띄우기(`.env.example`에 `SCHEDULE_URL=http://mcp-schedule:8092` 추가)
- [ ] FCM 보내기(일정 8시 알림 `POST /internal/push`, 가족 답장 알림)

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
