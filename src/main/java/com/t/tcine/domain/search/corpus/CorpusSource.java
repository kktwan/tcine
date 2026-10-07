package com.t.tcine.domain.search.corpus;

import org.springframework.ai.document.Document;

import java.util.List;

/** 키워드 검색 대상이 되는 작품 전체(인메모리 코퍼스) */
public interface CorpusSource {

    List<Document> getCorpus() throws Exception;
}
