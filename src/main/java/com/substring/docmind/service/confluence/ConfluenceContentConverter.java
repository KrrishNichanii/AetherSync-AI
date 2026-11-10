package com.substring.docmind.service.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Converts Confluence "storage format" (XHTML plus ac:/ri: macro elements) into
 * lightweight Markdown so headings, lists, tables and code blocks keep their
 * structure when chunked and shown to the LLM.
 */
@Component
public class ConfluenceContentConverter {

    private static final Set<String> SKIPPED = Set.of(
            "ac:parameter", "ac:image", "ri:attachment", "ac:placeholder", "style", "script",
            "ac:emoticon", "ri:user", "ac:adf-fallback");

    private static final Set<String> CALLOUT_MACROS = Set.of("info", "note", "warning", "tip", "panel", "expand");

    public String toMarkdown(String storageXhtml) {
        if (storageXhtml == null || storageXhtml.isBlank()) {
            return "";
        }
        Element root = Jsoup.parse("<root>" + storageXhtml + "</root>", "", Parser.xmlParser()).child(0);
        StringBuilder out = new StringBuilder();
        renderChildren(root, out, 0);
        return normalizeWhitespace(out.toString());
    }

    private void renderChildren(Node parent, StringBuilder out, int listDepth) {
        for (Node child : parent.childNodes()) {
            render(child, out, listDepth);
        }
    }

