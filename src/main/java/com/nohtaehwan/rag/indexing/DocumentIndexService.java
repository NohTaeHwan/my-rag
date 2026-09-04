package com.nohtaehwan.rag.indexing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.document.DocumentContent;
import com.nohtaehwan.rag.document.DocumentSourceReader;
import com.nohtaehwan.rag.document.MarkdownChunker;
import com.nohtaehwan.rag.document.MarkdownParser;
import com.nohtaehwan.rag.document.SourceFile;
import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.embedding.EmbeddingProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Markdown 문서를 읽어 파싱→Chunking→Embedding→저장까지 전체 색인 과정을 진행한다.
 *
 * <p>Embedding 생성(외부 API 호출)은 DB 트랜잭션 밖에서 순차적으로 수행하며, 실패하면 재시도 없이
 * 즉시 예외를 전파한다(fail-fast). 문서별 저장은 {@link DocumentRepository#reindex}가 트랜잭션으로 묶는다.
 *
 * <p><b>부분 성공(partial success) 정책</b> — 문서는 목록 순서대로 하나씩 처리되고, 문서 하나가
 * 끝날 때마다 {@link DocumentRepository#reindex}가 그 문서만의 트랜잭션으로 즉시 commit한다.
 * <ul>
 *   <li>색인은 문서 단위로 commit된다.</li>
 *   <li>뒤에 오는 문서가 실패해도 앞서 이미 commit된 문서는 rollback되지 않는다.</li>
 *   <li>어떤 문서의 Embedding 생성이 실패하면 그 문서는 저장되지 않고(reindex 자체가 호출되지 않음),
 *       예외가 그대로 전파되어 이후 문서는 시도조차 되지 않는다.</li>
 *   <li>이 API는 응답을 성공(200) 또는 실패(500) 둘 중 하나로만 반환하지만, 실패 응답을 받아도
 *       내부적으로는 일부 문서가 이미 새로 저장된 부분 성공 상태로 남아 있을 수 있다. 실패 시 어느
 *       문서까지 처리됐는지는 서버 로그(sourceKey 기준)로 확인해야 한다.</li>
 * </ul>
 *
 * <p><b>빈 Markdown 문서 정책</b> — Chunk가 0개인 문서도 색인 대상에서 제외하지 않는다.
 * {@code tb_document} 행은 저장하고(문서 수에는 포함), {@code tb_document_chunk}는 저장하지
 * 않는다(Chunk 수에는 포함하지 않음). Chunk가 없으므로 이후 검색 단계에서는 결과에 나타나지
 * 않는다. MVP 단순성을 위한 의도된 동작이며, 빈 문서를 색인 대상에서 제외하는 정책으로 바꾸려면
 * 이 클래스와 {@link DocumentIndexResponse} 집계 기준을 함께 바꿔야 한다.
 */
@Slf4j
@Service
public class DocumentIndexService {

    private final DocumentSourceReader documentSourceReader;
    private final MarkdownParser markdownParser;
    private final MarkdownChunker markdownChunker;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;
    private final DocumentRepository documentRepository;

    public DocumentIndexService(
            DocumentSourceReader documentSourceReader,
            MarkdownParser markdownParser,
            MarkdownChunker markdownChunker,
            EmbeddingClient embeddingClient,
            EmbeddingProperties embeddingProperties,
            DocumentRepository documentRepository
    ) {
        this.documentSourceReader = documentSourceReader;
        this.markdownParser = markdownParser;
        this.markdownChunker = markdownChunker;
        this.embeddingClient = embeddingClient;
        this.embeddingProperties = embeddingProperties;
        this.documentRepository = documentRepository;
    }

    /**
     * 설정된 디렉터리의 Markdown 파일 전체를 색인한다. 대상 파일이 없으면 0건 성공으로 처리한다.
     *
     * @return 처리한 문서 수, 생성한 Chunk 총 수, embedding 차원
     */
    public DocumentIndexResponse indexDocuments() {
        List<SourceFile> files = documentSourceReader.readAll();

        int documentCount = 0;
        int chunkCount = 0;

        for (SourceFile file : files) {
            DocumentContent parsed = markdownParser.parse(file.sourceKey(), file.content());
            List<DocumentChunk> chunks = markdownChunker.chunk(parsed);

            List<EmbeddedChunk> embeddedChunks = new ArrayList<>(chunks.size());
            for (DocumentChunk chunk : chunks) {
                embeddedChunks.add(new EmbeddedChunk(chunk, embeddingClient.embed(chunk.content())));
            }

            documentRepository.reindex(parsed.title(), file.sourceKey(), embeddedChunks);

            documentCount++;
            chunkCount += chunks.size();
            log.info("문서 색인 완료: sourceKey={}, chunkCount={}", file.sourceKey(), chunks.size());
        }

        log.info("전체 색인 완료: documentCount={}, chunkCount={}", documentCount, chunkCount);
        return new DocumentIndexResponse(documentCount, chunkCount, embeddingProperties.dimension());
    }
}
