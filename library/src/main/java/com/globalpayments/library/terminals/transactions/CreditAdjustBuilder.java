package com.globalpayments.library.terminals.transactions;

import java.math.BigDecimal;

import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.TransactionType;

import com.globalpayments.library.entities.TransactionDetails;
import com.globalpayments.library.terminals.IDevice;

public class CreditAdjustBuilder extends BaseBuilder {
    private String referenceNumber;
    private BigDecimal amount;
    private TransactionDetails details;
    private BigDecimal gratuity;
    private String transactionId;

   /* public CreditAdjustBuilder(C2XDevice device) {
        super((IDevice) device);
    }*/

    public CreditAdjustBuilder(IDevice device){
        super(device);
    }

    @Override
    protected TransactionRequest buildRequest() {
        TransactionRequest request = super.buildRequest();

        request.setTransactionType(TransactionType.TIP_ADJUST);
        request.setGatewayTransactionId(transactionId);

        if (amount != null) {
            request.setTotal(amount.movePointRight(2).longValue());
        }

        if (gratuity != null) {
            request.setTip(gratuity.movePointRight(2).longValue());
        }

        request.setPosReferenceNumber(referenceNumber);

        if (details != null) {
            request.setInvoiceNumber(details.getInvoiceNumber());
        }

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

    public TransactionDetails getDetails() {
        return details;
    }

    public void setDetails(TransactionDetails details) {
        this.details = details;
    }

    public BigDecimal getGratuity() {
        return gratuity;
    }

    public void setGratuity(BigDecimal gratuity) {
        this.gratuity = gratuity;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
