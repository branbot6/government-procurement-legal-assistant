package com.brandonbot.legalassistant.store;

import java.util.List;

import org.springframework.stereotype.Component;

import com.brandonbot.legalassistant.model.DocumentChunk;

/**
 * Placeholder for production OpenSearch hybrid retrieval.
 * Keep interface-compatible with InMemoryVectorStore so you can switch by profile later.
 */
@Component
public class OpenSearchVectorStore {

    public void upsertChunks(List<DocumentChunk> chunks) {
        // TODO: index chunks with text + embedding + metadata.
    }

    public List<VectorStoreGateway.DocumentChunkScore> retrieve(String query, int topK) {
        // TODO: implement BM25 + vector hybrid query + rerank.
        return List.of();
    }
}
