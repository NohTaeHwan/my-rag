#!/usr/bin/env python3
"""S/M/L 크기의 합성 Markdown 문서와 대응 gold 질의(queries.jsonl 초안)를 생성한다.

설계 원칙 (계획 문서 §3 참고):
  - 문서 내용은 실제 MVP 사용과 같은 한국어 쇼핑몰 도메인(주문/결제/환불/배송/회원/장애대응)
  - 같은 도메인 안에서 회사명·부서명·숫자(기간·수수료 등)만 다른 "hard negative" 변형을 다수 생성해,
    검색이 표면적 키워드가 아니라 실제 값을 구분해내는지 확인할 수 있게 한다.
  - 정답(expected_facts)은 생성 시점에 파라미터로 직접 박아 넣은 값이라, 이후 LLM이나 사람이
    "정답을 새로 판단"할 필요가 없다 — 문서를 만들 때 이미 정답을 알고 있는 구조다.
  - relevant_chunk_keys(실제 색인 후의 chunk_index)는 이 스크립트가 알 수 없다. 색인 후
    resolve_chunk_keys.py로 별도로 채운다.

사용 예:
  python3 generate_dataset.py --size s --out-dir generated/s --queries-out queries_s.jsonl
"""
from __future__ import annotations

import argparse
import json
import random
import shutil
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# 도메인 템플릿
# ---------------------------------------------------------------------------

COMPANIES = ["한빛몰", "그린마트", "온누리샵", "블루베이스", "모던스토어", "라이트커머스"]
DEPARTMENTS = ["CS팀", "물류팀", "정산팀", "회원관리팀", "품질관리팀"]


def _has_batchim(word: str) -> bool:
    """단어 마지막 글자에 받침이 있는지 확인한다(한글 음절이 아니면 받침 없음으로 취급)."""
    if not word:
        return False
    code = ord(word[-1])
    if 0xAC00 <= code <= 0xD7A3:
        return (code - 0xAC00) % 28 != 0
    return False


def eun_neun(word: str) -> str:
    return "은" if _has_batchim(word) else "는"


def i_ga(word: str) -> str:
    return "이" if _has_batchim(word) else "가"


def eul_reul(word: str) -> str:
    return "을" if _has_batchim(word) else "를"


@dataclass
class DomainTemplate:
    id: str
    title_fmt: str
    sections: list[tuple[str, str]]  # (heading, body_fmt)
    fact_key: str  # 본문에 삽입되는 값 중 정답으로 쓸 파라미터 이름
    fact_choices: list[str]
    question_fmt: str
    unrelated_question: str
    # ambiguous: 회사명을 특정하지 않아 여러 문서 중 어느 것의 답인지 단정할 수 없는 질문.
    ambiguous_question: str = ""
    # multi_chunk: 서로 다른 section(=대체로 다른 chunk)에 있는 두 값을 같이 물어보는 질문.
    # 이 값을 지원하지 않는 도메인은 None으로 둔다(모든 도메인이 서로 다른 section에 걸친
    # 숫자 값 2개를 갖고 있지는 않음).
    secondary_fact_key: str | None = None
    multi_question_fmt: str | None = None
    # keyword_identifier: 본문에 리터럴로 박혀있는 정확한 코드/식별자를 묻는 질문.
    keyword_code: str | None = None
    keyword_question_fmt: str | None = None


