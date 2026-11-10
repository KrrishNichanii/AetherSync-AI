package com.substring.docmind.service.confluence;

public class ConfluenceApiException extends RuntimeException {

    public ConfluenceApiException(String message) {
        super(message);
    }

    public ConfluenceApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
