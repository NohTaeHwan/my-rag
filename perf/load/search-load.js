// GET /api/search 부하 테스트. scenarios.json의 search.{STAGE}로 VU/시간을 정하고,
// search_query_mix 비율대로 M 데이터셋 질의를 뽑아 보낸다.
//
// 사용 예 (Spark에서, run_search_load.sh 참고):
//   BASE_URL=http://127.0.0.1:8090 STAGE=smoke k6 run search-load.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const scenarios = JSON.parse(open('./scenarios.json'));
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8090';
const STAGE = __ENV.STAGE || 'smoke';
const cfg = scenarios.search[STAGE];
if (!cfg) {
  throw new Error(`알 수 없는 STAGE: ${STAGE} (가능: ${Object.keys(scenarios.search).join(', ')})`);
}

const rawLines = open('../dataset/queries_m.resolved.jsonl').split('\n').filter((l) => l.trim().length > 0);
const allQueries = rawLines.map((l) => JSON.parse(l));

const buckets = {
  // "gold" = 사용자가 실제로 물어볼 법한 정상 질문군(direct_lookup/hard_negative/multi_chunk)을 합친 것.
  gold: allQueries.filter((q) => ['direct_lookup', 'hard_negative', 'multi_chunk'].includes(q.category)).map((q) => q.question),
  paraphrase: allQueries.filter((q) => q.category === 'paraphrase').map((q) => q.question),
  keyword_identifier: allQueries.filter((q) => q.category === 'keyword_identifier').map((q) => q.question),
  unanswerable: allQueries.filter((q) => q.category === 'unanswerable').map((q) => q.question),
};
for (const [name, list] of Object.entries(buckets)) {
  if (list.length === 0) throw new Error(`질의 버킷이 비어있음: ${name}`);
}

const mix = scenarios.search_query_mix;

function pickQuestion() {
  const r = Math.random();
  let acc = 0;
  for (const [bucket, weight] of Object.entries(mix)) {
    acc += weight;
    if (r <= acc) {
      const list = buckets[bucket];
      return list[Math.floor(Math.random() * list.length)];
    }
  }
  return buckets.gold[Math.floor(Math.random() * buckets.gold.length)];
}

function randomRequestId() {
  // 서버 RequestIdFilter가 받아주는 형식([A-Za-z0-9_-]{1,128})에 맞춘 상관관계 추적용 ID.
  return 'k6-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

const searchErrors = new Counter('search_errors');
const searchLatency = new Trend('search_latency_ms', true);

export const options = {
  vus: cfg.vus,
  duration: cfg.duration,
  thresholds: {
    http_req_failed: ['rate<0.2'], // 임계값 위반은 실패가 아니라 관찰 대상 — Stress 이상에서는 넘을 수 있음
  },
};

export default function () {
  const question = pickQuestion();
  const url = `${BASE_URL}/api/search?query=${encodeURIComponent(question)}`;
  const res = http.get(url, {
    timeout: '35s',
    headers: { 'X-Request-Id': randomRequestId() },
  });

  searchLatency.add(res.timings.duration);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'has results array': (r) => {
      if (r.status !== 200) return false;
      try {
        return Array.isArray(JSON.parse(r.body).results);
      } catch (e) {
        return false;
      }
    },
  });
  if (!ok) searchErrors.add(1);

  sleep(0.2);
}