DOMAINS: list[DomainTemplate] = [
    DomainTemplate(
        id="order-cancel",
        title_fmt="{company} 주문 취소 정책",
        sections=[
            ("취소 가능 시점",
             "{company}{company_eun_neun} 결제 완료 후 {cancel_days}일 이내, 배송 시작 전까지 주문 취소를 허용한다. "
             "배송이 이미 시작된 주문은 취소 대신 반품 절차로 안내한다."),
            ("취소 처리 절차",
             "고객이 마이페이지 또는 고객센터를 통해 취소를 요청하면 담당 부서인 {dept}{dept_i_ga} "
             "{process_hours}시간 이내에 처리한다. 처리가 완료되면 {company}{company_i_ga} 주문 상태를 "
             "`CANCELLED`로 변경한다."),
            ("취소 수수료",
             "{cancel_days}일 이내 취소는 수수료가 없다. 이 기간을 초과한 취소 요청은 반려된다."),
        ],
        fact_key="cancel_days",
        fact_choices=["3", "5", "7", "14"],
        question_fmt="{company}에서 결제 후 며칠 이내에 주문을 취소할 수 있나요?",
        unrelated_question="{company}의 창립 연도는 언제인가요?",
        ambiguous_question="결제 후 며칠 이내에 주문을 취소할 수 있나요?",
        secondary_fact_key="process_hours",
        multi_question_fmt="{company}에서 결제 후 며칠 이내에 주문을 취소할 수 있고, 취소 요청은 몇 시간 이내에 처리되나요?",
        keyword_code="CANCELLED",
        keyword_question_fmt="{company}에서 주문 취소가 완료되면 주문 상태가 어떤 코드로 바뀌나요?",
    ),
    DomainTemplate(
        id="refund",
        title_fmt="{company} 환불 정책",
        sections=[
            ("환불 처리 기간",
             "{company}의 환불 요청은 접수 후 평균 {refund_days}영업일 이내에 처리된다. "
             "카드 결제는 승인 취소, 계좌이체는 환불 계좌로 직접 재입금하는 방식을 사용한다."),
            ("환불 담당 부서",
             "환불 처리는 {dept}{dept_i_ga} 담당하며, 처리 실패 시 최대 {retry_count}회까지 자동 재시도한다."),
            ("부분 환불",
             "부분 취소·부분 반품에 대응해 부분 환불을 지원하며, 상품 가격 기준으로 금액을 계산한다."),
        ],
        fact_key="refund_days",
        fact_choices=["1~2", "2~3", "3~5", "5~7"],
        question_fmt="{company}에서 환불은 보통 며칠 정도 걸리나요?",
        unrelated_question="{company}의 본사 위치는 어디인가요?",
        ambiguous_question="환불은 보통 며칠 정도 걸리나요?",
        secondary_fact_key="retry_count",
        multi_question_fmt="{company}에서 환불은 보통 며칠 걸리고, 환불 실패 시 최대 몇 번까지 재시도하나요?",
    ),
    DomainTemplate(
        id="shipping",
        title_fmt="{company} 배송 정책",
        sections=[
            ("배송 기간",
             "{company}{company_eun_neun} 결제 완료 후 상품 준비까지 평균 1영업일, 출고 후 평균 {delivery_days}일 내에 "
             "배송을 완료한다."),
            ("배송비 정책",
             "{free_shipping_threshold}원 이상 주문 시 배송비를 무료로 제공하며, 미만 주문은 "
             "배송비 {shipping_fee}원이 부과된다."),
            ("배송 지연 처리",
             "예상 배송일을 초과해 지연되는 경우 {dept}{dept_i_ga} 고객에게 지연 안내를 발송한다."),
        ],
        fact_key="delivery_days",
        fact_choices=["1~2", "2~3", "3~4"],
        question_fmt="{company}의 배송은 출고 후 보통 며칠 걸리나요?",
        unrelated_question="{company}의 대표 상품은 무엇인가요?",
        ambiguous_question="배송은 출고 후 보통 며칠 걸리나요?",
        secondary_fact_key="shipping_fee",
        multi_question_fmt="{company}의 배송은 출고 후 며칠 걸리고, 무료배송 기준 미만이면 배송비는 얼마인가요?",
    ),
    DomainTemplate(
        id="membership",
        title_fmt="{company} 회원 등급 정책",
        sections=[
            ("등급 산정 기준",
             "{company}의 회원 등급은 최근 {period_months}개월 누적 구매 금액을 기준으로 매월 1일 재산정된다."),
            ("등급별 혜택",
             "최상위 등급 회원은 전 상품 {discount_rate}% 할인 혜택을 받으며, 담당 부서 {dept}{dept_i_ga} 등급 변경을 안내한다."),
            ("등급 유지 조건",
             "직전 산정 등급을 유지하려면 해당 {period_months}개월 동안 최소 1회 이상 구매해야 한다."),
        ],
        fact_key="period_months",
        fact_choices=["3", "6", "12"],
        question_fmt="{company}의 회원 등급은 최근 몇 개월 구매 실적으로 산정되나요?",
        unrelated_question="{company}의 회원 가입 방법은 무엇인가요?",
        ambiguous_question="회원 등급은 최근 몇 개월 구매 실적으로 산정되나요?",
        secondary_fact_key="discount_rate",
        multi_question_fmt="{company}의 회원 등급은 최근 몇 개월 실적으로 산정되고, 최상위 등급 할인율은 몇 %인가요?",
    ),
    DomainTemplate(
        id="payment",
        title_fmt="{company} 결제 실패 처리 정책",
        sections=[
            ("결제 실패 시 재고 처리",
             "{company}{company_eun_neun} 결제가 실패하면 임시 예약했던 재고를 즉시 반환하고 주문 상태를 `FAILED`로 변경한다."),
            ("재시도 정책",
             "결제 실패 시 최대 {retry_count}회까지 자동 재시도하며, {dept}{dept_i_ga} 최종 실패 건을 모니터링한다."),
            ("결제 수단",
             "카드, 계좌이체, 간편결제를 지원하며 결제수단별 실패율은 별도로 집계한다."),
        ],
        fact_key="retry_count",
        fact_choices=["2", "3", "5"],
        question_fmt="{company}에서 결제 실패 시 최대 몇 번까지 자동 재시도하나요?",
        unrelated_question="{company}{company_i_ga} 지원하는 결제 수단은 무엇인가요?",
        ambiguous_question="결제 실패 시 최대 몇 번까지 자동 재시도하나요?",
        keyword_code="FAILED",
        keyword_question_fmt="{company}에서 결제가 실패하면 주문 상태가 어떤 코드로 바뀌나요?",
    ),
    DomainTemplate(
        id="incident",
        title_fmt="{company} 장애 대응 정책",
        sections=[
            ("장애 등급 분류",
             "{company}{company_eun_neun} 서비스 장애를 영향 범위에 따라 1~3등급으로 분류하며, 1등급 장애는 "
             "{response_minutes}분 이내 초기 대응을 시작해야 한다."),
            ("장애 공지",
             "장애 발생 시 {dept}{dept_i_ga} 공지 채널을 통해 상태를 안내하고, 복구 완료 시 사후 보고서를 작성한다."),
            ("재발 방지",
             "동일 원인의 재발을 막기 위해 사후 회고(postmortem)를 진행하고 개선 항목을 기록한다."),
        ],
        fact_key="response_minutes",
        fact_choices=["15", "30", "60"],
        question_fmt="{company}{company_eun_neun} 1등급 장애 발생 시 몇 분 이내에 초기 대응을 시작하나요?",
        unrelated_question="{company}의 장애 공지는 어떤 채널로 안내되나요?",
        ambiguous_question="1등급 장애 발생 시 몇 분 이내에 초기 대응을 시작하나요?",
    ),
]

