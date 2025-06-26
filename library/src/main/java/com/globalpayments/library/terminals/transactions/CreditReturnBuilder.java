package com.globalpayments.library.terminals.transactions;

import java.math.BigDecimal;

import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

import com.globalpayments.library.terminals.IDevice;

public class CreditReturnBuilder extends BaseBuilder {
    private String referenceNumber;
    private String transactionId;
    private BigDecimal amount;

   /* public CreditReturnBuilder(C2XDevice device) {
        super((IDevice) device);
    }*/

    public CreditReturnBuilder(IDevice device){
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.REFUND);

        if (amount != null) {
            request.setTotal(amount.movePointRight(2).longValue());
        }

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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
