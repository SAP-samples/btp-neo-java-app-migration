package com.sap.cloud.sample.credstore.authentication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

public class CertificateParser {
    private static final Logger logger = LoggerFactory.getLogger(CertificateParser.class);

    private static final String CERTIFICATE_TYPE_X509 = "X.509";

    public Certificate[] parseCertificate(String certificate) {
        if (certificate == null || certificate.trim().isEmpty()) {
            logger.error("Certificate string is null or empty");
            throw new IllegalArgumentException("Certificate string cannot be null or empty.");
        }

        try (InputStream inputStream = new ByteArrayInputStream(certificate.trim().getBytes(StandardCharsets.UTF_8))) {

            CertificateFactory certFactory = CertificateFactory.getInstance(CERTIFICATE_TYPE_X509);
            List<Certificate> certList = new ArrayList<>(certFactory.generateCertificates(inputStream));

            if (certList.isEmpty()) {
                logger.error("No certificates found in the provided input.");
                throw new CertificateException("No valid certificates found.");
            }

            logger.info("Successfully parsed {} certificate(s).", certList.size());
            return certList.toArray(new Certificate[0]);

        } catch (CertificateException | IOException e) {
            logger.error("Failed to parse {} certificate: {}", CERTIFICATE_TYPE_X509, e.getMessage(), e);
            throw new IllegalArgumentException("Could not parse certificate from the binding credentials", e);
        }
    }
}