SIZE_TO_DOC_COUNT = {"s": 100, "m": 1000, "l": 10000, "xl": 50000}


@dataclass
class GeneratedDoc:
    source: str
    title: str
    markdown: str
    domain_id: str
    fact_key: str
    fact_value: str
    company: str
    params: dict = field(default_factory=dict)


DOMAIN_CYCLE_SPAN = 1000  # 도메인 하나가 이 값보다 많은 문서를 갖지 않는다고 가정(현재 최대 사이즈보다 훨씬 큼)


def unique_company_for(domain_index: int, domain_local_index: int) -> str:
    """도메인 간에도, 도메인 안에서도 절대 겹치지 않는 회사명을 만든다.

    COMPANIES 풀(6개)을 다 쓰고 나면 "회사명+N호점" 형태로 늘려서, 문서 수가 아무리
    많아져도 같은 도메인 안에서 회사명이 중복되는 일이 없게 한다. 이전 버전은
    rng.choice(COMPANIES)로 랜덤 배정해서, 같은 도메인 안에 같은 회사명+같은 정답값을
    가진 문서가 우연히 여러 개 생기는 버그가 있었다(내용이 사실상 동일한 문서가 중복
    생성되어, 검색이 그중 하나만 gold로 인정된 문서를 못 찾으면 "가짜 실패"로 채점됨).

    domain_index를 cycle에 섞어 넣지 않으면, 서로 다른 도메인이 같은 domain_local_index에서
    똑같은 (회사명, cycle) 조합을 만들어낸다(예: order-cancel의 33번째 문서와 payment의
    33번째 문서가 둘 다 "블루베이스 6호점"이 됨) — 이러면 keyword_identifier처럼 회사명으로만
    구분해야 하는 질의가 엉뚱한 도메인 문서와 혼동되는 실제 버그가 생긴다.
    """
    base = COMPANIES[domain_local_index % len(COMPANIES)]
    cycle = domain_local_index // len(COMPANIES) + domain_index * DOMAIN_CYCLE_SPAN
    return base if cycle == 0 else f"{base} {cycle + 1}호점"


