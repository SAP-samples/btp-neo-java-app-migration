function redirect(response, locationURL) {
    console.log('Redirecting to: [' + locationURL + ']');
    response.setHeader('Location', locationURL);
    response.statusCode = 303;
    response.end();
}

function handleAuthenticationChallenge(context, authenticateHeader) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (authenticateHeader.includes('SAML2 realm="Identity Authentication Service"')) {
        console.log('Handling SAML2 (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/authentication/endpoint' + incomingRequest.url + ']');
    } else if (authenticateHeader.includes('Basic realm="SAP HANA Cloud Platform"')) {
        console.log('Handling BASIC (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/basic/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/basic/authentication/endpoint' + incomingRequest.url + ']');
    }
}

function handleLogout(context, logoutRequest) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (logoutRequest.includes('logout-request')) {
        console.log('Triggering logout.');
        incomingResponse.setHeader('Location', '/logout/endpoint?originalURL=' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/logout/endpoint?originalURL=' + incomingRequest.url + ']');
    }
}

module.exports = {
    insertMiddleware: {
        beforeRequestHandler: [
            {
                handler: function authenticationChallengeHandler(request, response, callNextHandler) {
                    console.log('Handling request with path: [' + request.url + ']');

                    if (request.url.startsWith('/authentication/endpoint')) {
                        let locationURL = request.url.substring('/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/basic/authentication/endpoint')) {
                        let locationURL = request.url.substring('/basic/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/logout/callback?originalURL=')) {
                        let locationURL = request.url.substring('/logout/callback?originalURL='.length);
                        redirect(response, locationURL);
                    } else {
                        request.afterRequestHandler = function (context, done) {
                            let outgoingResponse = context.outgoingResponse;
                            let authenticateHeader = outgoingResponse.headers['www-authenticate'];
                            let logoutRequest = outgoingResponse.headers['com.sap.cloud.security.logout'];

                            console.log('Received headers from target system are: [' + JSON.stringify(outgoingResponse.headers) + ']');

                            if (authenticateHeader !== undefined) {
                                console.log('WWW-Authenticate header value is: [' + authenticateHeader + ']');
                                handleAuthenticationChallenge(context, authenticateHeader);
                            }
                
                            if (logoutRequest !== undefined) {
                                console.log('com.sap.cloud.security.logout header value is: [' + logoutRequest + ']');
                                handleLogout(context, logoutRequest);
                            }

                            console.log('Finalizing the request.');
                            done(null, context.incomingResponse);
                        };

                        callNextHandler();
                    }
                }
            }
        ]
    }
};
