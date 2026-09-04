package com.nohtaehwan.rag.indexing;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.nohtaehwan.rag.exception.RagException;

import lombok.extern.slf4j.Slf4j;

/**
 * tb_document 테이블 저장과 문서 단위 재색인을 담당한다.
 *
 * <p>{@link #reindex}는 같은 sourceKey의 기존 문서를 지우고 새로 저장하는 전체 과정을 하나의
 * 트랜잭션으로 묶는다. Service가 아니라 이 클래스(별도 Bean)에 {@code @Transactional}을 두는 이유는,
 * Service 안에서 자기 자신의 메서드를 호출하면 Spring 프록시 기반 AOP가 트랜잭션을 적용하지 못하는
 * self-invocation 문제를 피하기 위함이다. Embedding 생성(외부 API 호출)은 이 메서드 밖, Service에서
 * 먼저 끝내고, 여기서는 이미 계산된 embedding만 저장한다.
 *
 * <p>커밋/롤백 범위: 삭제·insert·Chunk insert가 모두 이 메서드 하나의 트랜잭션이므로, 어느 단계에서든
 * 예외가 나면 그 문서에 대한 변경 전체가 롤백되고 기존 문서는 그대로 남는다. 문서를 여러 건 반복
 * 호출하는 쪽(Service)에서 앞선 호출이 이미 커밋된 뒤 다음 호출이 실패해도, 그 커밋은 롤백되지
 * 않는다 — 색인은 문서 단위로 commit되는 부분 성공(partial success)을 허용하는 설계다.
 *
 * <p>{@code tb_document.source}는 UNIQUE 제약(V2 migration)이 걸려 있어 같은 source의 문서는
 * 항상 최대 1건만 존재한다. 삭제 대상도 그 1건(또는 0건)으로 한정된다. 같은 source로 두 재색인
 * 요청이 동시에 실행되면, 먼저 커밋한 쪽이 성공하고 나중 트랜잭션은 UNIQUE 제약 위반
 * (unique_violation)으로 insert가 실패해 그 트랜잭션 전체가 롤백된다 — 중복 행은 생성되지 않고,
 * 대신 나중 요청이 실패로 끝난다(별도 락은 걸지 않는다).
 *
 * <p>sourceKey가 null이거나 빈 문자열이면 저장하지 않고 {@link RagException}을 던진다.
 * {@link com.nohtaehwan.rag.document.DocumentSourceReader}가 만드는 정상 경로에서는 발생하지
 * 않지만, source 없이 저장하면 UNIQUE 제약과 재색인 조회(WHERE source = ?)의 전제가 깨지므로
 * 방어적으로 막는다.
 */
@Slf4j
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentRepository(JdbcTemplate jdbcTemplate, DocumentChunkRepository documentChunkRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentChunkRepository = documentChunkRepository;
    }

    /**
     * 같은 sourceKey(source)의 기존 문서를 삭제하고 새 문서+Chunk를 저장한다.
     * 기존 tb_document 삭제 시 FK ON DELETE CASCADE로 기존 tb_document_chunk도 함께 삭제된다.
     *
     * @param title     문서 제목
     * @param sourceKey 문서 source key (재색인 대상 판별 기준)
     * @param chunks    저장할 Chunk와 embedding 목록
     */
    @Transactional
    public void reindex(String title, String sourceKey, List<EmbeddedChunk> chunks) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new RagException("문서 source는 비어 있을 수 없습니다.");
        }

        jdbcTemplate.update("DELETE FROM tb_document WHERE source = ?", sourceKey);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tb_document (title, source) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, sourceKey);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new RagException("문서 저장 후 생성된 id를 확인하지 못했습니다: sourceKey=" + sourceKey);
        }

        documentChunkRepository.insertAll(key.longValue(), chunks);
        log.info("문서 재색인 완료: sourceKey={}, chunkCount={}", sourceKey, chunks.size());
    }
}