def render_doc(domain: DomainTemplate, domain_index: int, domain_local_index: int, rng: random.Random, size: str) -> GeneratedDoc:
    company = unique_company_for(domain_index, domain_local_index)
    dept = rng.choice(DEPARTMENTS)
    fact_value = domain.fact_choices[domain_local_index % len(domain.fact_choices)]

    params = {
        "company": company,
        "dept": dept,
        "company_eun_neun": eun_neun(company),
        "company_i_ga": i_ga(company),
        "dept_i_ga": i_ga(dept),
        domain.fact_key: fact_value,
        # 도메인별로 쓰지 않는 키는 format에서 무시되도록 defaultdict 대신 아래에서 개별 보강
    }
    # 각 도메인이 쓰는 추가 파라미터를 기본값과 함께 채운다(hard negative 다양성을 위해 일부는 랜덤화).
    params.setdefault("process_hours", rng.choice(["12", "24", "48"]))
    params.setdefault("retry_count", rng.choice(["2", "3", "5"]))
    params.setdefault("free_shipping_threshold", rng.choice(["30,000", "50,000", "70,000"]))
    params.setdefault("shipping_fee", rng.choice(["2,500", "3,000", "3,500"]))
    params.setdefault("discount_rate", rng.choice(["5", "10", "15"]))
    params.setdefault("period_months", rng.choice(["3", "6", "12"]))
    params.setdefault("response_minutes", rng.choice(["15", "30", "60"]))
    params[domain.fact_key] = fact_value  # fact_key는 마지막에 다시 덮어써서 확정

    title = domain.title_fmt.format(**params)
    # company에 공백이 들어갈 수 있어(예: "한빛몰 3호점") 첫 공백 기준으로 자르면 안 되고,
    # title이 항상 "{company} ..." 형태(title_fmt 전부 "{company} "로 시작)인 것을 이용해
    # company 접두어 길이만큼 정확히 잘라낸다.
    title_suffix = title[len(company):].strip()
    lines = [f"# {title}", ""]
    lines.append(f"이 문서는 {company}의 {title_suffix}{eul_reul(title_suffix)} 설명한다.")
    lines.append("")
    for heading, body_fmt in domain.sections:
        lines.append(f"## {heading}")
        lines.append("")
        lines.append(body_fmt.format(**params))
        lines.append("")

    source = f"perf-test/{size}/{domain.id}-{domain_local_index:04d}.md"
    return GeneratedDoc(
        source=source,
        title=title,
        markdown="\n".join(lines),
        domain_id=domain.id,
        fact_key=domain.fact_key,
        fact_value=fact_value,
        company=company,
        params=params,
    )


