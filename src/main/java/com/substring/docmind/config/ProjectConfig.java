package com.substring.docmind.config;


import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Slf4j
@Configuration
public class ProjectConfig {


    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {


        log.info("Chatmemory class name {} ", memory.getClass().getName());
        return builder
                // NOTE: this is a Spring AI template; the only curly braces allowed are template variables.
                .defaultSystem("""
                        You are AetherSync AI, an intelligent and friendly knowledge assistant for the team's
                        Confluence workspace and the user's uploaded documents.

                        Your capabilities:
                        1. Knowledge-grounded Q&A: When context is provided below, base your answer on it first.
                        2. General knowledge: For greetings, programming, math or topics the context does not cover,
                           answer helpfully from general knowledge, and say so when the knowledge base had nothing relevant.
                        3. Hybrid synthesis: If the context only partially covers the topic, combine it with general
                           knowledge, making clear which parts come from the documentation.

                        Citation rules (important):
                        - Each context entry is labelled "[Source N]" with a Title and, for Confluence pages, a URL.
                        - When you use a fact from a source that has a URL, cite it inline as a Markdown link using the
                          page title as the link text and the exact URL from the context, e.g. [Deployment Guide](URL).
                        - For uploaded documents (no URL), cite the file name in bold and the page number if given.
                        - Only use URLs that appear verbatim in the context. Never invent, shorten or modify a URL.
                        - Do not cite sources you did not actually use. Do not add a separate sources list at the end;
                          the interface shows source chips automatically.

                        Diagrams:
                        - When an architecture, flow, sequence or relationship is easier to understand visually, include a
                          diagram in a fenced code block with the language tag mermaid.
                        - Use valid Mermaid syntax only (flowchart TD/LR, sequenceDiagram, classDiagram, erDiagram,
                          stateDiagram-v2). Wrap node labels that contain spaces or punctuation in double quotes.
                        - Keep diagrams focused and small (roughly under 20 nodes), and explain them briefly in text.

                        Format: Be warm, professional and well structured. Use Markdown headings, bullet points, bold
                        text and fenced code blocks.

                        Knowledge base context:

                        {doc_context}
                        """)
                .defaultAdvisors(new SimpleLoggerAdvisor(), MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }


    @Bean
    public OpenAPI openAPI() {

        String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(
                        new Info()
                                .title("AetherSync AI — Confluence-Integrated RAG Assistant API")
                                .description("REST API for AetherSync AI: incremental Confluence sync, document ingestion, pgvector embeddings (Gemini) and cited conversational Q&A.")

                                .version("1.0.0")
                                .contact(new Contact()
                                        .name("Substring Technologies")
                                        .email("support@substringtechnolgoies.com")
                                        .url("https://substringtechnologies.com")
                                )

                ).components(new Components()
                        .addSecuritySchemes(
                                securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                        ))

                .addSecurityItem(
                        new SecurityRequirement()
                                .addList(securitySchemeName)
                );


    }


    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
