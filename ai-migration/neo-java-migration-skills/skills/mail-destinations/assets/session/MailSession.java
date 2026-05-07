package com.sap.cloud.sample.mail.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.Authenticator;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import java.util.Properties;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;

/**
 * Creates mail sessions from destination configuration.
 * This class replaces the Neo JNDI-based mail session lookup with
 * destination-based configuration for Cloud Foundry.
 */
public class MailSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailSession.class);

    /**
     * Get a mail session configured from a destination.
     *
     * @param name The destination name containing mail configuration
     * @return Configured mail Session
     * @throws NoSuchProviderException if the transport provider cannot be found
     */
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
