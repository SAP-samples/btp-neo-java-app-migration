package com.example.document;

import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.security.auth.DestroyFailedException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

public class SSLContextProvider {
    private static final Logger logger = LoggerFactory.getLogger(SSLContextProvider.class);

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String CERTIFICATE_ALIAS = "credstore_mtls";

    private final KeyParser keyParser;
    private final CertificateParser certificateParser;
    private PrivateKey privateKey;

    public SSLContextProvider() {
        this.keyParser = new KeyParser();
        this.certificateParser = new CertificateParser();
    }

    public SSLContext setupSSLContext(ServiceCredentials serviceCredentials) throws GeneralSecurityException, IOException {
        logger.info("Setting up SSL Context for mTLS.");

        String serviceCertificate = serviceCredentials.getCertificate();
        String serviceKey = serviceCredentials.getKey();

        if (serviceCertificate.isEmpty() || serviceKey.isEmpty()) {
            logger.error("Client certificate or private key not found in service binding!");
            throw new RuntimeException("Client certificate or private key not found in service binding!");
        }

        Certificate[] certificate = certificateParser.parseCertificate(serviceCertificate);
        privateKey = keyParser.parseKey(serviceKey);

        logger.info("Successfully retrieved private key and certificate.");

        KeyStore keyStore = createKeyStore(privateKey, certificate);

        SSLContext sslContext = new SSLContextBuilder()
                .loadKeyMaterial(keyStore, new char[] {})
                .build();

        logger.info("SSL Context successfully initialized with protocol: {}", sslContext.getProtocol());
        return sslContext;
    }

    private KeyStore createKeyStore(PrivateKey privateKey, Certificate[] certificate) throws GeneralSecurityException, IOException {
        logger.info("Creating KeyStore for mTLS.");

        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(null, null);

            keyStore.setKeyEntry(CERTIFICATE_ALIAS, privateKey, null, certificate);

            logger.info("KeyStore successfully created with alias: {}", CERTIFICATE_ALIAS);
            return keyStore;
        } catch (GeneralSecurityException | IOException e) {
            logger.error("Error creating KeyStore for mTLS", e);
            throw e;
        }
    }

    public void destroyPrivateKey() {
        logger.debug("Attempting to destroy private key");
        if (privateKey == null) {
            logger.warn("Private key is already null, nothing to destroy");
            return;
        }
        try {
            privateKey.destroy();
            logger.info("Private key successfully destroyed.");
        } catch (final DestroyFailedException e) {
            logger.error("Failed to destroy private key.", e);
        }
    }
}
