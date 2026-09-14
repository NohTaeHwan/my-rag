// POST /api/answers 부하 테스트. 검색과 분리해서 실행한다.
// 질문 4종(짧음/김/근거 있음/근거 없음)을 고정해두고 무작위로 섞어 보낸다 — max_tokens는
// 서버 설정(LLM_MAX_TOKENS)으로 이미 고정돼 있어 클라이언트가 따로 지정할 필요가 없다.
// 동시성은 Load(10)까지만 진행한다 — LLM은 공유 GPU를 쓰고 질의당 5~15초가 걸려 그 이상은
// 다른 서비스에 영향을 줄 위험이 크다(scenarios.json의 answer_load_cap 참고).
//
// 사용 예:
//   BASE_URL=http://127.0.0.1:8090 STAGE=smoke k6 run answer-load.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const scenarios = JSON.parse(open('./scenarios.json'));
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8090';
const STAGE = __ENV.STAGE || 'smoke';
const cfg = scenarios.answer[STAGE];
if (!cfg) {
  throw new Error(`알 수 없는 STAGE: ${STAGE} (가능: ${Object.keys(scenarios.answer).join(', ')})`);
}

const QUESTIONS = {
  short: '결제 실패 시 상태 코드는?',
  long: '주문 취소, 환불, 배송 지연이 동시에 발생했을 때 각각의 처리 절차와 소요 기간을 순서대로 자세히 설명해줘.',
  relevant: '주문 취소는 결제 후 며칠 이내에 가능한가요?',
  no_evidence: '화성으로 배송이 가능한가요?',
};
const KEYS = Object.keys(QUESTIONS);

function randomRequestId() {
  return 'k6-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

const answerLatency = new Trend('answer_latency_ms', true);
const emptyAnswerRate = new Rate('empty_answer_rate');
const answerErrors = new Counter('answer_errors');

export const options = {
  vus: cfg.vus,
  duration: cfg.duration,
  thresholds: {
    http_req_failed: ['rate<0.2'],
  },
};

export default function () {
  const key = KEYS[Math.floor(Math.random() * KEYS.length)];
  const payload = JSON.stringify({ question: QUESTIONS[key] });
  const res = http.post(`${BASE_URL}/api/answers`, payload, {
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': randomRequestId() },
    timeout: '65s', // LLM read-timeout(60s)보다 여유를 둠
  });

  answerLatency.add(res.timings.duration);

  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  if (!ok) {
    answerErrors.add(1);
  } else {
    try {
      const body = JSON.parse(res.body);
      emptyAnswerRate.add(!body.answer || body.answer.trim().length === 0);
    } catch (e) {
      emptyAnswerRate.add(true);
    }
  }

  sleep(1);
}
