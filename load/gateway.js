// 게이트웨이 부하 테스트 (k6). 실행 방법·결과 읽는 법은 load/README.md.
//
//   k6 run load/gateway.js                                   # 기본: smoke(가볍게 1분), http://localhost:8080
//   k6 run -e PROFILE=stress load/gateway.js                 # 로컬 전용: 점점 세게
//   k6 run -e BASE_URL=https://... -e DEV_KEY=... load/gateway.js
//
// 시나리오
//   health    : 로그인 없이 /actuator/health. 게이트웨이 자체(톰캣·필터)가 초당 몇 개를 견디는지.
//   profile   : 로그인한 사용자가 내 정보(/api/v1/me/profile)를 읽음. JWT 확인 + DB 읽기 경로.
//   turn      : 대화 한 번(/api/v1/agent/turn). 기본은 꺼 둠(TURN=1로 켬). 실제 서버에서 켜면 GPT 비용이 듭니다.
//               로컬에서는 load/stub_agent.py(가짜 agent)를 붙여 게이트웨이 중계만 잽니다.
//   ratelimit : 한 사용자가 1초에 40번 → 분당 30번 제한이 지켜지는지(429가 나와야 정상).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || 'smoke';
const USERS = Number(__ENV.USERS || 4);
const TURN = __ENV.TURN === '1';
// 같은 이름으로 로그인하므로 몇 번을 돌려도 계정은 USERS개만 생깁니다.
const DEVICE_PREFIX = __ENV.DEVICE_PREFIX || 'loadtest-k6-';

const limited = new Counter('rate_limited_429');

const profiles = {
  // 실제 서버(젯슨)에도 돌릴 수 있는 세기: 1분, 초당 health 5번 + 사용자 4명이 분당 20번씩 내 정보 (+TURN=1이면 4초에 대화 1번).
  smoke: { healthRate: 5, healthRamp: null, duration: '1m', turnRate: 1, turnUnit: '4s' },
  // 로컬 전용: health를 초당 20 → 400까지 올려 꺾이는 지점을 찾습니다.
  stress: { healthRate: null, healthRamp: [20, 100, 200, 400], duration: '2m', turnRate: 20, turnUnit: '1s' },
};
const P = profiles[PROFILE];
if (!P) throw new Error(`PROFILE은 ${Object.keys(profiles).join(', ')} 중 하나`);

const scenarios = {
  health: P.healthRamp
    ? {
        executor: 'ramping-arrival-rate',
        exec: 'health',
        startRate: P.healthRamp[0],
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 400,
        stages: P.healthRamp.map((r) => ({ target: r, duration: '30s' })),
      }
    : {
        executor: 'constant-arrival-rate',
        exec: 'health',
        rate: P.healthRate,
        timeUnit: '1s',
        duration: P.duration,
        preAllocatedVUs: 5,
      },
  profile: {
    executor: 'constant-vus',
    exec: 'profile',
    vus: USERS,
    duration: P.duration,
  },
  ratelimit: {
    executor: 'per-vu-iterations',
    exec: 'ratelimit',
    vus: 1,
    iterations: 1,
    startTime: '5s',
  },
};
if (TURN) {
  scenarios.turn = {
    executor: 'constant-arrival-rate',
    exec: 'turn',
    rate: P.turnRate,
    timeUnit: P.turnUnit,
    duration: P.duration,
    preAllocatedVUs: 20,
    maxVUs: 200,
  };
}

export const options = {
  scenarios,
  // 하나라도 넘으면 k6가 실패(종료 코드 99)로 끝납니다.
  thresholds: {
    'http_req_failed{scenario:health}': ['rate<0.01'],
    'http_req_failed{scenario:profile}': ['rate<0.01'],
    'http_req_duration{scenario:health}': ['p(95)<300'],
    'http_req_duration{scenario:profile}': ['p(95)<500'],
    ...(TURN ? { 'http_req_failed{scenario:turn}': ['rate<0.02'], 'http_req_duration{scenario:turn}': ['p(95)<10000'] } : {}),
    // 분당 30번 제한: 1초에 40번 보내면 적어도 10번은 429여야 합니다.
    rate_limited_429: ['count>=10'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

function json(body) {
  return { headers: { 'Content-Type': 'application/json', ...(__ENV.DEV_KEY ? { 'X-Dev-Login-Key': __ENV.DEV_KEY } : {}) }, body: JSON.stringify(body) };
}

// 시작 전에 개발용 로그인으로 사용자를 만듭니다. 분당 30번 제한이 사용자마다라서 시나리오끼리 사용자를 나눕니다:
// 내 정보 USERS명, 대화 USERS명(TURN=1일 때), 제한 확인 1명. 로그인은 기기(IP)당 분당 10번 제한이 있어 합쳐 9명 이하.
function login(name) {
  const p = json({ deviceId: `${DEVICE_PREFIX}${name}` });
  const res = http.post(`${BASE}/api/v1/auth/dev`, p.body, { headers: p.headers, tags: { scenario: 'setup' } });
  if (res.status !== 200) throw new Error(`개발용 로그인 실패 ${res.status}: ${res.body} (DEV_KEY 확인)`);
  return res.json('accessToken');
}

export function setup() {
  const total = USERS * (TURN ? 2 : 1) + 1;
  if (total > 9) throw new Error(`사용자 ${total}명은 너무 많아요(로그인 분당 10번 제한). USERS를 줄이세요.`);
  const ids = (prefix) => Array.from({ length: USERS }, (_, i) => login(`${prefix}${i}`));
  return { profile: ids('p'), turn: TURN ? ids('t') : [], limit: login('limit') };
}

function auth(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

export function health() {
  const res = http.get(`${BASE}/actuator/health`);
  check(res, { 'health 200': (r) => r.status === 200 });
}

export function profile(data) {
  // __VU는 모든 시나리오를 합친 번호라 겹칠 수 있어서, 이 시나리오 안의 반복 번호로 사용자를 돌려 씁니다.
  const token = data.profile[exec.scenario.iterationInTest % USERS];
  const res = http.get(`${BASE}/api/v1/me/profile`, auth(token));
  check(res, { 'profile 200': (r) => r.status === 200 });
  // 분당 30번 제한 아래(분당 20번)로.
  sleep(3);
}

export function turn(data) {
  const token = data.turn[exec.scenario.iterationInTest % USERS];
  const res = http.post(
    `${BASE}/api/v1/agent/turn`,
    JSON.stringify({ utterance: '오늘 날씨 어때?', location: { lat: 37.5665, lon: 126.978, locationFallback: true } }),
    { ...auth(token), timeout: '35s', responseCallback: http.expectedStatuses(200, 429) },
  );
  // 사용자마다 분당 30번 제한이 있어 세게(stress) 돌리면 429가 섞입니다. 429는 실패로 세지 않고 따로 셉니다.
  if (res.status === 429) limited.add(1);
  check(res, { 'turn 200 또는 429': (r) => r.status === 200 || r.status === 429 });
}

export function ratelimit(data) {
  const token = data.limit; // 다른 시나리오와 안 겹치는 사용자
  const reqs = Array.from({ length: 40 }, () => ['GET', `${BASE}/api/v1/me/profile`, null, { ...auth(token), tags: { scenario: 'ratelimit' }, responseCallback: http.expectedStatuses(200, 429) }]);
  const res = http.batch(reqs);
  let n429 = 0;
  for (const r of res) if (r.status === 429) n429++;
  limited.add(n429);
  check(res, { 'ratelimit: 200 아니면 429만': (rs) => rs.every((r) => r.status === 200 || r.status === 429) });
}
