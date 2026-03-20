package com.brandonbot.legalassistant.embedding;

import java.util.List;

public interface EmbeddingClient {

    boolean available();

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);
}
