package com.sap.cloud.sample.mail.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Base64; // or any other library for base64 encoding
import java.util.Map;

import com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.sap.cloud.security.client.HttpClientFactory;
import com.sap.cloud.security.config.ClientIdentity;
import com.sap.cloud.security.config.OAuth2ServiceConfiguration;
import com.sap.cloud.security.config.OAuth2ServiceConfigurationBuilder;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.xsuaa.client.DefaultOAuth2TokenService;
import com.sap.cloud.security.xsuaa.client.OAuth2ServiceEndpointsProvider;
import com.sap.cloud.security.xsuaa.client.OAuth2TokenResponse;
import com.sap.cloud.security.xsuaa.client.XsuaaDefaultEndpoints;
import com.sap.cloud.security.xsuaa.tokenflows.TokenFlowException;
import com.sap.cloud.security.xsuaa.tokenflows.XsuaaTokenFlows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectivitySocks5ProxySocket extends Socket {

    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte SOCKS5_JWT_AUTHENTICATION_METHOD = (byte) 0x80;
    private static final byte SOCKS5_JWT_AUTHENTICATION_METHOD_VERSION = 0x01;
    private static final byte SOCKS5_COMMAND_CONNECT_BYTE = 0x01;
    private static final byte SOCKS5_COMMAND_REQUEST_RESERVED_BYTE = 0x00;
    private static final byte SOCKS5_COMMAND_ADDRESS_TYPE_IPv4_BYTE = 0x01;
    private static final byte SOCKS5_COMMAND_ADDRESS_TYPE_DOMAIN_BYTE = 0x03;
    private static final byte SOCKS5_AUTHENTICATION_METHODS_COUNT = 0x01;
    private static final int SOCKS5_JWT_AUTHENTICATION_METHOD_UNSIGNED_VALUE = 0x80 & 0xFF;
    private static final byte SOCKS5_AUTHENTICATION_SUCCESS_BYTE = 0x00;

    private static final String SOCKS5_PROXY_HOST_PROPERTY = "onpremise_proxy_host";
    private static final String SOCKS5_PROXY_PORT_PROPERTY = "onpremise_socks5_proxy_port";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectivitySocks5ProxySocket.class);
    private static final String CONNECTIVITY = "connectivity";
    private static final String CLIENT_ID = "clientid";
    private static final String CLIENT_SECRET = "clientsecret";
    private static final String URL = "url";

    private String sccLocationId;

    public ConnectivitySocks5ProxySocket(String sccLocationId) {
        this.sccLocationId = sccLocationId != null ? Base64.getEncoder().encodeToString(sccLocationId.getBytes()) : "";
    }


    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        Map<String, Object> credentials = extractEnvironmentCredentials();

        super.connect(getProxyAddress(credentials), timeout);

        OutputStream outputStream = getOutputStream();

        executeSOCKS5InitialRequest(outputStream);

        executeSOCKS5AuthenticationRequest(outputStream, credentials);

        executeSOCKS5ConnectRequest(outputStream, (InetSocketAddress) endpoint);
    }

    private InetSocketAddress getProxyAddress(Map<String, Object> credentials) {
        LOGGER.debug("Extracting the SOCKS5 proxy host and port");
        String proxyHost = stringValue(credentials, SOCKS5_PROXY_HOST_PROPERTY);
        int proxyPort = Integer.parseInt(stringValue(credentials, SOCKS5_PROXY_PORT_PROPERTY));
        return new InetSocketAddress(proxyHost, proxyPort);
    }

    private String getAuthnToken(Map<String, Object> credentials) {
        LOGGER.debug("Retrieving the authentication token");
        try {
            DefaultOAuth2TokenService defaultOAuth2TokenService = new DefaultOAuth2TokenService(HttpClientFactory.create(null));
            OAuth2ServiceConfigurationBuilder builder = OAuth2ServiceConfigurationBuilder.forService(Service.XSUAA);
            OAuth2ServiceConfiguration config = builder.withClientId(stringValue(credentials, CLIENT_ID))
                                           .withClientSecret(stringValue(credentials, CLIENT_SECRET))
                                           .withUrl(stringValue(credentials, URL)).build();
            OAuth2ServiceEndpointsProvider endpointsProvider = new XsuaaDefaultEndpoints(config);
            ClientIdentity clientIdentity = config.getClientIdentity();


            XsuaaTokenFlows tokenFlows = new XsuaaTokenFlows(defaultOAuth2TokenService, endpointsProvider, clientIdentity);
            OAuth2TokenResponse serviceTokenResponse = tokenFlows.clientCredentialsTokenFlow().execute();
            String accessToken = serviceTokenResponse.getAccessToken();
            return accessToken;
        } catch (IllegalArgumentException | TokenFlowException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Read the connectivity service credentials from the platform service binding.
     *
     * <p>Uses the SAP Cloud SDK Service Binding Accessor (already on the classpath via
     * {@code sdk-modules-bom}) instead of parsing {@code VCAP_SERVICES} JSON manually.
     * An earlier version of this class used {@code org.json}, which the SDK BOM manages
     * at {@code provided} scope — adding the dependency made it compile but the JAR was
     * stripped from the WAR, causing {@code NoClassDefFoundError} at runtime once the
     * connectivity service was actually used.</p>
     */
    private Map<String, Object> extractEnvironmentCredentials() {
        LOGGER.debug("Extracting the environment credentials for service '{}'", CONNECTIVITY);
        ServiceBinding binding = DefaultServiceBindingAccessor.getInstance().getServiceBindings().stream()
                .filter(b -> CONNECTIVITY.equalsIgnoreCase(b.getServiceName().orElse(null)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No '" + CONNECTIVITY + "' service binding found. "
                                + "Bind the connectivity service to the application before sending mail to on-premise hosts."));
        return binding.getCredentials();
    }

    private static String stringValue(Map<String, Object> credentials, String key) {
        Object value = credentials.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Connectivity service binding is missing required credential '" + key + "'");
        }
        return value.toString();
    }

    private void executeSOCKS5InitialRequest(OutputStream outputStream) throws IOException {
        LOGGER.debug("Executing the SOCKS5 initial request");
        byte[] initialRequest = createInitialSOCKS5Request();
        outputStream.write(initialRequest);

        assertServerInitialResponse();
    }

    private byte[] createInitialSOCKS5Request() throws IOException {
        LOGGER.debug("Creating the initial SOCKS5 request");
        ByteArrayOutputStream byteArraysStream = new ByteArrayOutputStream();
        try {
            byteArraysStream.write(SOCKS5_VERSION);
            byteArraysStream.write(SOCKS5_AUTHENTICATION_METHODS_COUNT);
            byteArraysStream.write(SOCKS5_JWT_AUTHENTICATION_METHOD);
            return byteArraysStream.toByteArray();
        } finally {
            byteArraysStream.close();
        }
    }

    private void assertServerInitialResponse() throws IOException {
        LOGGER.debug("Asserting the server initial response");
        InputStream inputStream = getInputStream();

        int versionByte = inputStream.read();
        if (SOCKS5_VERSION != versionByte) {
            throw new SocketException(String.format("Unsupported SOCKS version - expected %s, but received %s", SOCKS5_VERSION, versionByte));
        }

        int authenticationMethodValue = inputStream.read();
        if (SOCKS5_JWT_AUTHENTICATION_METHOD_UNSIGNED_VALUE != authenticationMethodValue) {
            throw new SocketException(String.format("Unsupported authentication method value - expected %s, but received %s",
                    SOCKS5_JWT_AUTHENTICATION_METHOD_UNSIGNED_VALUE, authenticationMethodValue));
        }
    }

    private void executeSOCKS5AuthenticationRequest(OutputStream outputStream, Map<String, Object> credentials) throws IOException {
        LOGGER.debug("Executing the SOCKS5 authentication request");
        byte[] authenticationRequest = createJWTAuthenticationRequest(credentials);
        outputStream.write(authenticationRequest);

        assertAuthenticationResponse();
    }

    private byte[] createJWTAuthenticationRequest(Map<String, Object> credentials) throws IOException {
        LOGGER.debug("Creating the JWT authentication request");
        ByteArrayOutputStream byteArraysStream = new ByteArrayOutputStream();
        String jwtToken = getAuthnToken(credentials);
        try {
            byteArraysStream.write(SOCKS5_JWT_AUTHENTICATION_METHOD_VERSION);
            byteArraysStream.write(ByteBuffer.allocate(4).putInt(jwtToken.getBytes().length).array());
            byteArraysStream.write(jwtToken.getBytes());
            byteArraysStream.write(ByteBuffer.allocate(1).put((byte) sccLocationId.getBytes().length).array());
            byteArraysStream.write(sccLocationId.getBytes());
            return byteArraysStream.toByteArray();
        } finally {
            byteArraysStream.close();
        }
    }

    private void assertAuthenticationResponse() throws IOException {
        LOGGER.debug("Asserting the authentication response");
        InputStream inputStream = getInputStream();

        int authenticationMethodVersion = inputStream.read();
        if (SOCKS5_JWT_AUTHENTICATION_METHOD_VERSION != authenticationMethodVersion) {
            throw new SocketException(String.format("Unsupported authentication method version - expected %s, but received %s",
                    SOCKS5_JWT_AUTHENTICATION_METHOD_VERSION, authenticationMethodVersion));
        }

        int authenticationStatus = inputStream.read();
        if (SOCKS5_AUTHENTICATION_SUCCESS_BYTE != authenticationStatus) {
            throw new SocketException("Authentication failed!");
        }
    }

    private void executeSOCKS5ConnectRequest(OutputStream outputStream, InetSocketAddress endpoint) throws IOException {
        byte[] commandRequest = createConnectCommandRequest(endpoint);
        outputStream.write(commandRequest);

        assertConnectCommandResponse();
    }

    private byte[] createConnectCommandRequest(InetSocketAddress endpoint) throws IOException {
        String host = endpoint.getHostName();
        int port = endpoint.getPort();
        ByteArrayOutputStream byteArraysStream = new ByteArrayOutputStream();
        try {
            byteArraysStream.write(SOCKS5_VERSION);
            byteArraysStream.write(SOCKS5_COMMAND_CONNECT_BYTE);
            byteArraysStream.write(SOCKS5_COMMAND_REQUEST_RESERVED_BYTE);
            byte[] hostToIPv4 = parseHostToIPv4(host);
            if (hostToIPv4 != null) {
                byteArraysStream.write(SOCKS5_COMMAND_ADDRESS_TYPE_IPv4_BYTE);
                byteArraysStream.write(hostToIPv4);
            } else {
                byteArraysStream.write(SOCKS5_COMMAND_ADDRESS_TYPE_DOMAIN_BYTE);
                byteArraysStream.write(ByteBuffer.allocate(1).put((byte) host.getBytes().length).array());
                byteArraysStream.write(host.getBytes());
            }
            byteArraysStream.write(ByteBuffer.allocate(2).putShort((short) port).array());
            return byteArraysStream.toByteArray();
        } finally {
            byteArraysStream.close();
        }
    }

    private void assertConnectCommandResponse() throws IOException {
        InputStream inputStream = getInputStream();

        int versionByte = inputStream.read();
        if (SOCKS5_VERSION != versionByte) {
            throw new SocketException(String.format("Unsupported SOCKS version - expected %s, but received %s", SOCKS5_VERSION, versionByte));
        }

        int connectStatusByte = inputStream.read();
        assertConnectStatus(connectStatusByte);

        readRemainingCommandResponseBytes(inputStream);
    }

    private void assertConnectStatus(int commandConnectStatus) throws IOException {
        if (commandConnectStatus == 0) {
            return;
        }

        String commandConnectStatusTranslation;
        switch (commandConnectStatus) {
            case 1:
                commandConnectStatusTranslation = "FAILURE";
                break;
            case 2:
                commandConnectStatusTranslation = "FORBIDDEN";
                break;
            case 3:
                commandConnectStatusTranslation = "NETWORK_UNREACHABLE";
                break;
            case 4:
                commandConnectStatusTranslation = "HOST_UNREACHABLE";
                break;
            case 5:
                commandConnectStatusTranslation = "CONNECTION_REFUSED";
                break;
            case 6:
                commandConnectStatusTranslation = "TTL_EXPIRED";
                break;
            case 7:
                commandConnectStatusTranslation = "COMMAND_UNSUPPORTED";
                break;
            case 8:
                commandConnectStatusTranslation = "ADDRESS_UNSUPPORTED";
                break;
            default:
                commandConnectStatusTranslation = "UNKNOWN";
                break;
        }
        throw new SocketException("SOCKS5 command failed with status: " + commandConnectStatusTranslation);
    }

    private byte[] parseHostToIPv4(String hostName) {
        byte[] parsedHostName = null;
        String[] virtualHostOctets = hostName.split("\\.", -1);
        int octetsCount = virtualHostOctets.length;
        if (octetsCount == 4) {
            try {
                byte[] ipOctets = new byte[octetsCount];
                for (int i = 0; i < octetsCount; i++) {
                    int currentOctet = Integer.parseInt(virtualHostOctets[i]);
                    if ((currentOctet < 0) || (currentOctet > 255)) {
                        throw new IllegalArgumentException(String.format("Provided octet %s is not in the range of [0-255]", currentOctet));
                    }
                    ipOctets[i] = (byte) currentOctet;
                }
                parsedHostName = ipOctets;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        return parsedHostName;
    }

    private void readRemainingCommandResponseBytes(InputStream inputStream) throws IOException {
        inputStream.read(); // skipping over SOCKS5 reserved byte
        int addressTypeByte = inputStream.read();
        if (SOCKS5_COMMAND_ADDRESS_TYPE_IPv4_BYTE == addressTypeByte) {
            for (int i = 0; i < 6; i++) {
                inputStream.read();
            }
        } else if (SOCKS5_COMMAND_ADDRESS_TYPE_DOMAIN_BYTE == addressTypeByte) {
            int domainNameLength = inputStream.read();
            int portBytes = 2;
            inputStream.read(new byte[domainNameLength + portBytes], 0, domainNameLength + portBytes);
        }
    }
}
