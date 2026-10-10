package com.fraudguard.consumer.kafka;

/**
 * Malformed JSON or a payload that does not satisfy the transaction schema.
 * The listener lets this propagate so the error handler can retry and then
 * publish the record to the dead-letter topic.
 */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }

    public InvalidTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
