package com.sap.cloud.sample.mail.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.Authenticator;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import java.util.Properties;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;

public class MailSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailSession.class);

    public Session getSession(String name) throws NoSuchProviderException {
        LOGGER.debug("Going to obtain mail session.");
        Destination destination = DestinationAccessor.getDestination(name);
        final MailPropertiesHandler handler = new MailPropertiesHandler(destination);
        final String mailUser = handler.getMailName();
        final Properties sessionProperties = handler.getSessionProperties();
        Authenticator authenticator = null;
        if (mailUser != null) {
            authenticator = new MailAuthenticator(mailUser, handler.getMailPassword());
        }

        Session session = Session.getInstance(sessionProperties, authenticator);
        
        if(handler.isOnPremiseTransport()){
            session.setProvider(new OnPremiseSMTPProvider());
            LOGGER.debug("SAP OnPremise Provider is set");
        }

        return session;
    }

}
