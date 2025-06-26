package com.globalpayments.library.terminals;

import com.globalpayments.library.terminals.entities.CardholderInteractionRequest;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.ErrorType;
import com.globalpayments.library.terminals.enums.TransactionStatus;

public interface TransactionListener {
    void onStatusUpdate(TransactionStatus transactionStatus);
    boolean onCardholderInteractionRequested(CardholderInteractionRequest cardholderInteractionRequest);
    void onTransactionComplete(TerminalResponse transaction);
    void onError(Error error, ErrorType errorType);
}