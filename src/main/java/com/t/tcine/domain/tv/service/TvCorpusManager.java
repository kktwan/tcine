package com.t.tcine.domain.tv.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.t.tcine.domain.search.util.RankingEngine;

@Component
public class TvCorpusManager {

    private static final Logger log = LoggerFactory.getLogger(TvCorpusManager.class);
    private static final long CORPUS_CACHE_TTL_MILLIS = 15 * 60 * 1000L;
    private static final int SCROLL_PAGE_SIZE = 1000;

    private final QdrantClient qdrant;
    private final String collection;
    private final TvIndexService indexService;

    private volatile List<Document> cachedCorpus = List.of();
    private volatile long corpusExpiresAt = 0L;
    private volatile long corpusIndexedCount = -1L;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "tv-corpus");
        thread.setDaemon(true);
        return thread;
    });

    public TvCorpusManager(QdrantClient qdrant, TvIndexService indexService) {
        this.qdrant = qdrant;
        this.collection = indexService.collectionName();
        this.indexService = indexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCorpus() {
        CompletableFuture.runAsync(() -> {
            try {
                getCorpus();
                log.info("시리즈 코퍼스 인메모리 캐시 예열 완료 ({}편)", cachedCorpus.size());
            } catch (Exception e) {
                log.debug("시리즈 코퍼스 초기 예열 건너뜀: {}", e.getMessage());
            }
        }, pool);
    }

    public List<Document> getCorpus() throws Exception {
        long now = System.currentTimeMillis();
        List<Document> current = cachedCorpus;
        if (!current.isEmpty() && corpusExpiresAt > now) return current;
        synchronized (this) {
            if (!cachedCorpus.isEmpty() && corpusExpiresAt > System.currentTimeMillis()) return cachedCorpus;
            if (!qdrant.collectionExistsAsync(collection).get()) throw new IllegalStateException("시리즈 색인이 아직 없어요.");
            long currentCount = indexService.count();
            if (!cachedCorpus.isEmpty() && currentCount > 0 && currentCount == corpusIndexedCount) {
                corpusExpiresAt = System.currentTimeMillis() + CORPUS_CACHE_TTL_MILLIS;
                return cachedCorpus;
            }
            List<Document> loaded = scrollAllDocuments();
            if (!loaded.isEmpty()) {
                cachedCorpus = loaded;
                corpusIndexedCount = currentCount > 0 ? currentCount : loaded.size();
                corpusExpiresAt = System.currentTimeMillis() + CORPUS_CACHE_TTL_MILLIS;
            }
            return loaded;
        }
    }

    private List<Document> scrollAllDocuments() throws Exception {
        List<Document> documents = new ArrayList<>();
        io.qdrant.client.grpc.Common.PointId offset = null;
        while (true) {
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(SCROLL_PAGE_SIZE)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true));
            if (offset != null) request.setOffset(offset);
            ScrollResponse response = qdrant.scrollAsync(request.build()).get();
            for (RetrievedPoint point : response.getResultList()) {
                Map<String, Object> metadata = new HashMap<>();
                point.getPayloadMap().forEach((key, value) -> {
                    if (!"doc_content".equals(key)) metadata.put(key, payloadValue(value));
                });
                String content = point.containsPayload("doc_content") ? point.getPayloadOrThrow("doc_content").getStringValue() : "";
                String keywords = RankingEngine.extractFieldFromContent(content, "키워드:");
                if (!keywords.isEmpty()) metadata.put("keywords", keywords);
                String tagline = RankingEngine.extractFieldFromContent(content, "한줄 소개:");
                if (!tagline.isEmpty()) metadata.put("tagline", tagline);
                documents.add(new Document(point.getId().getUuid(), content, metadata));
            }
            if (!response.hasNextPageOffset() || response.getResultCount() == 0) break;
            offset = response.getNextPageOffset();
        }
        return documents;
    }

    private static Object payloadValue(Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            default -> "";
        };
    }
}
