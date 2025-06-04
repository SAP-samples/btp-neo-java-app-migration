package com.sap.cloud.sample.mail.session;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;

public class MailAuthenticator extends Authenticator {

    private final String userName;
    private final String password;

    public MailAuthenticator(String userName, String password) {
        this.userName = userName;
        this.password = password;
    }

    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(userName, password);
    }
}