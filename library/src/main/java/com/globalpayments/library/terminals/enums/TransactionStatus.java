package com.globalpayments.library.terminals.enums;

import timber.log.Timber;

public enum TransactionStatus {
    NONE,
    WAITING_FOR_CARD,
    BAD_READ,
    ICC_SWIPED,
    FALLBACK_INITIATED,
    MULTIPLE_CARDS_DETECTED,
    CARD_READ,
    TECHNICAL_FALLBACK_INITIATED,
    CARD_READ_ERROR,
    CARD_REMOVED_AFTER_TRANSACTION_COMPLETE,
    CONTACTLESS_CARD_STILL_IN_FIELD,
    CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT,
    DO_NOT_REMOVE_CARD,
    DEVICE_BUSY,
    ENTER_PIN,
    LAST_PIN_ATTEMPT,
    PIN_ACCEPTED,
    RETRY_PIN,
    REMOVE_CARD,
    CONFIGURING,
    SEE_PHONE,
    //New Status added
    INSERT_CARD,
    INSERT_TAP_CARD,
    REINSERT_CARD,
    TAP_CARD,
    TAP_AGAIN_CARD,
    TRY_ANOTHER_CARD,
    SWIPE_CARD,
    TAP_SWIPE_CARD,
    INSERT_SWIPE_OR_TRY_ANOTHER_CARD,
    //End of New Status
    PROCESSING;

    public static TransactionStatus fromVitalSdk(com.tsys.payments.library.enums.TransactionStatus status) {
        Timber.d("FromVitalSDK called with status = [%s]", status);
        if (status == null) return NONE;

        try{
            TransactionStatus transStatus = TransactionStatus.valueOf(status.name());
            Timber.d("FromVitalSDK to transactionStatus =[%s]", transStatus);
            return transStatus;
        } catch (IllegalArgumentException e) {
            Timber.e("Missing enum mapping for status: [%s]", status.name());
            return NONE;
        }
    }
}