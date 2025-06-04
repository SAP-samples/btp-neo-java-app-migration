package com.sap.cloud.sample.mail.session;

import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class MailPropertiesHandler {

    private static final String DESTINATION_NAME = "Name";
    private static final String DESTINATION_TYPE = "Type";
    private static final String DESTINATION_PROXY_TYPE = "ProxyType";
    private static final String MAIL_PASSWORD = "mail.password";
    private static final String MAIL_USER = "mail.user";
    private static final String PROXY_TYPE_ON_PREMISE = "OnPremise";

    private final Destination destination;
    private static final Logger LOGGER = LoggerFactory.getLogger(MailPropertiesHandler.class);

    public MailPropertiesHandler(Destination destination) {
        this.destination = destination;
    }

    public String getMailName() {
        return destination.get(MAIL_USER, String.class).getOrNull();
    }

    public String getMailPassword() {
        return destination.get(MAIL_PASSWORD, String.class).getOrNull();
    }

    public boolean isOnPremiseTransport(){
        return PROXY_TYPE_ON_PREMISE.equals(destination.get(DESTINATION_PROXY_TYPE, String.class).getOrNull());
    }

    public Properties getSessionProperties() {
        Properties result = new Properties();
        destination.getPropertyNames()
            .forEach(p -> {
               if (isSessionRelevantProperty(p)){
                    LOGGER.debug("Adding property {}", p);
                    result.put(p, destination.get(p).getOrNull());
               }
            });
        return result;
    }

    private boolean isSessionRelevantProperty(String keyName) {
        return !keyName.equalsIgnoreCase(DESTINATION_TYPE) && !keyName.equalsIgnoreCase(DESTINATION_NAME)
                && (!keyName.equalsIgnoreCase(MAIL_PASSWORD));

    }

}
