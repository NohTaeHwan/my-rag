package com.nohtaehwan.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

import com.nohtaehwan.rag.indexing.mapper.ChunkRow;
import com.nohtaehwan.rag.indexing.mapper.DocumentChunkMapper;
import com.nohtaehwan.rag.indexing.mapper.DocumentInsertParameter;
import com.nohtaehwan.rag.indexing.mapper.DocumentMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;

/**
 * Mockito로는 증명할 수 없는, 실제 PostgreSQL(+pgvector)에 대해서만 확인 가능한 검색 동작을 검증한다.
 * {@link com.nohtaehwan.rag.indexing.DocumentPersistenceIntegrationTest}와 같이 {@code isolated-integration}
 * tag로 {@code ./gradlew isolatedIntegrationTest}에서만 실행되고, migrate·insert·cleanup 전에
 * {@link IsolatedDatabaseGuard}가 격리 통합 DB인지 확인한다.
 *
 * <p>테스트 실행마다 UUID 기반 고유 source를 사용해 기존 DB 데이터와 충돌하지 않도록 격리한다.
 * 저장·검색·cleanup 모두 같은 실행에서 생성한 {@code testSource} 하나만 사용하며, cleanup은 그
 * source에 대해서만 수행한다 — 기존에 실제로 색인된 다른 데이터는 전체 테이블 삭제나 사전 조회 없이
 * 건드리지 않는다.
 *
 * <p>고정된 벡터(모든 차원이 동일한 값 / 부호가 번갈아 나오는 값 / 정반대 값)를 써서 cosine distance가
 * 각각 정확히 0, 1, 2가 되도록 만든다 — 로컬 DB에 이미 있는 다른(실제 색인된) 데이터가 섞여 있어도
 * 이 세 값의 상대적 순서는 수학적으로 항상 보장된다.
 */
@Tag("isolated-integration")
@SpringBootTest
@Import(IsolatedDatabaseGuard.GuardedFlywayConfig.class)
class SearchPersistenceIntegrationTest {

    private static final int DIMENSION = 1024;

    // V3 migration이 시드하는 개발용 사용자/기본 Knowledge Base. 검색 SQL의 사용자·KB 범위
    // 제한은 이 작업 단위의 범위 밖이라(SearchMapper 미변경) 저장 측 scope만 맞춰둔다.
    private static final Long USER_ID = 1L;
    private static final Long KNOWLEDGE_BASE_ID = 1L;

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocumentChunkMapper documentChunkMapper;
    @Autowired
    private SearchMapper searchMapper;
    @Autowired
    private DataSource dataSource;

    private String testSource;

    @BeforeEach
    void setUp() {
        // insert 전에 격리 통합 DB인지 확인한다. 아니면 실패하고 testSource도 null로 남아 cleanup이 생략된다.
        IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
        testSource = "search-integration-test/" + UUID.randomUUID() + ".md";

        DocumentInsertParameter parameter =
                new DocumentInsertParameter(USER_ID, KNOWLEDGE_BASE_ID, "검색 테스트 문서", testSource);
        documentMapper.insert(parameter);
        Long documentId = parameter.getId();

        documentChunkMapper.insertAll(documentId, List.of(
                new ChunkRow("close content", 0, toVectorLiteral(constantVector(0.1))),
                new ChunkRow("middle content", 1, toVectorLiteral(alternatingVector())),
                new ChunkRow("far content", 2, toVectorLiteral(constantVector(-0.1)))));
    }

    @AfterEach
    void cleanUp() {
        if (testSource != null) {
            IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
            documentMapper.deleteByUserIdAndKnowledgeBaseIdAndSource(USER_ID, KNOWLEDGE_BASE_ID, testSource);
        }
    }

    @Test
    void search_관련성_높은_Chunk가_더_낮은_distance로_먼저_반환된다() {
        String queryLiteral = toVectorLiteral(constantVector(0.1));

        List<SearchRow> results = searchMapper.search(queryLiteral, 10);

        assertThat(results).extracting(SearchRow::content)
                .containsSubsequence("close content", "middle content", "far content");
        assertThat(results.get(0).distance()).isCloseTo(0.0, within(1e-3));
    }

    @Test
    void search_topK가_1이면_가장_가까운_결과_1건만_반환하고_실제_저장값과_일치한다() {
        String queryLiteral = toVectorLiteral(constantVector(0.1));

        List<SearchRow> results = searchMapper.search(queryLiteral, 1);

        assertThat(results).hasSize(1);
        SearchRow top = results.get(0);
        assertThat(top.title()).isEqualTo("검색 테스트 문서");
        assertThat(top.source()).isEqualTo(testSource);
        assertThat(top.content()).isEqualTo("close content");
        assertThat(top.chunkIndex()).isEqualTo(0);
    }

    @Test
    void search_query_벡터_차원이_다르면_실제_DB_오류가_발생한다() {
        String wrongDimensionLiteral = toVectorLiteral(List.of(0.1, 0.2, 0.3));

        assertThatThrownBy(() -> searchMapper.search(wrongDimensionLiteral, 5))
                .isInstanceOf(DataAccessException.class);
    }

    private static List<Double> constantVector(double value) {
        return IntStream.range(0, DIMENSION).mapToObj(i -> value).toList();
    }

    private static List<Double> alternatingVector() {
        return IntStream.range(0, DIMENSION).mapToObj(i -> i % 2 == 0 ? 0.1 : -0.1).toList();
    }

    private static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