    private void render(Node node, StringBuilder out, int listDepth) {
        if (node instanceof CDataNode cdata) {
            out.append(cdata.text());
            return;
        }
        if (node instanceof TextNode text) {
            out.append(text.text().replace(' ', ' '));
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }

        String tag = el.tagName().toLowerCase(Locale.ROOT);
        if (SKIPPED.contains(tag)) {
            return;
        }

        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                block(out);
                out.append("#".repeat(level)).append(' ').append(inlineText(el)).append("\n\n");
            }
            case "p", "div", "section" -> {
                block(out);
                renderChildren(el, out, listDepth);
                out.append("\n\n");
            }
            case "br" -> out.append('\n');
            case "hr" -> {
                block(out);
                out.append("---\n\n");
            }
            case "ul", "ol" -> {
                if (listDepth == 0) {
                    block(out);
                }
                int index = 1;
                for (Element li : el.children()) {
                    if (!li.tagName().equalsIgnoreCase("li")) {
                        continue;
                    }
                    out.append("  ".repeat(listDepth))
                            .append(tag.equals("ol") ? (index++) + ". " : "- ");
                    renderListItem(li, out, listDepth + 1);
                    out.append('\n');
                }
                if (listDepth == 0) {
                    out.append('\n');
                }
            }
            case "table" -> renderTable(el, out);
            case "pre" -> {
                block(out);
                out.append("```\n").append(el.wholeText().strip()).append("\n```\n\n");
            }
            case "code" -> out.append('`').append(el.text()).append('`');
            case "strong", "b" -> out.append("**").append(inlineText(el)).append("**");
            case "em", "i" -> out.append('_').append(inlineText(el)).append('_');
            case "a" -> {
                String text = inlineText(el);
                String href = el.attr("href");
                out.append(href.isBlank() ? text : "[" + text + "](" + href + ")");
            }
            case "blockquote" -> {
                block(out);
                for (String line : renderToString(el, listDepth).split("\n")) {
                    out.append("> ").append(line).append('\n');
                }
                out.append('\n');
            }
            case "ac:link" -> renderConfluenceLink(el, out);
            case "ac:task-list" -> {
                block(out);
                for (Element task : el.getElementsByTag("ac:task")) {
                    Element status = task.getElementsByTag("ac:task-status").first();
                    Element body = task.getElementsByTag("ac:task-body").first();
                    boolean done = status != null && "complete".equalsIgnoreCase(status.text().trim());
                    out.append(done ? "- [x] " : "- [ ] ")
                            .append(body != null ? inlineText(body) : "")
                            .append('\n');
                }
                out.append('\n');
            }
            case "ac:structured-macro" -> renderMacro(el, out, listDepth);
            default -> renderChildren(el, out, listDepth);
        }
    }

    private void renderListItem(Element li, StringBuilder out, int depth) {
        StringBuilder item = new StringBuilder();
        for (Node child : li.childNodes()) {
            if (child instanceof Element c && (c.tagName().equalsIgnoreCase("ul") || c.tagName().equalsIgnoreCase("ol"))) {
                item.append('\n');
                render(c, item, depth);
            } else if (child instanceof Element c && c.tagName().equalsIgnoreCase("p")) {
                renderChildren(c, item, depth);
                item.append(' ');
            } else {
                render(child, item, depth);
            }
        }
        out.append(item.toString().strip());
    }

    private void renderTable(Element table, StringBuilder out) {
        block(out);
        boolean headerWritten = false;
        for (Element row : table.getElementsByTag("tr")) {
            StringBuilder line = new StringBuilder("|");
            int cells = 0;
            for (Element cell : row.children()) {
                String cellTag = cell.tagName().toLowerCase(Locale.ROOT);
                if (!cellTag.equals("td") && !cellTag.equals("th")) {
                    continue;
                }
                line.append(' ').append(renderToString(cell, 0).replace("\n", " ").replace("|", "\\|").strip())
                        .append(" |");
                cells++;
            }
            if (cells == 0) {
                continue;
            }
            out.append(line).append('\n');
            if (!headerWritten) {
                out.append("|").append(" --- |".repeat(cells)).append('\n');
                headerWritten = true;
            }
        }
        out.append('\n');
    }

    private void renderMacro(Element macro, StringBuilder out, int listDepth) {
        String name = macro.attr("ac:name").toLowerCase(Locale.ROOT);
        Element plainBody = macro.getElementsByTag("ac:plain-text-body").first();
        Element richBody = macro.getElementsByTag("ac:rich-text-body").first();

        if (plainBody != null) {
            String language = parameter(macro, "language");
            if (name.contains("mermaid")) {
                language = "mermaid";
            }
            block(out);
            out.append("```").append(language == null ? "" : language).append('\n')
                    .append(plainBody.wholeText().strip())
                    .append("\n```\n\n");
            return;
        }

        if (richBody != null) {
            if (CALLOUT_MACROS.contains(name)) {
                String title = parameter(macro, "title");
                block(out);
                String label = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                out.append("> **").append(title != null ? title : label).append(":** ");
                String body = renderToString(richBody, listDepth).strip();
                out.append(body.replace("\n", "\n> ")).append("\n\n");
            } else {
                renderChildren(richBody, out, listDepth);
            }
        }
        // Macros with no textual body (toc, children, jira, etc.) are dropped.
    }

    private void renderConfluenceLink(Element link, StringBuilder out) {
        Element linkBody = link.getElementsByTag("ac:plain-text-link-body").first();
        if (linkBody == null) {
            linkBody = link.getElementsByTag("ac:link-body").first();
        }
        if (linkBody != null && !linkBody.text().isBlank()) {
            out.append(linkBody.text());
            return;
        }
        Element page = link.getElementsByTag("ri:page").first();
        if (page != null && page.hasAttr("ri:content-title")) {
            out.append(page.attr("ri:content-title"));
        }
    }

    private String parameter(Element macro, String name) {
        for (Element p : macro.getElementsByTag("ac:parameter")) {
            if (p.parent() == macro && name.equalsIgnoreCase(p.attr("ac:name"))) {
                String v = p.text().trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private String inlineText(Element el) {
        return renderToString(el, 0).replace('\n', ' ').replaceAll("\\s{2,}", " ").strip();
    }

    private String renderToString(Element el, int listDepth) {
        StringBuilder sb = new StringBuilder();
        renderChildren(el, sb, listDepth);
        return normalizeWhitespace(sb.toString());
    }

    /** Ensures the next block starts on a fresh line. */
    private static void block(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append("\n\n");
        }
    }

    private static String normalizeWhitespace(String s) {
        return s.replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
