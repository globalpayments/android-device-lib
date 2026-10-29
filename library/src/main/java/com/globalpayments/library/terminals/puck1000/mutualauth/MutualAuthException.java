package com.globalpayments.library.terminals.puck1000.mutualauth;

/**
 * Raised when any step of the PUCK1000 Mutual Authentication certificate exchange fails
 * (key/CSR generation, certificate server communication, or response parsing).
 */
public class MutualAuthException extends Exception {

    public MutualAuthException(String message) {
        super(message);
    }

    public MutualAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
