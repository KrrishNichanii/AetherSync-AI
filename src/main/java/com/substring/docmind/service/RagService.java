package com.substring.docmind.service;


import com.substring.docmind.config.AppProperties;
import com.substring.docmind.dto.*;
import com.substring.docmind.entity.Conversation;
import com.substring.docmind.entity.User;
import com.substring.docmind.repository.ConversationRepository;
import com.substring.docmind.service.confluence.ConfluenceSyncService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final AppProperties appProperties;
    private final ChatClient chatClient;

    private final ConversationRepository conversationRepository;


    //to ask any thing related to document
    public ChatResponseDto askQuestion(ChatRequestDto request, User user) {

        long startTime = System.currentTimeMillis();

        String conversationId = request.getConversationId() != null ? request.getConversationId() : UUID.randomUUID().toString();

        ensureConversationExists(conversationId, user, request.getQuestion());

        log.info("Processing query: '{}', scoped documentId: {}", request.getQuestion(), request.getDocumentId());

        List<Document> similarDocuments = this.retrieveRelevantDocuments(
                request.getQuestion(),
                request.getDocumentId(),
                request.getTopK(),
                request.getMinSimilarity(),
                user
        );

        RetrievedContext context = buildContext(similarDocuments);

        String answer = this.chatClient
                .prompt()
                .system(s -> s.param("doc_context", context.promptText()))
                .user(request.getQuestion())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call().content();
        long responseTime = System.currentTimeMillis() - startTime;
        log.info("Completed Q&A in {} ms with {} citations from {} sources", responseTime,
                context.citations().size(), context.sources().size());
        return ChatResponseDto.builder()
                .answer(answer)
                .conversationId(conversationId)
                .citations(context.citations())
                .sources(context.sources())
                .responseTimeMs(responseTime)
                .build();
    }

    private void ensureConversationExists(String conversationId, User user, @NotBlank(message = "Question cannot be empty") String question) {

        conversationRepository.findByIdAndUser(conversationId, user)
                .orElseGet(() -> {
                    String title = question.length() > 50 ? question.substring(0, 47) + "....." : question;
                    return conversationRepository.save(
                            Conversation.builder().id(conversationId)
                                    .title(title)
                                    .user(user)
                                    .createdAt(LocalDateTime.now())
                                    .updatedAt(LocalDateTime.now())
                                    .build()
                    );
                });
    }


    // this streams
    public Flux<String> streamQuestionAnswer(ChatRequestDto requestDto, User user) {
        log.info("Streaming query: '{}'", requestDto.getQuestion());
        String conversationId = requestDto.getConversationId() != null ? requestDto.getConversationId() : UUID.randomUUID().toString();
        requestDto.setConversationId(conversationId);
        ensureConversationExists(conversationId, user, requestDto.getQuestion());

        List<Document> relevantDocuments = retrieveRelevantDocuments(
                requestDto.getQuestion(),
                requestDto.getDocumentId(),
                requestDto.getTopK(),
                requestDto.getMinSimilarity(),
                user
        );
        String contextText = buildContext(relevantDocuments).promptText();
        return chatClient.prompt()
                .system(s -> s.param("doc_context", contextText))
                .user(requestDto.getQuestion())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .concatWith(Flux.just("[DONE]"));
    }


    public SearchResultDto searchSimilarChunks(SearchRequestDto request, User user) {

        List<Document> matchedDocs = retrieveRelevantDocuments(request.getQuery(), request.getDocumentId(), request.getTopK(), request.getSimilaritySearch(), user);

        RetrievedContext context = buildContext(matchedDocs);

        return SearchResultDto.builder()
                .query(request.getQuery())
                .totalMatches(context.citations().size())
                .matches(context.citations())
                .sources(context.sources())
                .build();
    }

    /* ─── Context & citation building ─────────────────────────────── */

    /** Retrieved chunks rendered for the prompt, plus the citations/sources returned to the client. */
    private record RetrievedContext(String promptText, List<CitationDto> citations, List<SourceReferenceDto> sources) {
    }

    /**
     * Groups retrieved chunks by their originating source (Confluence page or uploaded file),
     * numbers each source, and renders a context block where every chunk carries its
     * source number, title and URL so the LLM can cite it as a Markdown link.
     * Source numbers in the prompt match {@link SourceReferenceDto#index()} returned to the UI.
     */
    private RetrievedContext buildContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new RetrievedContext("", List.of(), List.of());
        }

        Map<String, SourceAccumulator> sourcesByKey = new LinkedHashMap<>();
        List<CitationDto> citations = new ArrayList<>(documents.size());
        StringBuilder prompt = new StringBuilder();

        for (Document doc : documents) {
            CitationDto citation = mapToCitation(doc);
            citations.add(citation);

            String key = citation.getSourceUrl() != null ? citation.getSourceUrl()
                    : citation.getDocumentId() != null ? "doc:" + citation.getDocumentId()
                    : "title:" + citation.getSourceTitle();
            SourceAccumulator source = sourcesByKey.computeIfAbsent(key,
                    k -> new SourceAccumulator(sourcesByKey.size() + 1, citation));
            source.offer(citation.getSimilarityScore());

            if (!prompt.isEmpty()) {
                prompt.append("\n\n---\n\n");
            }
            prompt.append("[Source ").append(source.index).append("]\n");
            prompt.append("Title: ").append(citation.getSourceTitle()).append('\n');
            if (ConfluenceSyncService.SOURCE_CONFLUENCE.equals(citation.getSourceType())) {
                prompt.append("Type: Confluence page\n");
            } else {
                prompt.append("Type: Uploaded document");
                if (citation.getPageNumber() != null) {
                    prompt.append(" (page ").append(citation.getPageNumber()).append(')');
                }
                prompt.append('\n');
            }
            if (citation.getSourceUrl() != null) {
                prompt.append("URL: ").append(citation.getSourceUrl()).append('\n');
            }
            prompt.append("Content:\n").append(doc.getText());
        }

        List<SourceReferenceDto> sources = sourcesByKey.values().stream()
                .map(SourceAccumulator::toDto)
                .toList();
        return new RetrievedContext(prompt.toString(), citations, sources);
    }

    private static final class SourceAccumulator {
        private final int index;
        private final CitationDto first;
        private Double bestScore;

        SourceAccumulator(int index, CitationDto first) {
            this.index = index;
            this.first = first;
        }

        void offer(Double score) {
            if (score != null && (bestScore == null || score > bestScore)) {
                bestScore = score;
            }
        }

        SourceReferenceDto toDto() {
            return new SourceReferenceDto(index, first.getSourceTitle(), first.getSourceUrl(),
                    first.getSourceType(), bestScore);
        }
    }


    private CitationDto mapToCitation(Document document) {

        Map<String, Object> meta = document.getMetadata();
        boolean confluence = ConfluenceSyncService.SOURCE_CONFLUENCE.equals(meta.get("source"));

        UUID docId = null;
        if (meta.get("documentId") != null) {
            try {
                docId = UUID.fromString(meta.get("documentId").toString());
            } catch (Exception ignore) {
                // not a UUID; leave null
            }
        }
        Integer chunkIndex = null;
        if (meta.get("chunkIndex") instanceof Number n) {
            chunkIndex = n.intValue();
        }

        Integer pageNumber = null;
        if (meta.get("pageNumber") instanceof Number n) {
            pageNumber = n.intValue();
        } else if (meta.get("page_number") instanceof Number n) {
            pageNumber = n.intValue();
        }

        Double score = document.getScore();
        if (score == null && meta.get("distance") instanceof Number n) {
            score = 1.0 - n.doubleValue();
        }

        String title = confluence
                ? stringOr(meta.get("pageTitle"), "Untitled Confluence page")
                : stringOr(meta.get("fileName"), "Unknown");

        return CitationDto.builder()
                .documentId(docId)
                .fileName(stringOr(meta.get("fileName"), title))
                .chunkIndex(chunkIndex)
                .pageNumber(pageNumber)
                .snippet(document.getText())
                .similarityScore(score)
                .metadata(meta)
                .sourceType(confluence ? ConfluenceSyncService.SOURCE_CONFLUENCE : "upload")
                .sourceTitle(title)
                .sourceUrl(confluence ? stringOr(meta.get("url"), null) : null)
                .pageId(confluence ? stringOr(meta.get("pageId"), null) : null)
                .build();
    }

    private static String stringOr(Object value, String fallback) {
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }


    //    this is very important document that fetches the similar result from vector db
    private List<Document> retrieveRelevantDocuments(@NotBlank(message = "Query cannot be empty") String query, UUID documentId, Integer topK, Double similaritySearch, User user) {

        int effectiveTopK = (topK != null && topK > 0) ? topK : appProperties.getRag().getTopK();

        double effectiveSimilarity = (similaritySearch != null) ? similaritySearch : appProperties.getRag().getSimilarityThreshold();

        SearchRequest.Builder searchRequestBuilder = SearchRequest.builder().
                query(query).
                topK(effectiveTopK);

        if (effectiveSimilarity > 0.0) {
            searchRequestBuilder.similarityThreshold(effectiveSimilarity);
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filterExpression;
        if (documentId != null) {
            // Scoped to one of the user's own uploaded documents.
            filterExpression = b.and(
                    b.eq("userId", user.getId().toString()),
                    b.eq("documentId", documentId.toString())
            ).build();
        } else {
            // The user's own uploads plus the shared Confluence knowledge base.
            filterExpression = b.or(
                    b.eq("userId", user.getId().toString()),
                    b.eq("source", ConfluenceSyncService.SOURCE_CONFLUENCE)
            ).build();
        }
        searchRequestBuilder.filterExpression(filterExpression);

        try {
            List<Document> documents = vectorStore.similaritySearch(searchRequestBuilder.build());
            log.info("Retrieved {} chunks for query: '{}' (scoped docId: {})", documents.size(), query, documentId);
            return documents;
        } catch (Exception e) {
            log.error("Similarity search failed for query: '{}'", query, e);
            return Collections.emptyList();
        }
    }
}
