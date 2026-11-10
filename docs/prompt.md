You are an expert full-scale system architect and technical interviewer. I need you to write a comprehensive, highly detailed **Technical Architecture & Interview Revision Guide** for our project **AetherSync AI** (formerly DocMind).

This document will be used by me to prepare for technical interviews so I can confidently explain every layer of the system—from backend architecture down to database design, vector embeddings, Confluence ingestion pipelines, and chat memory.

Please write a structured, crystal-clear guide covering the following sections in depth:

---

## 1. Executive Summary & High-Level Architecture
- **What is AetherSync AI?** Explain its core purpose as a hybrid RAG (Retrieval-Augmented Generation) system combining personal user file uploads with automated Confluence space (`MFS`) synchronization.
- **Tech Stack Overview**: Spring Boot (backend), Spring AI, PostgreSQL with `pgvector`, Gemini API (`gemini-3.8-flash` for chat, `gemini-embedding-001` for 768-dim embeddings), and Next.js / Tailwind CSS (frontend).
- **System Architecture Diagram / Flow**: Describe the end-to-end request lifecycle from user prompt to vector retrieval and LLM response generation.

---

## 2. Confluence Ingestion & Synchronization Pipeline (Deep Dive)
- **Trigger Mechanisms**: Explain how background sync works via scheduled cron jobs (`@EnableScheduling`, `@Scheduled`) vs. manual admin REST endpoints (`POST /api/sync/confluence`).
- **API Fetching & Pagination**: How the system calls Confluence V2 REST APIs (`GET /wiki/api/v2/spaces/{id}/pages`), handling cursors (`_links.next`) and resilience (rate limiting, exponential backoff on 429/5xx).
- **Smart Incremental Sync & Versioning**: Explain the role of the `confluence_synced_pages` tracking table. How version numbers and SHA-256 content hashes prevent redundant embedding generation and save costs.
- **Parsing & Chunking**: How Confluence XHTML storage format is converted to clean Markdown (preserving tables, code blocks, and Mermaid diagrams), and how `TokenTextSplitter` breaks text into optimal vector chunks with enriched metadata (`pageId`, `pageTitle`, absolute web UI URL, `syncId`).
- **Safe Upserts & Deletion Handling**: Explain why new chunks are written *before* old ones are deleted (`syncId != new`), and how post-listing verification prevents an API outage from wiping the index.

---

## 3. Hybrid Search, Vector Storage & pgvector
- **Vector Embeddings**: How chunks are transformed into 768-dimensional vectors using Gemini embeddings.
- **Database Schema**: How pgvector stores embeddings and metadata in PostgreSQL.
- **Isolation & Scoping**: Explain how search handles data boundaries—combining user-uploaded documents and Confluence pages while strictly preserving user data isolation.

---

## 4. Chat Memory & Context Management
- **Persistent Conversation History**: Explain how the custom `JpaChatMemory` bean stores and retrieves past messages per session/user.
- **Context Injection**: How chat history and retrieved RAG context chunks are dynamically injected into the Gemini prompt payload.

---

## 5. Frontend & UI Features (Next.js)
- **AetherSync AI Aesthetic**: Dark theme styling (`#0A0A0A` background, `#161616` cards, `#2FFF88` neon accents).
- **Clickable Source Citations**: How citation chips render at the bottom of AI answers linking directly back to original Confluence pages.
- **Live Mermaid.js Rendering**: How ```mermaid ... ``` code blocks in LLM responses are parsed and rendered interactively on the client side.

---

## 6. Interview Q&A Cheatsheet
Provide 5-6 common tough technical questions an interviewer might ask about this specific architecture (e.g., *"How do you handle stale vectors when a Confluence page is deleted?"*, *"Why use pgvector over a managed external vector DB?"*, *"How do you prevent race conditions during sync runs?"*) along with ideal, concise engineering answers.