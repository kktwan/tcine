package com.t.tcine.domain.movie.service;

import com.t.tcine.domain.search.corpus.AbstractCorpusManager;
import io.qdrant.client.QdrantClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 영화 Qdrant 컬렉션의 인메모리 코퍼스 캐시 */
@Component
public class MovieCorpusManager extends AbstractCorpusManager {

    public MovieCorpusManager(QdrantClient qdrant, MovieIndexService indexService,
                              @Value("${spring.ai.vectorstore.qdrant.collection-name:tcine-movies-openai}") String collection) {
        super(qdrant, collection, indexService::count, "영화", "movie-corpus");
    }
}
