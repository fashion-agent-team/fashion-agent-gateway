# 게이트웨이 부하 테스트 (k6)

앱이 부르는 단 하나의 입구인 게이트웨이가 사람이 몰렸을 때 버티는지, 분당 제한이 지켜지는지 잽니다.
도구는 [k6](https://grafana.com/docs/k6/latest/)입니다(`brew install k6`).

## 시나리오

| 이름 | 하는 일 | 보는 것 |
|---|---|---|
| health | 로그인 없이 `/actuator/health` | 게이트웨이 자체(톰캣·필터·DB 연결 확인)가 초당 몇 개를 견디는지 |
| profile | 로그인한 사용자 4명이 `/api/v1/me/profile`을 분당 20번씩 | JWT 확인 + DB 읽기 경로의 응답 시간 |
| ratelimit | 한 사용자가 한꺼번에 40번 | 분당 30번 제한(429)이 지켜지는지. 429가 10번 이상 나와야 통과 |
| turn | 대화 한 번(`/api/v1/agent/turn`). `TURN=1`일 때만 | 게이트웨이 → agent 중계. **실제 서버에서 켜면 GPT 비용이 듭니다** |

통과 기준(하나라도 넘으면 k6가 실패로 끝남): 오류 1% 미만, health p95 300ms·profile p95 500ms·turn p95 10초 이하,
분당 제한 429가 10번 이상.

## 세기(PROFILE)

- `smoke`(기본): 1분. health 초당 5번 + 사용자 4명. **실제 서버(젯슨)에는 이것만** 돌립니다.
- `stress`: 2분. health 초당 20 → 100 → 200 → 400. 대화는 초당 20번. **로컬 전용**입니다.

## 로컬에서 (실제 서버와 GPT를 건드리지 않음)

```bash
# 1) DB(PostgreSQL)와 가짜 agent
python3 load/stub_agent.py &                 # 8081, 0.8초 뒤 같은 답
# 2) 게이트웨이
./gradlew bootJar -x test
JWT_SECRET=$(openssl rand -base64 48) GATEWAY_DEV_LOGIN=true AGENT_URL=http://localhost:8081 \
  java -jar build/libs/fashion-agent-gateway-0.0.1-SNAPSHOT.jar &
# 3) 부하
k6 run load/gateway.js                       # smoke
k6 run -e PROFILE=stress -e TURN=1 load/gateway.js
```

## 실제 서버(젯슨)에서 — 박창근 확인 뒤에만

```bash
k6 run -e BASE_URL=https://simcheong.java21.net -e DEV_KEY="$(cat ~/Documents/fashion-agent/.dev-login-key)" load/gateway.js
```

- 개발용 로그인으로 `loadtest-k6-p0~p3`, `loadtest-k6-limit` 계정 5개가 생깁니다(몇 번을 돌려도 같은 5개).
- 로그인은 기기(IP)당 분당 10번 제한이 있어 연달아 돌리면 setup에서 429가 납니다. 1분 쉬고 다시 돌립니다.
- 강사님 프록시를 거치면 모든 요청이 같은 IP로 보여서, 그동안 다른 사람의 로그인이 막힐 수 있습니다.

## 결과 (2026-11-25, 클라우드 4코어 컨테이너, 로컬 PostgreSQL 16, 가짜 agent)

| 세기 | health p95 | profile p95 | turn p95 | 오류 | 429(제한) |
|---|---|---|---|---|---|
| smoke + TURN | 3.6ms | 4.8ms | 810ms(가짜 agent 0.8초) | 0% | 10 |
| stress + TURN (health 최대 초당 400) | 1.1ms | 2.3ms | 804ms | 0% | 2,161(대화 초당 20번을 4명이 보내서 거의 다 제한) |

- 초당 400번까지 꺾이지 않았습니다. 대화는 사용자마다 분당 30번에서 정확히 막혔습니다.
- **발견:** 부하 중 DB가 멈추자 DB를 쓰는 요청(health·로그인·내 정보·대화 전 내 정보 읽기)이 모두 30초씩
  멈추고 요청이 500개 넘게 쌓였습니다(Hikari 연결 대기 기본값 30초). `connection-timeout: 3000`으로 바꿔
  DB가 죽으면 바로(0.01초) 503을 돌려주게 했습니다(gateway·mcp-schedule·mcp-closet).
