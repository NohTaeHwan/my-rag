package com.nohtaehwan.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.nohtaehwan.rag.answer.AnswerResponse;
import com.nohtaehwan.rag.answer.AnswerService;
import com.nohtaehwan.rag.answer.AnswerSource;
import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.indexing.mapper.ChunkRow;
import com.nohtaehwan.rag.indexing.mapper.DocumentChunkMapper;
import com.nohtaehwan.rag.indexing.mapper.DocumentInsertParameter;
import com.nohtaehwan.rag.indexing.mapper.DocumentMapper;
import com.nohtaehwan.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.nohtaehwan.rag.llm.LlmClient;
import com.nohtaehwan.rag.llm.Prompt;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;

/**
 * Mockito로는 증명할 수 없는, 실제 PostgreSQL(+pgvector)에 대해서만 확인 가능한 검색 동작과
 * 사용자·Knowledge Base 범위 격리를 검증한다. {@code isolated-integration} tag로
 * {@code ./gradlew isolatedIntegrationTest}에서만 실행되고, insert·cleanup 전에
 * {@link IsolatedDatabaseGuard}가 격리 통합 DB인지 확인한다.
 *
 * <p>fixture는 실행마다 새로 만든 사용자·KB·문서(생성 id 캡처)와 UUID 기반 source만 사용한다.
 * 시드 KB(id=1)에는 쓰지 않으며, cleanup은 이 실행에서 생성한 문서·KB·사용자 id로만 삭제한다.
 *
 * <p>고정된 벡터(모든 차원이 동일한 값 / 부호가 번갈아 나오는 값 / 정반대 값)를 써서 query
 * {@code constant(0.1)}과의 cosine distance가 각각 정확히 0, 1, 2가 되도록 만든다. Embedding·LLM은
 * mock이며 실제 외부 호출은 없다.
 */
@Tag("isolated-integration")
@SpringBootTest
@Import(IsolatedDatabaseGuard.GuardedFlywayConfig.class)
class SearchPersistenceIntegrationTest {

    private static final int DIMENSION = 1024;
    private static final Long USER_1 = 1L;

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocumentChunkMapper documentChunkMapper;
    @Autowired
    private SearchMapper searchMapper;
    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private SearchService searchService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private DataSource dataSource;
    // 테스트 셋업/정리 전용(사용자·KB 생성, 비INDEXED 문서 insert, 생성 id 기준 삭제).
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingClient embeddingClient;
    @MockitoBean
    private LlmClient llmClient;

    private final String runId = UUID.randomUUID().toString();
    private final String testSource = "search-integration-test/" + runId + ".md";

    // cleanup 대상: 이 실행에서 생성한 id만 담는다.
    private final List<Long> createdDocumentIds = new ArrayList<>();
    private final List<Long> createdKnowledgeBaseIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private boolean guardPassed;

    /** 사용자 1 소유의 검색 대상 KB. */
    private Long kbA;

    @BeforeEach
    void setUp() {
        // insert 전에 격리 통합 DB인지 확인한다. 실패하면 guardPassed=false라 cleanup도 생략된다.
        IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
        guardPassed = true;
        kbA = createKnowledgeBase(USER_1, "kbA");
    }

    @AfterEach
    void cleanUp() {
        if (!guardPassed) {
            return;
        }
        IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
        // 문서(→Chunk CASCADE) → KB → 사용자 순으로 FK를 지키며 생성 id만 삭제한다.
        createdDocumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM tb_document WHERE id = ?", id));
        createdKnowledgeBaseIds.forEach(id -> jdbcTemplate.update("DELETE FROM tb_knowledge_base WHERE id = ?", id));
        createdUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", id));
    }

    @Test
    void search_관련성_높은_Chunk가_더_낮은_distance로_먼저_반환된다() {
        insertIndexedDocument(USER_1, kbA, List.of(
                new ChunkRow("close content", 0, literal(constantVector(0.1))),
                new ChunkRow("middle content", 1, literal(alternatingVector())),
                new ChunkRow("far content", 2, literal(constantVector(-0.1)))));

        List<SearchRow> results = searchMapper.search(literal(constantVector(0.1)), USER_1, kbA, 10);

        assertThat(results).extracting(SearchRow::content)
                .containsExactly("close content", "middle content", "far content");
        assertThat(results.get(0).distance()).isCloseTo(0.0, within(1e-3));
    }

