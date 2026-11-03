# fashion-agent-gateway

**API Gateway** · 패션 에이전트(시각장애인용 코디 도우미)의 한 부분입니다.

앱이 부르는 단 하나의 입구입니다. REST API로 앱의 요청을 받아 계정 확인 후 agent로 넘깁니다.

## 하는 일

- REST API 제공 (앱 ↔ 서버의 유일한 연결)
- **account**: 기존 회원인지 확인, 계정이 없으면 Apple·구글 로그인 연동
- **회원정보**: 이름·생년월일·성별·직업·키·몸무게 저장·조회 (항목마다 건너뛰기 가능)
- 앱 토큰 확인, 사용량 제한
- 사용자 말과 사진을 agent로 전달

## 지금 상태

없음. 앱의 온보딩(`fashion-agent`의 `lib/agent/onboarding_flow.dart`)이 회원정보를 폰에만 저장하고 있어서, 여기 API가 생기면 그쪽을 부르도록 바꿉니다.

## 할 일

- [ ] 언어·배포 위치 정하기
- [ ] REST 주소와 요청·응답 JSON, 오류 코드 표 만들기 (예: `POST /chat`, `POST /outfit-check`, `GET /health`, 회원 관련 주소)
- [ ] Apple·구글 로그인 붙이기 (앱 레포 `docs/ACCOUNT.md` 참고)
- [ ] 회원정보를 DB에 저장

## 전체 구조

```
application(fashion-agent) ─REST→ fashion-agent-gateway ─→ fashion-agent-agent ─MCP→ mcp-weather / mcp-schedule / mcp-clothes-state / mcp-face-check / mcp-closet
                                                                                              ↓
                                                                                              DB (계정·회원정보·일정·옷장)
```

## 브랜치

GitFlow를 따릅니다. `develop`에서 `feature/…` 브랜치를 따서 PR로 합칩니다. 규칙은 앱 레포의 `docs/GITFLOW.md`를 봅니다.

## 비밀 값

API 키와 DB 접속 정보는 레포에 넣지 않습니다. 배포 환경의 비밀 값(Secret)으로만 둡니다.
