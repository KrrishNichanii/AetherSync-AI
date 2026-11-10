package com.substring.docmind.service.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceContentConverterTest {

    private final ConfluenceContentConverter converter = new ConfluenceContentConverter();

    @Test
    void convertsHeadingsParagraphsAndLists() {
        String html = "<h2>Overview</h2><p>Service <strong>MFS</strong> handles&nbsp;payments.</p>"
                + "<ul><li>Fast</li><li>Reliable<ul><li>99.9% SLA</li></ul></li></ul>"
                + "<ol><li>Build</li><li>Deploy</li></ol>";

        String md = converter.toMarkdown(html);

        assertThat(md).contains("## Overview");
        assertThat(md).contains("Service **MFS** handles payments.");
        assertThat(md).contains("- Fast");
        assertThat(md).contains("- Reliable");
        assertThat(md).contains("  - 99.9% SLA");
        assertThat(md).contains("1. Build").contains("2. Deploy");
    }

    @Test
    void convertsCodeAndMermaidMacrosToFencedBlocks() {
        String html = "<ac:structured-macro ac:name=\"code\">"
                + "<ac:parameter ac:name=\"language\">java</ac:parameter>"
                + "<ac:plain-text-body><![CDATA[if (a < b) { run(); }]]></ac:plain-text-body>"
                + "</ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"mermaid-cloud\">"
                + "<ac:plain-text-body><![CDATA[flowchart LR\n  A --> B]]></ac:plain-text-body>"
                + "</ac:structured-macro>";

        String md = converter.toMarkdown(html);

        assertThat(md).contains("```java\nif (a < b) { run(); }\n```");
        assertThat(md).contains("```mermaid\nflowchart LR\n  A --> B\n```");
        assertThat(md).doesNotContain("ac:parameter").doesNotContain(">java<");
    }

    @Test
    void convertsTablesCalloutsAndLinks() {
        String html = "<table><tbody><tr><th>Env</th><th>URL</th></tr>"
                + "<tr><td>Prod</td><td><a href=\"https://mfs.example.com\">site</a></td></tr></tbody></table>"
                + "<ac:structured-macro ac:name=\"warning\"><ac:rich-text-body><p>Do not restart</p>"
                + "</ac:rich-text-body></ac:structured-macro>"
                + "<p>See <ac:link><ri:page ri:content-title=\"Runbook\"/></ac:link></p>";

        String md = converter.toMarkdown(html);

        assertThat(md).contains("| Env | URL |");
        assertThat(md).contains("| --- | --- |");
        assertThat(md).contains("| Prod | [site](https://mfs.example.com) |");
        assertThat(md).contains("> **Warning:** Do not restart");
        assertThat(md).contains("See Runbook");
    }

    @Test
    void emptyOrMacroOnlyBodyProducesBlankText() {
        assertThat(converter.toMarkdown(null)).isEmpty();
        assertThat(converter.toMarkdown("  ")).isEmpty();
        assertThat(converter.toMarkdown("<ac:structured-macro ac:name=\"toc\"/>")).isBlank();
    }
}
