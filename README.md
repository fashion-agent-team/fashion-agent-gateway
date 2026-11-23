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
- 사용자 id를 헤더에 따로 싣지 않습니다. 확인한 `Authorization: Bearer` 토큰을 그대로 agent에 넘기고, agent·일정 서버가 같은 `JWT_SECRET`으로 직접 확인합니다(2026-11-23). 앱이 넣은 옛 `X-User-Id` 헤더는 지웁니다.
  배포할 때 agent·mcp-schedule에도 게이트웨이와 같은 `JWT_SECRET`을 넣고, 두 서버를 게이트웨이보다 먼저 올립니다.
- 사용량 제한: 사용자당 분당 30회, 사진이 든 요청(64KB 넘는 요청)은 분당 10회로 시작. 넘으면 429 `rate_limited`.
  앱이 켜질 때 보내는 미리 준비(`POST /api/v1/agent/warm`)는 이 횟수와 따로 사용자당 분당 6회.
- 로그인 없이 부르는 `/api/v1/auth/*`는 기기(`X-Forwarded-For` 맨 앞, 없으면 접속 주소)당 분당 10회, 계정을 만들 수 있는 `/auth/dev`·`/auth/login`은 모두 합쳐 분당 30회(`gateway.auth-per-minute`, `gateway.signups-per-minute`). 넘으면 429 `rate_limited`, `Retry-After: 60`.
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

테스트는 `./gradlew test`. 두 종류로 나눴습니다(시나리오 번호는 앱 레포 `specs/001-account-profile/spec.md`의 Given/When/Then).

| 종류 | 파일 | 하는 일 |
| --- | --- | --- |
| 컨트롤러 슬라이스 테스트 | `web/AuthControllerTest`, `web/AuthControllerProdTest`, `web/MeControllerTest`, `tts/TtsControllerTest`, `stt/SttControllerTest` | `@WebMvcTest`로 컨트롤러만 띄우고 서비스·저장소는 `@MockitoBean`. 입력 확인·응답 모양·상태 코드 |
| 통합 테스트 | `GatewayIntegrationTest` | 앱 전체 + 진짜 PostgreSQL(Testcontainers `postgres:17`). 로그인 → JWT → 필터 → DB → 뒤쪽 서비스까지 Mock 없이 |

통합 테스트는 Docker가 있으면 알아서 돕니다. Docker가 없으면 `TEST_DB_URL=jdbc:postgresql://localhost:5432/fashion`(`TEST_DB_USER`, `TEST_DB_PASSWORD`)으로 있는 DB에 붙이고, 둘 다 없으면 건너뜁니다.

## 할 일

- [x] Spring Boot 뼈대 + `/actuator/health` + Dockerfile
- [x] `/api/v1/auth/dev` + JWT 발급·검증 필터(토큰은 그대로 뒤로 넘김)
- [x] agent·mcp-schedule로 라우팅 (mcp-helper는 2026-11-11 뺌)
- [x] 회원정보 API (`account` 스키마), 푸시 토큰
- [x] Firebase 로그인 (`/api/v1/auth/login`, 구글 공개 키로 확인), 리프레시
- [x] 사용량 제한 (메모리)
- [ ] Firebase 프로젝트를 만들어 `FIREBASE_PROJECT_ID`를 넣고 앱의 Apple·구글 로그인으로 실제 확인
- [ ] 앱 레포 `docs/infra/docker-compose.yml`로 함께 띄우기(`.env.example`에 `SCHEDULE_URL=http://mcp-schedule:8092` 추가)
- [ ] FCM 보내기(일정 8시 알림 `POST /internal/push`, 가족 답장 알림)

## 공통 약속

모든 레포가 지키는 약속은 앱 레포에 있습니다. **코드보다 약속 문서를 먼저 고칩니다.**

- [서비스 간 약속](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/README.md): 공통 규칙, 포트, 인증(로그인 토큰), 오류 모양, DB 스키마, 옷 추천 흐름
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
