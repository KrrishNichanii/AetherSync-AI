package com.substring.docmind.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatResponseDto {
    private String answer;
    private String conversationId;
    private List<CitationDto> citations;
    /** De-duplicated sources (one per Confluence page / uploaded file) for citation chips. */
    private List<SourceReferenceDto> sources;
    private Long responseTimeMs;
}
