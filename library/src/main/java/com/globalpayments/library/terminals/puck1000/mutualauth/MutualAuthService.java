package com.globalpayments.library.terminals.puck1000.mutualauth;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * Performs the certificate exchange required to enable Mutual Authentication (MA) on a
 * PUCK1000 reader.
 *
 * <p>The flow mirrors the Castles reference implementation: an EC (P-256) key pair is generated
 * on-device (this becomes the COTS private key), a PKCS#10 CSR is built and submitted to the MA
 * certificate server, and the issued COTS certificate, COTS intermediate CA and PUCK certificate
 * chain are parsed back into PEM form. The resulting {@link MutualAuthCertificate} is then passed
 * to {@code Credit.init(...)} so the SDK can complete the MA handshake with the reader.</p>
 *
 * <p>{@link #requestCertificate()} is intentionally synchronous and performs network I/O; it must
 * be invoked from a background thread. The PUCK1000 controller already drives connection/init work
 * on a dedicated executor, so it can be called directly from there.</p>
 */
public class MutualAuthService {

    private static final String TAG = "Puck1000MutualAuth";

    /**
     * Default MA certificate server. Resolves to the {@link MutualAuthEnvironment#DEFAULT}
     * (DVT/EVT) host, used when the integrator does not select an environment or custom host.
     */
    public static final String DEFAULT_CERT_HOST = MutualAuthEnvironment.DEFAULT.getUrl();

    private static final String ISSUE_API = "/api/v1/scrp/cots/issue";

    private static final String EC_CURVE = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private static final int PEM_LINE_LENGTH = 64;
    private static final int HTTP_TIMEOUT_MS = 30000;

    private static final String PEM_CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_CERTIFICATE_END = "-----END CERTIFICATE-----";
    private static final String PEM_PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_PRIVATE_KEY_END = "-----END PRIVATE KEY-----";

    private static final Pattern CERT_PATTERN = Pattern.compile(
            PEM_CERTIFICATE_BEGIN + ".*?" + PEM_CERTIFICATE_END,
            Pattern.DOTALL
    );

    private final String certHost;
    private final CsrSubject csrSubject;
    private final AtomicReference<MutualAuthCertificate> lastCertificate = new AtomicReference<>(null);

    public MutualAuthService() {
        this(DEFAULT_CERT_HOST, null);
    }

    public MutualAuthService(@Nullable String certHost) {
        this(certHost, null);
    }

    public MutualAuthService(@Nullable String certHost, @Nullable CsrSubject csrSubject) {
        this.certHost = (certHost == null || certHost.trim().isEmpty())
                ? DEFAULT_CERT_HOST
                : certHost.trim();
        this.csrSubject = csrSubject == null ? CsrSubject.defaults() : csrSubject;
    }

    /**
     * Generates a fresh COTS key pair + CSR, requests a certificate from the MA server and returns
     * the artifacts needed for Mutual Authentication. The successful result is cached and can be
     * retrieved via {@link #getLastCertificate()} for re-initialization after a reader reboot.
     *
     * @throws MutualAuthException if key/CSR generation, the network call, or response parsing fails
     */
    @NonNull
    public MutualAuthCertificate requestCertificate() throws MutualAuthException {
        lastCertificate.set(null);

        KeyPair keyPair = generateKeyPair();
        String privateKeyPem = privateKeyToPkcs8Pem(keyPair.getPrivate());
        // NOTE: the private key is intentionally not logged. This is a published SDK and logging
        // private-key material must be avoided even in debug builds.
        Timber.tag(TAG).d("Generated COTS EC key pair");

        String csrPem = generateCsr(keyPair.getPrivate(), keyPair.getPublic());
        Timber.tag(TAG).d("Generated CSR:\n%s", csrPem);

        String base64Csr = pemTextToBase64(csrPem);
        Timber.tag(TAG).d("Base64 CSR:\n%s", base64Csr);

        CsrResponse response = sendCsr(base64Csr);
        MutualAuthCertificate certificate = parseResponse(response, privateKeyPem);

        lastCertificate.set(certificate);
        return certificate;
    }

    /**
     * @return the most recently issued certificate bundle, or {@code null} if none has been
     * obtained yet (or the last attempt failed).
     */
    @Nullable
    public MutualAuthCertificate getLastCertificate() {
        return lastCertificate.get();
    }

    // =========================================================================
    // Key pair + CSR
    // =========================================================================

    private KeyPair generateKeyPair() throws MutualAuthException {
        try {
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_CURVE);
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(ecSpec, new SecureRandom());
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new MutualAuthException("Failed to generate COTS key pair", e);
        }
    }

    private String generateCsr(PrivateKey privateKey, PublicKey publicKey) throws MutualAuthException {
        try {
            X500NameBuilder dnBuilder = new X500NameBuilder(BCStyle.INSTANCE);
            dnBuilder.addRDN(BCStyle.CN, csrSubject.commonName);
            dnBuilder.addRDN(BCStyle.OU, csrSubject.organizationUnit);
            dnBuilder.addRDN(BCStyle.O, csrSubject.organization);
            dnBuilder.addRDN(BCStyle.L, csrSubject.locality);
            dnBuilder.addRDN(BCStyle.ST, csrSubject.state);
            dnBuilder.addRDN(BCStyle.C, csrSubject.country);
            dnBuilder.addRDN(BCStyle.E, csrSubject.email);

            JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM);
            signerBuilder.setProvider(new BouncyCastleProvider());
            ContentSigner signer = signerBuilder.build(privateKey);

            JcaPKCS10CertificationRequestBuilder csrBuilder =
                    new JcaPKCS10CertificationRequestBuilder(dnBuilder.build(), publicKey);
            PKCS10CertificationRequest csr = csrBuilder.build(signer);

            StringWriter stringWriter = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
                pemWriter.writeObject(csr);
                pemWriter.flush();
            }
            return stringWriter.toString();
        } catch (Exception e) {
            throw new MutualAuthException("Failed to generate CSR", e);
        }
    }

    private String privateKeyToPkcs8Pem(PrivateKey privateKey) throws MutualAuthException {
        byte[] pkcs8Der = privateKey.getEncoded();
        if (pkcs8Der == null) {
            throw new MutualAuthException("COTS private key does not support encoding");
        }
        return toPem(toBase64(pkcs8Der), PEM_PRIVATE_KEY_BEGIN, PEM_PRIVATE_KEY_END);
    }

    // =========================================================================
    // HTTP
    // =========================================================================

    private CsrResponse sendCsr(String base64Csr) throws MutualAuthException {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();

        Map<String, String> params = new HashMap<>();
        params.put("csr", base64Csr);
        String requestJson = gson.toJson(params);
        Timber.tag(TAG).d("Request JSON: %s", requestJson);
        byte[] body = requestJson.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(buildIssueUrl());
            Timber.tag(TAG).d("--> POST %s", url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readStream(statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream());
            Timber.tag(TAG).d("<-- HTTP %d response: %s", statusCode, responseBody);

            if (statusCode < 200 || statusCode >= 300) {
                throw new MutualAuthException("Certificate server returned HTTP " + statusCode
                        + (responseBody.isEmpty() ? "" : (": " + responseBody)));
            }

            CsrResponse response = gson.fromJson(responseBody, CsrResponse.class);
            if (response == null) {
                throw new MutualAuthException("Certificate response is empty or invalid");
            }
            if (response.getCode() != 0) {
                throw new MutualAuthException("Certificate server error code " + response.getCode()
                        + (response.getMessage() == null ? "" : (": " + response.getMessage())));
            }
            if (response.getData() == null) {
                throw new MutualAuthException("Certificate response contained no data");
            }
            return response;
        } catch (MutualAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new MutualAuthException("Mutual Auth certificate request failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildIssueUrl() {
        String host = certHost.endsWith("/")
                ? certHost.substring(0, certHost.length() - 1)
                : certHost;
        String path = ISSUE_API.startsWith("/") ? ISSUE_API : ("/" + ISSUE_API);
        return host + path;
    }

    private static String readStream(InputStream stream) throws java.io.IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    // =========================================================================
    // Response parsing
    // =========================================================================

    private MutualAuthCertificate parseResponse(CsrResponse response, String privateKeyPem)
            throws MutualAuthException {
        try {
            CsrData data = response.getData();
            String cotsCertPem = base64ToPemCertificate(data.getCertificate());
            String cotsIntermediatePem = base64ToPemCertificate(data.getCotsIntermediate());
            List<String> puckChainPem = base64FullChainToPemList(data.getPuckFullChain());

            Timber.tag(TAG).d("Certificate PEM:\n%s", cotsCertPem);
            Timber.tag(TAG).d("cotsIntermediate PEM:\n%s", cotsIntermediatePem);
            for (int i = 0; i < puckChainPem.size(); i++) {
                Timber.tag(TAG).d("puckFullChain PEM [%d]:\n%s", i, puckChainPem.get(i));
            }
            Timber.tag(TAG).d("Mutual Auth certificate issued (puck chain size=%d)", puckChainPem.size());

            return new MutualAuthCertificate(
                    cotsCertPem,
                    privateKeyPem,
                    cotsIntermediatePem,
                    puckChainPem
            );
        } catch (MutualAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new MutualAuthException("Failed to parse Mutual Auth certificate response", e);
        }
    }

    private String base64ToPemCertificate(String base64Cert) throws MutualAuthException {
        if (base64Cert == null || base64Cert.trim().isEmpty()) {
            throw new MutualAuthException("Certificate value was empty in server response");
        }
        return toPem(base64Cert, PEM_CERTIFICATE_BEGIN, PEM_CERTIFICATE_END);
    }

    private List<String> base64FullChainToPemList(String fullChainBase64) throws MutualAuthException {
        if (fullChainBase64 == null || fullChainBase64.trim().isEmpty()) {
            throw new MutualAuthException("PUCK full chain was empty in server response");
        }
        String fullChainPem = new String(fromBase64(fullChainBase64), StandardCharsets.UTF_8);

        List<String> certificates = new ArrayList<>();
        Matcher matcher = CERT_PATTERN.matcher(fullChainPem);
        while (matcher.find()) {
            certificates.add(matcher.group().trim() + "\n");
        }
        if (certificates.isEmpty()) {
            throw new MutualAuthException("No PUCK certificate found in full chain");
        }
        return certificates;
    }

    // =========================================================================
    // Base64 / PEM helpers
    // =========================================================================

    private static String toBase64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static byte[] fromBase64(String base64) {
        return Base64.decode(base64.replaceAll("\\s+", ""), Base64.DEFAULT);
    }

    private static String pemTextToBase64(String pemText) {
        return toBase64(pemText.getBytes(StandardCharsets.UTF_8));
    }

    private static String toPem(String base64, String begin, String end) {
        String normalized = base64.replaceAll("\\s+", "");
        StringBuilder pem = new StringBuilder();
        pem.append(begin).append('\n');
        for (int i = 0; i < normalized.length(); i += PEM_LINE_LENGTH) {
            int lineEnd = Math.min(i + PEM_LINE_LENGTH, normalized.length());
            pem.append(normalized, i, lineEnd).append('\n');
        }
        pem.append(end).append('\n');
        return pem.toString();
    }

    /**
     * Configurable subject fields for the generated CSR. Defaults are provided so Mutual Auth can
     * work out of the box against the sandbox, while integrators can override device-identifying
     * values for PVT/production enrollment.
     */
    public static class CsrSubject {
        private final String commonName;
        private final String organizationUnit;
        private final String organization;
        private final String locality;
        private final String state;
        private final String country;
        private final String email;

        public CsrSubject(String commonName,
                          String organizationUnit,
                          String organization,
                          String locality,
                          String state,
                          String country,
                          String email) {
            this.commonName = commonName;
            this.organizationUnit = organizationUnit;
            this.organization = organization;
            this.locality = locality;
            this.state = state;
            this.country = country;
            this.email = email;
        }

        public static CsrSubject defaults() {
            return new CsrSubject(
                    "GP-PUCK1000",
                    "Payment",
                    "GlobalPayments",
                    "Locality",
                    "State",
                    "US",
                    "support@globalpay.com"
            );
        }
    }
}
