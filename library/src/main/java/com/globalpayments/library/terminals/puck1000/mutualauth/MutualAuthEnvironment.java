package com.globalpayments.library.terminals.puck1000.mutualauth;

/**
 * Predefined PUCK1000 Mutual Authentication certificate-server environments.
 *
 * <p>The certificate host URLs are owned by the SDK (not the integrating app). Integrators select
 * an environment rather than hard-coding URLs. When no environment is set explicitly, the SDK
 * defaults to {@link #DVT_EVT}.</p>
 */
public enum MutualAuthEnvironment {

    /**
     * DVT/EVT units using a test Mutual Authentication environment. This is the default.
     */
    DVT_EVT("https://dev-puck.uat.castlestech.cloud/"),

    /**
     * PVT units.
     */
    PVT("https://ccs-gp.uat.castlestech.cloud/");

    /**
     * The environment used when the integrator does not select one explicitly.
     */
    public static final MutualAuthEnvironment DEFAULT = DVT_EVT;

    private final String url;

    MutualAuthEnvironment(String url) {
        this.url = url;
    }

    /**
     * @return the Mutual Authentication certificate-server base URL for this environment.
     */
    public String getUrl() {
        return url;
    }
}
