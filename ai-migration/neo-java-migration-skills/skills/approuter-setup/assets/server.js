var approuter = require('@sap/approuter');
var ar = approuter();

ar.start({
    extensions: [
        require('./authentication-challenge-handler.js')
    ]
});
