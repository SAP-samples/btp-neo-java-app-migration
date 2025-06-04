# Example for Persistence with JDBC (SDK for Java Web) in the Neo Environment

## Prerequisites

This sample project requires access to a database to show the basic usage of JDBC in the SAP BTP. Make sure that your account has access to a database system where you can create a schema. You can create that schema on a productive SAP HANA Cloud instance, an SAP HANA database with multitenant database container support, an SAP ASE database system, or another database system available for your account.

## Provide Access to a Schema

1. Deploy the sample application to a subaccount on SAP BTP, but do not start it right away. Starting the application will fail with an error because there is no database yet.
2. Create a schema on a database system that is accessible to your account.
3. Bind the sample application to the schema you have created.
4. Start the sample application.

For more information, see [Tutorial: Adding Persistence with JDBC (SDK for Java Web) | SAP Help Portal](https://help.sap.com/docs/btp/sap-btp-neo-environment/tutorial-adding-persistence-with-jdbc-sdk-for-java-web#loioe4c52854bb571014aeb88753d0dad158__section_deployCloud).