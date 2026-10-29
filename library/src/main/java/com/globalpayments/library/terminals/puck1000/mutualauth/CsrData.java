package com.globalpayments.library.terminals.puck1000.mutualauth;

import com.google.gson.annotations.SerializedName;

/**
 * Payload returned by the Mutual Authentication certificate server inside {@link CsrResponse}.
 * It carries the three certificate artifacts required to complete the PUCK1000 Mutual
 * Authentication handshake.
 */
public class CsrData {

    @SerializedName("certificate")
    private String certificate;

    @SerializedName("cots_intermediate")
    private String cotsIntermediate;

    @SerializedName("puck_full_chain")
    private String puckFullChain;

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }

    public String getCotsIntermediate() {
        return cotsIntermediate;
    }

    public void setCotsIntermediate(String cotsIntermediate) {
        this.cotsIntermediate = cotsIntermediate;
    }

    public String getPuckFullChain() {
        return puckFullChain;
    }

    public void setPuckFullChain(String puckFullChain) {
        this.puckFullChain = puckFullChain;
    }
}
