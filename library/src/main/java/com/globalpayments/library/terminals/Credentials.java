package com.globalpayments.library.terminals;

public class Credentials {

    private String username;
    private String licenseId;
    private String siteId;
    private String password;
    private String deviceId;
    private String merchantId;
    private String developerId;
    private String transactionKey;

    public static Credentials porticoCredentials(String username, String licenseId, String siteId,
            String password, String deviceId) {
        Credentials credentials = new Credentials();
        credentials.username = username;
        credentials.licenseId = licenseId;
        credentials.siteId = siteId;
        credentials.password = password;
        credentials.deviceId = deviceId;
        return credentials;
    }

    public static Credentials transitCredentials(String merchantId, String username, String password, String deviceId,
            String developerId) {
        Credentials credentials = new Credentials();
        credentials.merchantId = merchantId;
        credentials.username = username;
        credentials.password = password;
        credentials.deviceId = deviceId;
        credentials.developerId = developerId;
        return credentials;
    }

    public static Credentials transitCredentials(String transactionKey, String merchantId, String deviceId, String developerId) {
        Credentials credentials = new Credentials();
        credentials.transactionKey = transactionKey;
        credentials.merchantId = merchantId;
        credentials.deviceId = deviceId;
        credentials.developerId = developerId;
        return credentials;
    }

    public String getUsername() {
        return username;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public String getSiteId() {
        return siteId;
    }

    public String getPassword() {
        return password;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getDeveloperId() {
        return developerId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }
}
