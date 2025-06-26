package com.globalpayments.library.terminals.transactions;

import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

import com.globalpayments.library.terminals.IDevice;

public class BatchCloseBuilder extends BaseBuilder {
    public BatchCloseBuilder(IDevice device) {
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.BATCH_CLOSE);

        return request;
    }
}
