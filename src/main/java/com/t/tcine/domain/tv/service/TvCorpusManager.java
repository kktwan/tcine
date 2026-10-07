package com.t.tcine.domain.tv.service;

import com.t.tcine.domain.search.corpus.AbstractCorpusManager;
import io.qdrant.client.QdrantClient;
import org.springframework.stereotype.Component;

/** 시리즈 Qdrant 컬렉션의 인메모리 코퍼스 캐시 */
@Component
public class TvCorpusManager extends AbstractCorpusManager {

    public TvCorpusManager(QdrantClient qdrant, TvIndexService indexService) {
        super(qdrant, indexService.collectionName(), indexService::count, "시리즈", "tv-corpus");
    }
}
