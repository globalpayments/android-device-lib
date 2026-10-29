package com.globalpayments.library.terminals.entities;

/**
 * Outcome of arming a gateway dispatch for the current transaction.
 */
enum DispatchDecision {
    /** The gateway request must be sent. */
    SEND,
    /** A cancellation was requested first, the caller has to complete the cancellation. */
    CANCELLED,
    /** The transaction already produced a final response, nothing left to do. */
    ABANDONED
}
