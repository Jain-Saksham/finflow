package com.finflow.events;

/** A message that can never succeed; the consumer error handler sends it straight to the DLT. */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }
}
