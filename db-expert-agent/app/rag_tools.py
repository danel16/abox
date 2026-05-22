# ruff: noqa
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import uuid

from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams

QDRANT_URL = os.environ.get("QDRANT_URL", "http://qdrant-qdrant.qdrant.svc.cluster.local:6333")
EMBEDDING_MODEL = os.environ.get("EMBEDDING_MODEL", "text-embedding-3-small")
# text-embedding-3-small produces 1536-dim vectors
VECTOR_SIZE = 1536

_qdrant: QdrantClient | None = None
_openai_client: OpenAI | None = None


def _get_qdrant() -> QdrantClient:
    global _qdrant
    if _qdrant is None:
        _qdrant = QdrantClient(url=QDRANT_URL)
    return _qdrant


def _get_openai() -> OpenAI:
    global _openai_client
    if _openai_client is None:
        base_url = os.environ.get("EMBEDDING_BASE_URL", "https://api.openai.com/v1")
        _openai_client = OpenAI(
            api_key=os.environ.get("EMBEDDING_API_KEY"),
            base_url=base_url,
        )
    return _openai_client


def _embed(text: str) -> list[float]:
    response = _get_openai().embeddings.create(input=text, model=EMBEDDING_MODEL)
    return response.data[0].embedding


def _ensure_collection(collection: str) -> None:
    client = _get_qdrant()
    existing = {c.name for c in client.get_collections().collections}
    if collection not in existing:
        client.create_collection(
            collection_name=collection,
            vectors_config=VectorParams(size=VECTOR_SIZE, distance=Distance.COSINE),
        )


def search_knowledge_base(query: str, collection: str = "knowledge", limit: int = 5) -> str:
    """Search the RAG knowledge base for context relevant to a query.

    Use this before answering questions that may benefit from stored documentation,
    schema notes, runbooks, or any previously ingested context.

    Args:
        query: Natural-language search query.
        collection: Qdrant collection name to search. Defaults to "knowledge".
        limit: Maximum number of results to return (1–20).

    Returns:
        Relevant text chunks separated by dividers, or a message if nothing was found.
    """
    try:
        _ensure_collection(collection)
        vector = _embed(query)
        results = _get_qdrant().query_points(
            collection_name=collection,
            query=vector,
            limit=min(max(limit, 1), 20),
        ).points
        if not results:
            return "No relevant documents found in the knowledge base."
        chunks = []
        for r in results:
            source = r.payload.get("source", "unknown")
            text = r.payload.get("text", "")
            score = round(r.score, 3)
            chunks.append(f"[source: {source} | score: {score}]\n{text}")
        return "\n\n---\n\n".join(chunks)
    except Exception as e:
        return f"Knowledge base search failed: {e}"


def ingest_document(text: str, source: str = "manual", collection: str = "knowledge") -> str:
    """Ingest a text document into the RAG knowledge base for future retrieval.

    Use this when the user asks you to remember a piece of information, store schema
    documentation, or save runbook content for later use.

    Args:
        text: The document text to store (keep under ~6000 words for best embedding quality).
        source: A label identifying where this text came from (e.g. filename, URL, or "manual").
        collection: Qdrant collection to store into. Defaults to "knowledge".

    Returns:
        Confirmation message with the assigned document ID.
    """
    try:
        _ensure_collection(collection)
        vector = _embed(text)
        point_id = str(uuid.uuid4())
        _get_qdrant().upsert(
            collection_name=collection,
            points=[
                PointStruct(
                    id=point_id,
                    vector=vector,
                    payload={"text": text, "source": source},
                )
            ],
        )
        return f"Document ingested (id={point_id}, source={source}, collection={collection})."
    except Exception as e:
        return f"Ingestion failed: {e}"
