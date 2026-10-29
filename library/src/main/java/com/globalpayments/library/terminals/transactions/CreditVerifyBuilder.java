package com.globalpayments.library.terminals.transactions;

import com.globalpayments.library.terminals.IDevice;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

public class CreditVerifyBuilder extends BaseBuilder {

    private boolean requestToken;

    public CreditVerifyBuilder(IDevice device) {
        super(device);
    }

    public boolean isRequestToken() {
        return requestToken;
    }

    public void setRequestToken(boolean requestToken) {
        this.requestToken = requestToken;
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.VERIFY);
        request.setTotal(0L);
        request.setGenerateToken(requestToken);

        return request;
    }
}
