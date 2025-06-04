package com.sap.cloud.sample.mail.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;

import com.sun.mail.smtp.SMTPTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OnPremiseSMTPTransport extends SMTPTransport{


    private static final String CLOUD_CONNECTOR_LOCATION_ID = "CloudConnectorLocationId";
    private static final String MAIL_TRANSPORT_PROTOCOL = "mail.transport.protocol";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String MAIL = "mail.";
    private static final String DOT = ".";

    private static final Logger LOGGER = LoggerFactory.getLogger(OnPremiseSMTPTransport.class);

    public OnPremiseSMTPTransport(Session session, URLName urlname) {
        super(session, urlname);
    }

    @Override
    public void connect() throws MessagingException {
        LOGGER.debug("Connecting to OnPremise SMTP Transport");
        String transportProperty = session.getProperty(MAIL_TRANSPORT_PROTOCOL).toLowerCase();
        String sccLocationId = session.getProperty(CLOUD_CONNECTOR_LOCATION_ID);
        Socket socket = new ConnectivitySocks5ProxySocket(sccLocationId);
        try {
            socket.connect(new InetSocketAddress(getTransportProperty(transportProperty, HOST),
                    Integer.parseInt(getTransportProperty(transportProperty, PORT))));
            LOGGER.debug("Connected to the SMTP Transport successfully");
        } catch (NumberFormatException | IOException e) {
            throw new MessagingException(e.getMessage(), e);
        }
        super.connect(socket);

    }

    private String getTransportProperty(String transport, String prop) {
        return session.getProperty(MAIL + transport + DOT + prop);
    }

}
