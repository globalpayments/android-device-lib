package com.globalpayments.library.terminals.puck1000.mutualauth;

import com.google.gson.annotations.SerializedName;

/**
 * Envelope returned by the Mutual Authentication certificate server when issuing a certificate
 * for a submitted CSR. A {@link #code} of {@code 0} indicates success.
 */
public class CsrResponse {

    @SerializedName("code")
    private int code;

    @SerializedName("message")
    private String message;

    @SerializedName("data")
    private CsrData data;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public CsrData getData() {
        return data;
    }

    public void setData(CsrData data) {
        this.data = data;
    }
}
