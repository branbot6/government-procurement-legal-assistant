#!/usr/bin/env python3
import argparse
import time
from typing import List, Union

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import uvicorn


class EmbeddingRequest(BaseModel):
    model: str | None = None
    input: Union[str, List[str]]


app = FastAPI(title="Local BGE Embedding Server")
encoder: SentenceTransformer | None = None
loaded_model_name: str = ""


@app.get("/v1/models")
def list_models():
    return {
        "object": "list",
        "data": [
            {
                "id": loaded_model_name,
                "object": "model",
                "owned_by": "local",
            }
        ],
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest):
    assert encoder is not None, "model not loaded"

    texts = req.input if isinstance(req.input, list) else [req.input]
    texts = [t if isinstance(t, str) else str(t) for t in texts]

    started = time.time()
    vectors = encoder.encode(texts, normalize_embeddings=True)
    elapsed_ms = int((time.time() - started) * 1000)

    data = []
    for i, vec in enumerate(vectors):
        data.append(
            {
                "object": "embedding",
                "index": i,
                "embedding": vec.tolist(),
            }
        )

    token_est = sum(max(1, len(t) // 2) for t in texts)
    return {
        "object": "list",
        "data": data,
        "model": req.model or loaded_model_name,
        "usage": {
            "prompt_tokens": token_est,
            "total_tokens": token_est,
        },
        "meta": {"latency_ms": elapsed_ms},
    }


def main():
    global encoder, loaded_model_name

    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="BAAI/bge-large-zh-v1.5")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()

    loaded_model_name = args.model
    print(f"[local-bge] loading model: {loaded_model_name}")
    encoder = SentenceTransformer(loaded_model_name)
    print("[local-bge] model loaded")

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
