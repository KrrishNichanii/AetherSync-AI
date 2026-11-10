package com.substring.docmind.dto;

/**
 * A de-duplicated source used to answer a question, suitable for rendering as a
 * clickable citation chip. {@code index} matches the "[Source n]" label given to the LLM.
 */
public record SourceReferenceDto(
        int index,
        String title,
        String url,
        String sourceType,
        Double bestScore
) {
}
