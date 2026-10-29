package com.globalpayments.library.terminals.transactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.globalpayments.library.entities.Address;
import com.globalpayments.library.entities.Card;
import com.globalpayments.library.entities.TransactionDetails;
import com.globalpayments.library.terminals.IDevice;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.enums.AvsType;
import com.tsys.payments.library.enums.CardDataSourceType;
import com.tsys.payments.library.enums.TransactionType;
import java.math.BigDecimal;
import org.junit.Test;

public class CreditSaleBuilderTest {

    @Test
    public void execute_buildsPorticoStyleRequestAndSendsToDevice() throws Exception {
        CapturingDevice device = new CapturingDevice();
        CreditSaleBuilder builder = new CreditSaleBuilder(device);

        Card card = new Card();
        card.setNumber("4111111111111111");
        card.setExpMonth(12);
        card.setExpYear(2025);
        card.setCvv("123");

        Address address = new Address();
        address.setStreetAddress("1 Main St");
        address.setCity("Atlanta");
        address.setState("GA");
        address.setPostalCode("30339");

        TransactionDetails details = new TransactionDetails();
        details.setInvoiceNumber("INV-001");

        builder.setAmount(new BigDecimal("14.00"));
        builder.setTaxAmount(new BigDecimal("1.25"));
        builder.setReferenceNumber("client-ref-001");
        builder.setAllowDuplicates(true);
        builder.setRequestToken(true);
        builder.setToken("token-abc");
        builder.setCardBrandTxnId("cbt-123");
        builder.setCardHolderName("Jane Tester");
        builder.setCreditCard(card);
        builder.setAddress(address);
        builder.setDetails(details);

        builder.execute();

        TransactionRequest request = device.capturedRequest;
        assertNotNull(request);
        assertEquals(TransactionType.SALE, request.getTransactionType());
        assertEquals(1400L, request.getTotal().longValue());
        assertEquals(125L, request.getTax().longValue());
        assertEquals("client-ref-001", request.getPosReferenceNumber());
        assertEquals("INV-001", request.getInvoiceNumber());
        assertTrue(request.isAllowDuplicates());
        assertTrue(request.isGenerateToken());
        assertEquals("token-abc", request.getToken());
        assertEquals("cbt-123", request.getCardBrandTxnId());

        assertNotNull(request.getKeyedCardData());
        assertEquals("4111111111111111", request.getKeyedCardData().getPan());
        assertEquals("122025", request.getKeyedCardData().getExpirationDate());
        assertEquals("123", request.getKeyedCardData().getCvv2());
        assertEquals(CardDataSourceType.KEYED, request.getKeyedCardData().getCardDataSource());
        assertEquals(AvsType.ZIP_ADDRESS, request.getKeyedCardData().getAvsType());
        assertEquals("30339", request.getKeyedCardData().getPostalCode());
        assertEquals("Jane Tester", request.getKeyedCardData().getCardholderName());
    }

    private static final class CapturingDevice implements IDevice {
        private TransactionRequest capturedRequest;

        @Override
        public void doTransaction(TransactionRequest transactionRequest) {
            this.capturedRequest = transactionRequest;
        }

        @Override
        public void cancelTransaction() { }

        @Override
        public void cancelSAFUpload() { }

        @Override
        public void uploadSAF() { }

        @Override
        public boolean isForcedSafEnabled() {
            return false;
        }

        @Override
        public void setForcedSafEnabled(boolean forcedSaf) { }

        @Override
        public void acknowledgeSAFTransaction(String uniqueSafId) { }
    }
}
