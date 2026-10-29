package com.globalpayments.library.terminals.transactions;

import com.globalpayments.library.terminals.IDevice;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

import java.math.BigDecimal;

public class CreditIncrementalAuthBuilder extends BaseBuilder {
    private String referenceNumber;
    private BigDecimal amount;
    private String transactionId;


    public CreditIncrementalAuthBuilder(IDevice device) {
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.INCREMENT);
        request.setGatewayTransactionId(transactionId);

        if (amount != null) {
            request.setTotal(amount.movePointRight(2).longValue());
        }

        request.setPosReferenceNumber(referenceNumber);

        return request;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}

