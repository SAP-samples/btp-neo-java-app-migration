package com.sap.cloud.sample.mail.session;

import jakarta.mail.Provider;

public class OnPremiseSMTPProvider extends Provider {

    private static final String SAP = "SAP";
    private static final String SMTP = "smtp";
    private static final String VERSION = "1.0.0";

    public OnPremiseSMTPProvider() {
        super(Provider.Type.TRANSPORT, SMTP, OnPremiseSMTPTransport.class.getName(), SAP, VERSION);
    }
}