# 계획서 §3 Task 2가 요구하는 7개 카테고리. direct_lookup을 뺀 나머지 6개는 최소
# MIN_PER_CATEGORY개(단, 후보가 부족하면 있는 만큼만)를 우선 채우고, 남는 예산을
# direct_lookup에 전부 배정한다. M(150개 안팎) 기준으로는 6*15=90 + direct_lookup 60 정도로
# 계획서의 "카테고리당 최소 15개"를 자연스럽게 만족한다. S처럼 sample_size가 작으면
# 카테고리당 할당량도 비례해서 줄어든다(0이 되지는 않게 최소 1은 보장).
MIN_PER_CATEGORY = 15


def _quota(sample_size: int, num_other_categories: int) -> int:
    return max(1, min(MIN_PER_CATEGORY, sample_size // (num_other_categories + 1)))


def _rephrase_variants(question: str) -> list[str]:
    """가벼운 어휘 치환으로 같은 의미의 다른 표현 몇 개를 만든다(중복은 제거)."""
    variants = [question]
    v2 = (question.replace("며칠", "얼마 동안").replace("몇 번", "몇 차례")
          .replace("몇 분", "얼마 만에").replace("몇 개월", "몇 달").replace("몇 %", "몇 퍼센트"))
    if v2 not in variants:
        variants.append(v2)
    v3 = question.replace("보통", "일반적으로").replace("최대", "최고")
    if v3 not in variants:
        variants.append(v3)
    return variants


def build_queries(docs: list[GeneratedDoc], rng: random.Random, sample_size: int) -> list[dict]:
    domain_by_id = {d.id: d for d in DOMAINS}
    by_domain: dict[str, list[GeneratedDoc]] = {}
    for doc in docs:
        by_domain.setdefault(doc.domain_id, []).append(doc)

    other_quota = _quota(sample_size, num_other_categories=6)

    # direct_lookup 후보: 문서별 정답 질문
    direct_candidates = []
    for i, doc in enumerate(docs):
        domain = domain_by_id[doc.domain_id]
        direct_candidates.append({
            "query_id": f"q-direct-{i:05d}",
            "question": domain.question_fmt.format(**doc.params),
            "category": "direct_lookup",
            "answer_type": "extractive",
            "relevant_sources": [doc.source],
            "relevant_chunk_keys": [],  # resolve_chunk_keys.py가 색인 후 채움
            "expected_facts": [doc.fact_value],
            "unanswerable": False,
            "difficulty": "easy",
        })

    # hard_negative 후보: 같은 도메인에 값만 다른 문서가 여러 개 있는 상황에서의 질문.
    # direct_lookup과 질문 형태는 같지만, "같은 도메인 안에 유사 문서가 여럿 있다"는
    # 맥락에서 별도 카테고리로 채점하기 위해 나눈다.
    hard_candidates = []
    for doc in docs:
        if len(by_domain[doc.domain_id]) < 2:
            continue
        domain = domain_by_id[doc.domain_id]
        hard_candidates.append({
            "query_id": f"q-hardneg-{doc.source.replace('/', '_')}",
            "question": domain.question_fmt.format(**doc.params),
            "category": "hard_negative",
            "answer_type": "extractive",
            "relevant_sources": [doc.source],
            "relevant_chunk_keys": [],
            "expected_facts": [doc.fact_value],
            "unanswerable": False,
            "difficulty": "hard",
        })

    # unanswerable 후보: 문서에 없는 사실을 묻는 질문
    unanswerable_candidates = []
    for doc in docs:
        domain = domain_by_id[doc.domain_id]
        unanswerable_candidates.append({
            "query_id": f"q-unans-{doc.source.replace('/', '_')}",
            "question": domain.unrelated_question.format(**doc.params),
            "category": "unanswerable",
            "answer_type": "abstractive",
            "relevant_sources": [],
            "relevant_chunk_keys": [],
            "expected_facts": [],
            "unanswerable": True,
            "difficulty": "medium",
        })

    # multi_chunk 후보: 서로 다른 section(대체로 다른 chunk)의 값 두 개를 같이 묻는 질문.
    # 모든 도메인이 지원하지는 않는다(secondary_fact_key가 없는 도메인은 제외).
    multi_chunk_candidates = []
    for doc in docs:
        domain = domain_by_id[doc.domain_id]
        if not domain.multi_question_fmt or not domain.secondary_fact_key:
            continue
        secondary_value = doc.params.get(domain.secondary_fact_key)
        if secondary_value is None:
            continue
        multi_chunk_candidates.append({
            "query_id": f"q-multi-{doc.source.replace('/', '_')}",
            "question": domain.multi_question_fmt.format(**doc.params),
            "category": "multi_chunk",
            "answer_type": "extractive",
            "relevant_sources": [doc.source],
            "relevant_chunk_keys": [],
            "expected_facts": [doc.fact_value, str(secondary_value)],
            "unanswerable": False,
            "difficulty": "medium",
        })

    # keyword_identifier 후보: 본문에 리터럴로 박힌 정확한 코드(예: `CANCELLED`)를 묻는 질문.
    keyword_candidates = []
    for doc in docs:
        domain = domain_by_id[doc.domain_id]
        if not domain.keyword_question_fmt or not domain.keyword_code:
            continue
        keyword_candidates.append({
            "query_id": f"q-keyword-{doc.source.replace('/', '_')}",
            "question": domain.keyword_question_fmt.format(**doc.params),
            "category": "keyword_identifier",
            "answer_type": "extractive",
            "relevant_sources": [doc.source],
            "relevant_chunk_keys": [],
            "expected_facts": [domain.keyword_code],
            "unanswerable": False,
            "difficulty": "medium",
        })

    # ambiguous 후보: 회사명을 특정하지 않아 같은 도메인의 여러 문서 중 어느 것이
    # 정답인지 문서만으로 단정할 수 없는 질문. 평가 시에는 unanswerable과 동일하게
    # (정답 문서가 하나로 정해지지 않으므로) 점수 계산에서 제외한다.
    # 도메인 하나당 base 질문 1개뿐이라 그대로는 최대 6개(도메인 수)밖에 안 나오므로,
    # 가벼운 어휘 치환으로 변형을 몇 개 더 만들어 M 데이터셋의 "카테고리당 최소 15개" 기준에
    # 근접시킨다(그래도 도메인 조합에 따라 15개에 못 미칠 수 있음 — 그 경우 사람이 손으로
    # 추가하는 걸 권장한다, labeling-guide.md 참고).
    ambiguous_candidates = []
    for domain in DOMAINS:
        if not domain.ambiguous_question or len(by_domain.get(domain.id, [])) < 2:
            continue
        for vi, variant_question in enumerate(_rephrase_variants(domain.ambiguous_question)):
            ambiguous_candidates.append({
                "query_id": f"q-ambiguous-{domain.id}-{vi}",
                "question": variant_question,
                "category": "ambiguous",
                "answer_type": "abstractive",
                "relevant_sources": [],
                "relevant_chunk_keys": [],
                "expected_facts": [],
                "unanswerable": True,  # 정답 문서를 하나로 특정할 수 없어 채점 시 unanswerable과 동일 취급
                "difficulty": "hard",
            })

    def sample(candidates: list[dict], n: int) -> list[dict]:
        return rng.sample(candidates, min(n, len(candidates)))

    queries: list[dict] = []
    queries.extend(sample(multi_chunk_candidates, other_quota))
    queries.extend(sample(keyword_candidates, other_quota))
    queries.extend(sample(hard_candidates, other_quota))
    queries.extend(sample(unanswerable_candidates, other_quota))
    queries.extend(sample(ambiguous_candidates, other_quota))

    used_sources = {q["query_id"] for q in queries}
    remaining_direct_pool = [q for q in direct_candidates if q["query_id"] not in used_sources]
    n_direct_and_paraphrase = max(1, sample_size - len(queries))
    # 남은 예산의 절반은 direct_lookup, 절반은 그중 일부를 재구성한 paraphrase로 채운다.
    n_direct = max(1, n_direct_and_paraphrase - other_quota)
    direct_selected = sample(remaining_direct_pool, n_direct)
    queries.extend(direct_selected)

    paraphrase_pool = [q for q in remaining_direct_pool if q not in direct_selected]
    for i, base in enumerate(sample(paraphrase_pool, other_quota)):
        rephrased = (base["question"]
                     .replace("며칠", "얼마 동안")
                     .replace("몇 번", "몇 차례")
                     .replace("몇 분", "얼마 만에")
                     .replace("몇 개월", "몇 달")
                     .replace("몇 %", "몇 퍼센트"))
        queries.append({
            **base,
            "query_id": f"q-para-{i:04d}",
            "question": rephrased,
            "category": "paraphrase",
            "difficulty": "medium",
        })

    return queries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", choices=sorted(SIZE_TO_DOC_COUNT), required=True)
    parser.add_argument("--out-dir", required=True,
                         help="document.source-directory로 쓰일(또는 그 안에 합쳐질) 마크다운 루트 디렉터리. "
                              "실제 파일은 이 아래 perf-test/{size}/ 에 생성된다(문서의 source 경로와 정확히 일치시키기 위함)")
    parser.add_argument("--queries-out", required=True, help="gold 질의를 저장할 .jsonl 경로")
    parser.add_argument("--query-sample-size", type=int, default=20,
                         help="이번 실행에서 뽑을 gold 질의 수 (S=20 검증용, M/L은 늘려서 사용)")
    parser.add_argument("--seed", type=int, default=42, help="재현성을 위한 난수 시드")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    doc_count = SIZE_TO_DOC_COUNT[args.size]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # 이전 실행의 잔여 파일이 새 번호 체계와 섞이지 않도록, 이번 size의 출력 폴더를 먼저 비운다.
    # (예: 예전엔 전역 index로 파일명을 매겼다가 도메인별 local index로 바꾸면, 옛 파일들이
    #  안 지워지고 같이 남아서 실제로 존재하지 않는 "중복 문서"처럼 보이는 문제가 있었다.)
    size_dir = out_dir / "perf-test" / args.size
    if size_dir.exists():
        shutil.rmtree(size_dir)

    docs: list[GeneratedDoc] = []
    domain_counts: dict[str, int] = {}
    for i in range(doc_count):
        domain_index = i % len(DOMAINS)
        domain = DOMAINS[domain_index]
        domain_local_index = domain_counts.get(domain.id, 0)
        domain_counts[domain.id] = domain_local_index + 1
        doc = render_doc(domain, domain_index, domain_local_index, rng, args.size)
        docs.append(doc)
        # doc.source(예: "perf-test/s/payment-0000.md")는 DocumentSourceReader가 실제로
        # 계산하는 상대경로와 정확히 같아야 한다 — 그래야 queries.jsonl의 relevant_sources가
        # 실제 색인된 tb_document.source와 일치한다.
        doc_path = out_dir / doc.source
        doc_path.parent.mkdir(parents=True, exist_ok=True)
        doc_path.write_text(doc.markdown, encoding="utf-8")

    queries = build_queries(docs, rng, args.query_sample_size)

    queries_path = Path(args.queries_out)
    queries_path.parent.mkdir(parents=True, exist_ok=True)
    with queries_path.open("w", encoding="utf-8") as f:
        for q in queries:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")

    manifest = {
        "size": args.size,
        "seed": args.seed,
        "document_count": len(docs),
        "query_count": len(queries),
        "domains": sorted({d.domain_id for d in docs}),
    }
    manifest_path = out_dir / f"manifest_{args.size}.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"문서 {len(docs)}개 생성 완료 → {out_dir}/perf-test/{args.size}/")
    print(f"gold 질의 {len(queries)}개 생성 완료 → {queries_path}")
    print(f"manifest → {manifest_path}")


if __name__ == "__main__":
    main()