    @Test
    void search_topK가_1이면_가장_가까운_결과_1건만_반환하고_실제_저장값과_일치한다() {
        insertIndexedDocument(USER_1, kbA, List.of(
                new ChunkRow("close content", 0, literal(constantVector(0.1))),
                new ChunkRow("far content", 1, literal(constantVector(-0.1)))));

        List<SearchRow> results = searchMapper.search(literal(constantVector(0.1)), USER_1, kbA, 1);

        assertThat(results).hasSize(1);
        SearchRow top = results.get(0);
        assertThat(top.title()).isEqualTo("검색 테스트 문서");
        assertThat(top.source()).isEqualTo(testSource);
        assertThat(top.content()).isEqualTo("close content");
        assertThat(top.chunkIndex()).isEqualTo(0);
    }

    @Test
    void search_query_벡터_차원이_다르면_실제_DB_오류가_발생한다() {
        // 비교할 Chunk가 없으면 거리 연산을 평가하지 않아 차원 오류가 발생하지 않는다.
        insertIndexedDocument(USER_1, kbA,
                List.of(new ChunkRow("dimension check", 0, literal(constantVector(0.1)))));
        assertThatThrownBy(() -> searchMapper.search(literal(List.of(0.1, 0.2, 0.3)), USER_1, kbA, 5))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void search_범위_밖_Chunk가_더_가까워도_topK_1에서_범위_안_문서만_반환한다() {
        insertIndexedDocument(USER_1, kbA, List.of(new ChunkRow("in-scope", 0, literal(alternatingVector()))));
        // 같은 사용자의 다른 KB, 다른 사용자의 KB에 같은 source로 정확히 일치하는(distance 0) Chunk를 둔다.
        insertIndexedDocument(USER_1, createKnowledgeBase(USER_1, "kbB"),
                List.of(new ChunkRow("other-kb", 0, literal(constantVector(0.1)))));
        Long user2 = createUser();
        insertIndexedDocument(user2, createKnowledgeBase(user2, "kbC"),
                List.of(new ChunkRow("other-user", 0, literal(constantVector(0.1)))));

        List<SearchRow> results = searchMapper.search(literal(constantVector(0.1)), USER_1, kbA, 1);

        assertThat(results).extracting(SearchRow::content).containsExactly("in-scope");
    }

    @Test
    void search_INDEXED가_아닌_문서의_Chunk는_제외한다() {
        Long pendingDocumentId = jdbcTemplate.queryForObject(
                "INSERT INTO tb_document (user_id, knowledge_base_id, title, source, index_status) "
                        + "VALUES (?, ?, ?, ?, 'PENDING') RETURNING id",
                Long.class, USER_1, kbA, "대기 문서", testSource);
        createdDocumentIds.add(pendingDocumentId);
        documentChunkMapper.insertAll(pendingDocumentId,
                List.of(new ChunkRow("pending", 0, literal(constantVector(0.1)))));

        List<SearchRow> results = searchMapper.search(literal(constantVector(0.1)), USER_1, kbA, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void existsByIdAndUserId_본인_KB만_true이고_타인_소유와_없는_KB는_false다() {
        Long user2 = createUser();
        Long otherUsersKb = createKnowledgeBase(user2, "kbC");
        Long missingKb = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM tb_knowledge_base", Long.class);

        assertThat(knowledgeBaseMapper.existsByIdAndUserId(kbA, USER_1)).isTrue();
        assertThat(knowledgeBaseMapper.existsByIdAndUserId(otherUsersKb, USER_1)).isFalse();
        assertThat(knowledgeBaseMapper.existsByIdAndUserId(missingKb, USER_1)).isFalse();
    }

    @Test
    void searchService_타인_소유_KB는_404이고_embedding을_호출하지_않는다() {
        Long user2 = createUser();
        Long otherUsersKb = createKnowledgeBase(user2, "kbC");

        assertThatThrownBy(() -> searchService.search(USER_1, otherUsersKb, "질문"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(embeddingClient);
    }

    @Test
    void 유효한_KB에_범위_밖_Chunk만_있으면_검색은_빈_목록이고_답변은_LLM을_호출하지_않는다() {
        insertIndexedDocument(USER_1, createKnowledgeBase(USER_1, "kbB"),
                List.of(new ChunkRow("other-kb", 0, literal(constantVector(0.1)))));
        when(embeddingClient.embed(anyString())).thenReturn(constantVector(0.1));

        SearchResponse searchResponse = searchService.search(USER_1, kbA, "질문");
        AnswerResponse answerResponse = answerService.answer(USER_1, kbA, "질문");

        assertThat(searchResponse.results()).isEmpty();
        assertThat(answerResponse.sources()).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void answerService_범위_밖_Chunk가_더_가까워도_prompt와_sources에는_KB_A_근거만_담긴다() {
        String sourceA = "search-integration-test/kb-a/" + runId + ".md";
        String sourceB = "search-integration-test/kb-b/" + runId + ".md";
        String sourceC = "search-integration-test/kb-c/" + runId + ".md";
        // KB A 근거는 distance 1, 같은 사용자 KB B·다른 사용자 KB C 근거는 distance 0으로 더 가깝다.
        Long documentA = insertIndexedDocument(USER_1, kbA, "KB A 문서", sourceA,
                List.of(new ChunkRow("KB-A 전용 근거 " + runId, 0, literal(alternatingVector()))));
        insertIndexedDocument(USER_1, createKnowledgeBase(USER_1, "kbB"), "KB B 문서", sourceB,
                List.of(new ChunkRow("KB-B 범위 밖 근거 " + runId, 0, literal(constantVector(0.1)))));
        Long user2 = createUser();
        insertIndexedDocument(user2, createKnowledgeBase(user2, "kbC"), "KB C 문서", sourceC,
                List.of(new ChunkRow("KB-C 범위 밖 근거 " + runId, 0, literal(constantVector(0.1)))));
        when(embeddingClient.embed(anyString())).thenReturn(constantVector(0.1));
        when(llmClient.complete(any(Prompt.class))).thenReturn("KB A 근거 기반 답변");

        AnswerResponse response = answerService.answer(USER_1, kbA, "질문");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(llmClient).complete(prompt.capture());
        assertThat(prompt.getValue().userMessage())
                .contains("KB-A 전용 근거 " + runId, sourceA)
                .doesNotContain("KB-B 범위 밖 근거", sourceB, "KB-C 범위 밖 근거", sourceC);
        assertThat(response.answer()).isEqualTo("KB A 근거 기반 답변");
        assertThat(response.sources()).extracting(AnswerSource::documentId, AnswerSource::source)
                .containsExactly(tuple(documentA, sourceA));
    }

    private Long createUser() {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tb_user (display_name) VALUES (?) RETURNING id", Long.class, "격리 테스트 사용자 " + runId);
        createdUserIds.add(id);
        return id;
    }

    private Long createKnowledgeBase(Long userId, String name) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tb_knowledge_base (user_id, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, userId, "격리 테스트 " + name + " " + runId);
        createdKnowledgeBaseIds.add(id);
        return id;
    }

    private void insertIndexedDocument(Long userId, Long knowledgeBaseId, List<ChunkRow> chunks) {
        insertIndexedDocument(userId, knowledgeBaseId, "검색 테스트 문서", testSource, chunks);
    }

    private Long insertIndexedDocument(Long userId, Long knowledgeBaseId, String title, String source, List<ChunkRow> chunks) {
        DocumentInsertParameter parameter = new DocumentInsertParameter(userId, knowledgeBaseId, title, source);
        documentMapper.insert(parameter);
        createdDocumentIds.add(parameter.getId());
        documentChunkMapper.insertAll(parameter.getId(), chunks);
        return parameter.getId();
    }

    private static List<Double> constantVector(double value) {
        return IntStream.range(0, DIMENSION).mapToObj(i -> value).toList();
    }

    private static List<Double> alternatingVector() {
        return IntStream.range(0, DIMENSION).mapToObj(i -> i % 2 == 0 ? 0.1 : -0.1).toList();
    }

    private static String literal(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
