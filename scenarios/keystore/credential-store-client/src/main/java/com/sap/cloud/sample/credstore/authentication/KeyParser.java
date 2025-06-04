package com.sap.cloud.sample.credstore.authentication;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.security.PrivateKey;

public class KeyParser {
    private static final Logger logger = LoggerFactory.getLogger(KeyParser.class);

    public PrivateKey parseKey(String key) {
        try (PEMParser pemParser = new PEMParser(new StringReader(key))) {
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (object instanceof PEMKeyPair pemKeyPair) {
                logger.info("Detected PKCS1 format (RSA PRIVATE KEY), converting to PKCS8...");
                PrivateKeyInfo privateKeyInfo = PrivateKeyInfo.getInstance(pemKeyPair.getPrivateKeyInfo());

                PrivateKey privateKey = converter.getPrivateKey(privateKeyInfo);
                logger.info("Successfully converted to PKCS8.");
                return privateKey;
            }

            logger.error("Unsupported private key format: Not PKCS1");
            throw new IllegalArgumentException("Unsupported private key format: Not PKCS1");
        } catch (Exception e) {
            logger.error("Failed to parse private key (Only PKCS1 supported): {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to parse private key (Only PKCS1 supported)", e);
        }
    }
}
