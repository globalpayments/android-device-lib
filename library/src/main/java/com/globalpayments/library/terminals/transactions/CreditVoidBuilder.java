package com.globalpayments.library.terminals.transactions;

import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

import com.globalpayments.library.terminals.IDevice;

public class CreditVoidBuilder extends BaseBuilder {
    private String referenceNumber;
    private String transactionId;

   /* public CreditVoidBuilder(C2XDevice device) {
        super((IDevice) device);
    }*/

    /**
     * Build Void Transaction
     * @param device
     */
    public CreditVoidBuilder(IDevice device){
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.VOID);
        request.setGatewayTransactionId(transactionId);
        request.setPosReferenceNumber(referenceNumber);

        return request;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
