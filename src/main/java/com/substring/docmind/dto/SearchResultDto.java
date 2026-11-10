package com.substring.docmind.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchResultDto {

    private  String query;
    private  int totalMatches;
    private List<CitationDto> matches;
    /** De-duplicated sources for the matches, in the same order the LLM sees them. */
    private List<SourceReferenceDto> sources;

}
