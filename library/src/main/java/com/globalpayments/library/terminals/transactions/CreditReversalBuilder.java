package com.globalpayments.library.terminals.transactions;

import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.ReversalReason;
import com.tsys.payments.library.enums.TransactionType;

import com.globalpayments.library.terminals.IDevice;

public class CreditReversalBuilder extends BaseBuilder {
    private String referenceNumber;
    private String transactionId;
    private ReversalReason reversalReason;

    /**
     * Build Reversal Transaction
     * @param device
     */
    public CreditReversalBuilder(IDevice device){
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.REVERSAL);
        request.setGatewayTransactionId(transactionId);
        request.setPosReferenceNumber(referenceNumber);
        request.setReversalReason(reversalReason);

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

    public ReversalReason getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(com.globalpayments.library.entities.ReversalReason reversalReason) {
        this.reversalReason = ReversalReason.values()[reversalReason.ordinal()];
    }
}
