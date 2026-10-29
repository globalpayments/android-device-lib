package com.globalpayments.library.terminals.puck1000.mutualauth;

import java.util.Collections;
import java.util.List;

/**
 * Immutable holder for the four artifacts required to perform PUCK1000 Mutual Authentication
 * through {@code Credit.init(context, kernelLog, cotsCert, cotsPrivateKey, cotsIntermediateCa,
 * puckFullChain)}.
 *
 * <ul>
 *     <li>{@code cotsCertificatePem} &dash; the COTS leaf certificate issued by the MA server.</li>
 *     <li>{@code cotsPrivateKeyPem} &ndash; the COTS private key generated on-device (PKCS#8 PEM).</li>
 *     <li>{@code cotsIntermediatePem} &ndash; the COTS intermediate CA certificate.</li>
 *     <li>{@code puckFullChainPem} &ndash; the PUCK certificate chain (ordered leaf to root).</li>
 * </ul>
 */
public class MutualAuthCertificate {

    private final String cotsCertificatePem;
    private final String cotsPrivateKeyPem;
    private final String cotsIntermediatePem;
    private final List<String> puckFullChainPem;

    public MutualAuthCertificate(String cotsCertificatePem,
                                 String cotsPrivateKeyPem,
                                 String cotsIntermediatePem,
                                 List<String> puckFullChainPem) {
        this.cotsCertificatePem = cotsCertificatePem;
        this.cotsPrivateKeyPem = cotsPrivateKeyPem;
        this.cotsIntermediatePem = cotsIntermediatePem;
        this.puckFullChainPem = puckFullChainPem == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(puckFullChainPem);
    }

    public String getCotsCertificatePem() {
        return cotsCertificatePem;
    }

    public String getCotsPrivateKeyPem() {
        return cotsPrivateKeyPem;
    }

    public String getCotsIntermediatePem() {
        return cotsIntermediatePem;
    }

    public List<String> getPuckFullChainPem() {
        return puckFullChainPem;
    }
}
