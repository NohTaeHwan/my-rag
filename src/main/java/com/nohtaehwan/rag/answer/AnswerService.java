package com.nohtaehwan.rag.answer;

import java.util.List;

import org.springframework.stereotype.Service;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.llm.LlmClient;
import com.nohtaehwan.rag.llm.LlmProperties;
import com.nohtaehwan.rag.llm.Prompt;
import com.nohtaehwan.rag.retrieval.SearchResponse;
import com.nohtaehwan.rag.retrieval.SearchResult;
import com.nohtaehwan.rag.retrieval.SearchService;

import lombok.extern.slf4j.Slf4j;

/**
 * 질문을 검색 근거 기반 LLM 답변으로 변환한다. 검색 결과가 없거나(또는 있어도 context 길이 제한 때문에
 * 하나도 포함되지 못하면) LLM을 호출하지 않고 고정 근거 부족 응답을 반환한다 — 근거 없는 답변 생성을
 * 막기 위함이다.
 *
 * <p>검색(embedding 호출 포함)과 LLM 호출을 DB 트랜잭션으로 묶지 않는다(읽기 전용 흐름).
 */
@Slf4j
@Service
public class AnswerService {

    private static final String NO_EVIDENCE_ANSWER = "질문에 답할 수 있는 근거 문서를 찾지 못했습니다.";

    private final SearchService searchService;
    private final ContextBuilder contextBuilder;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    public AnswerService(
            SearchService searchService,
            ContextBuilder contextBuilder,
            PromptBuilder promptBuilder,
            LlmClient llmClient,
            LlmProperties llmProperties) {
        this.searchService = searchService;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
    }

    /**
     * 질문에 대한 답변을 생성한다.
     *
     * @param question 사용자 질문
     * @return 답변과 실제 prompt에 포함된 근거(검색 결과가 없거나 context가 비면 고정 근거 부족 응답)
     * @throws InvalidRequestException question이 null이거나 공백만 있을 때
     * @throws RagException            검색(embedding/DB) 또는 LLM 호출이 실패했을 때
     */
    public AnswerResponse answer(String question) {
        if (question == null || question.isBlank()) {
            throw new InvalidRequestException("question은 비어 있을 수 없습니다.");
        }

        SearchResponse searchResponse = searchService.search(question);
        if (searchResponse.results().isEmpty()) {
            log.info("답변 생성: 검색 결과 없음, 근거 부족 응답 반환");
            return noEvidenceResponse();
        }

        Context context = contextBuilder.build(searchResponse.results(), llmProperties.maxContextChars());
        if (context.text().isEmpty()) {
            log.info("답변 생성: context 길이 제한으로 포함된 근거 없음, 근거 부족 응답 반환");
            return noEvidenceResponse();
        }

        Prompt prompt = promptBuilder.build(question, context.text());
        String answer = llmClient.complete(prompt);

        List<AnswerSource> sources = context.includedResults().stream()
                .map(AnswerService::toSource)
                .toList();

        log.info("답변 생성 완료: questionLength={}, contextItemCount={}, answerLength={}",
                question.length(), sources.size(), answer.length());
        return new AnswerResponse(answer, sources);
    }

    private static AnswerResponse noEvidenceResponse() {
        return new AnswerResponse(NO_EVIDENCE_ANSWER, List.of());
    }

    private static AnswerSource toSource(SearchResult result) {
        return new AnswerSource(result.documentId(), result.title(), result.source(), result.chunkIndex(), result.distance());
    }
}
