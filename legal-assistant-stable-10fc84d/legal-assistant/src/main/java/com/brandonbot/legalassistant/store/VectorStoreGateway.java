package com.brandonbot.legalassistant.store;

import java.util.List;

import com.brandonbot.legalassistant.model.DocumentChunk;

public interface VectorStoreGateway {

    void upsertChunks(List<DocumentChunk> chunks);

    List<DocumentChunkScore> retrieve(String query, int topK);

    void clear();

    record DocumentChunkScore(DocumentChunk chunk, double score) {
    }
}
