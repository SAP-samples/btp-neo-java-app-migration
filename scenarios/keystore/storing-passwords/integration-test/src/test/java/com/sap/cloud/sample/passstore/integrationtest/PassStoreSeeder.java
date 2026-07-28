package com.sap.cloud.sample.passstore.integrationtest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;

/**
 * Seeds the credential store with the password the integration test expects
 * ({@code test} in namespace {@code pass-storage-app}) before the tests run, so
 * the suite is self-contained rather than depending on a password that was
 * manually added via the SAP BTP cockpit (see cf/README.md).
 *
 * <p>Requires the credential store binding JSON (a service key of the same
 * credstore instance the app is bound to) in the {@code CREDSTORE_BINDING}
 * environment variable. When it is absent, seeding is skipped.
 *
 * <p>The write protocol was verified empirically against the live service:
 * mTLS transport (binding {@code certificate}/{@code key}); {@code POST {url}/password}
 * with header {@code sapcp-credstore-namespace}; body a JWE (RSA-OAEP-256 +
 * A256GCM) encrypted to {@code encryption.server_public_key} whose protected
 * header carries an {@code iat} (issued-at, epoch seconds) — required by the
 * service for replay protection — encrypting the credential JSON
 * {@code {"name","value"}} (value is the plaintext password).
 */
final class PassStoreSeeder {

    static final String BINDING_ENV = "CREDSTORE_BINDING";
    private static final String NAMESPACE = "pass-storage-app";
    private static final String PASSWORD_NAME = "test";
    private static final String NAMESPACE_HEADER = "sapcp-credstore-namespace";
    private static final String JOSE = "application/jose";

    // The credstore broker may emit PEM fields with literal newline characters
    // inside JSON strings; allow those unescaped control chars when parsing.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private PassStoreSeeder() {
    }

    /**
     * Ensures the password {@code test} exists in the credential store. No-op
     * when {@code CREDSTORE_BINDING} is not set. Idempotent: if the password
     * already exists it is left as-is.
     */
    static void ensurePasswordSeeded() throws Exception {
        String bindingJson = System.getenv(BINDING_ENV);
        if (bindingJson == null || bindingJson.isBlank()) {
            System.out.println("[PassStoreSeeder] " + BINDING_ENV
                    + " not set — skipping seeding (assuming password is provisioned externally).");
            return;
        }

        JsonNode root = MAPPER.readTree(bindingJson);
        JsonNode b = root.has("credentials") ? root.get("credentials") : root;
        String url = b.get("url").asText();
        SSLContext ssl = mtlsContext(b.get("certificate").asText(), b.get("key").asText());
        HttpClient client = HttpClient.newBuilder().sslContext(ssl).build();

        if (passwordExists(client, url)) {
            System.out.println("[PassStoreSeeder] Password '" + PASSWORD_NAME + "' already present — no seeding needed.");
            return;
        }

        RSAPublicKey serverPub = rsaPublicKey(b.get("encryption").get("server_public_key").asText());
        String jwe = buildJwe(serverPub);

        HttpRequest post = HttpRequest.newBuilder()
                .uri(new URI(url + "/password"))
                .header(NAMESPACE_HEADER, NAMESPACE)
                .header("Content-Type", JOSE)
                .POST(HttpRequest.BodyPublishers.ofString(jwe))
                .build();
        HttpResponse<String> resp = client.send(post, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new IllegalStateException("Failed to seed credential store password '" + PASSWORD_NAME
                    + "': HTTP " + resp.statusCode() + " " + resp.body());
        }
        System.out.println("[PassStoreSeeder] Seeded password '" + PASSWORD_NAME + "' (HTTP " + resp.statusCode() + ").");
    }

    private static boolean passwordExists(HttpClient client, String url) throws Exception {
        HttpRequest get = HttpRequest.newBuilder()
                .uri(new URI(url + "/password?name=" + URLEncoder.encode(PASSWORD_NAME, StandardCharsets.UTF_8)))
                .header(NAMESPACE_HEADER, NAMESPACE)
                .header("Content-Type", JOSE)
                .GET()
                .build();
        return client.send(get, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private static String buildJwe(RSAPublicKey serverPub) throws Exception {
        String credJson = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("name", PASSWORD_NAME)
                .put("value", "integration-test-secret"));

        JWEObject jwe = new JWEObject(
                new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                        .customParam("iat", Instant.now().getEpochSecond())
                        .build(),
                new Payload(credJson));
        jwe.encrypt(new RSAEncrypter(serverPub));
        return jwe.serialize();
    }

    private static SSLContext mtlsContext(String certPem, String keyPem) throws Exception {
        // The binding certificate is a full chain (leaf + intermediate). Parse
        // ALL certs, not just the first — presenting only the leaf makes the
        // server reject the handshake (401).
        @SuppressWarnings("unchecked")
        Collection<Certificate> certs = (Collection<Certificate>)
                CertificateFactory.getInstance("X.509")
                        .generateCertificates(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
        Certificate[] chain = certs.toArray(new Certificate[0]);
        RSAPrivateKey key = rsaPrivateKey(keyPem);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("mtls", key, new char[0], chain);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Parses a public key that may be a raw base64 X.509 SubjectPublicKeyInfo or a PEM block. */
    private static RSAPublicKey rsaPublicKey(String pem) throws Exception {
        String b64 = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Parses a private key in PKCS#1 or PKCS#8 PEM, or raw base64 PKCS#8. */
    private static RSAPrivateKey rsaPrivateKey(String pem) throws Exception {
        if (!pem.contains("-----BEGIN")) {
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("\\s", ""));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair kp) {
                return (RSAPrivateKey) conv.getPrivateKey(PrivateKeyInfo.getInstance(kp.getPrivateKeyInfo()));
            }
            if (obj instanceof PrivateKeyInfo pki) {
                return (RSAPrivateKey) conv.getPrivateKey(pki);
            }
            throw new IllegalArgumentException(
                    "Unsupported private key PEM: " + (obj == null ? "null" : obj.getClass()));
        }
    }
}
