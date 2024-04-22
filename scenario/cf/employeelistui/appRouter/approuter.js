var approuter = require('@sap/approuter');

var ar = approuter();

ar.beforeRequestHandler.use('/', function myMiddleware(request, response, next) {
    request.headers["x-my-ext"] = "passed"; // in case you need to pass a custom header to the requested service
    // response.setHeader("X-Xss-Protection", "1"); // in case you need to add a custom header to the requesting service in a response
    // if (request.url.includes("resources/sap/ui/core/messagebundle")) {
    //     console.log("Here we are");
    // }
    next();
});
ar.start();