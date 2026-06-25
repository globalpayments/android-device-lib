package com.globalpayments.library.entities;

public enum ReversalReason {
    UNDEFINED,

    VOIDED_BY_CUSTOMER,

    /**
     * The terminal times out during a card interaction.
     */
    DEVICE_TIMEOUT,

    /**
     * Communication to the terminal fails after host processing.
     */
    DEVICE_UNAVAILABLE,

    PARTIAL_REVERSAL,

    PREMATURE_CHIP_REMOVAL,

    CHIP_DECLINED
}
