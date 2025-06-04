# Migration to Jakarta EE 10 and Java 17

## Table of Contents
- [Overview](#overview)
- [OpenRewrite Recipes for Migration](#openrewrite-recipes-for-migration)
- [Applying Migration Recipes](#applying-migration-recipes)
- [Lessons Learned](#lessons-learned)
- [Examples](#examples)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Overview

Java 17, being a long-term support (LTS) release, offers numerous enhancements and new features that can significantly improve the performance, security, and maintainability of your applications.

Migrating from Java EE to Jakarta EE is crucial for active development, modern Java compatibility, community support, cloud-native readiness, standardization, and future-proofing your applications.

Migration can be a daunting task, especially when dealing with large codebases.  [OpenRewrite](https://docs.openrewrite.org/) is a powerful tool that can automate many of the time-consuming and error-prone aspects of this migration process.


## OpenRewrite Recipes for Migration

OpenRewrite provides a framework for defining and applying transformations to your codebase. Recipes are the core building blocks in OpenRewrite. They are collections of transformations that will be applied to your code. Recipes can be predefined or custom-made to suit specific needs. They are designed to be reusable and composable, allowing you to combine multiple recipes to achieve complex refactoring tasks.

## Applying Migration Recipes

If you want to migrate your codebase to Jakarta EE 10 and Java 17, you can use the predefined recipes. You can see more information about them in OpenRewrite's official [documentation](https://docs.openrewrite.org/recipes/java/migrate).

These recipes have no required configuration options and can be activated by adding a dependency on `org.openrewrite.recipe:rewrite-migrate-java:<latest-version>` in your build file or by running a shell command:
```sh
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:RELEASE \
    -Drewrite.activeRecipes=org.openrewrite.java.migrate.UpgradeToJava17 \
    -Drewrite.activeRecipes=org.openrewrite.java.migrate.jakarta.JakartaEE10 \
    -Drewrite.exportDatatables=true
```

> **Note:**
> 1. Make sure that you have installed Java SE 17 and the latest Maven version.
> 2. Check the `<latest-version>` of the OpenRewrite dependency at [https://mvnrepository.com/artifact/org.openrewrite.recipe/rewrite-migrate-java](https://mvnrepository.com/artifact/org.openrewrite.recipe/rewrite-migrate-java).
> 3. Update to the latest versions of all dependencies and plugins in your pom.xml files. Some dependencies might have been moved or replaced by others. Therefore, make sure that you are referencing the correct dependencies.
> 4. Verify that the code compiles and functions correctly. Be aware that additional modifications to your source code may be necessary.
> 5. After making these updates, test your application thoroughly  to ensure that all changes are functional and stable.

### Lessons Learned

The `org.apache.chemistry.opencmis` libraries have not been migrated to Jakarta. Therefore, if your application uses the document store service, the following exclusions are required:

```xml
<dependencies>
  ...
  <dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
      <artifactId>chemistry-opencmis-client-impl</artifactId>
      <version>${opencmis.version}</version>
      <exclusions>
        <exclusion>
          <groupId>org.apache.cxf</groupId>
          <artifactId>cxf-rt-frontend-jaxws</artifactId>
        </exclusion>
        <exclusion>
          <groupId>org.apache.cxf</groupId>
          <artifactId>cxf-rt-transports-http</artifactId>
        </exclusion>
        <exclusion>
          <groupId>org.apache.cxf</groupId>
          <artifactId>cxf-rt-ws-policy</artifactId>
        </exclusion>
    </exclusions>
  </dependency>
  ...
</dependencies>
```
> Note: Add the excluded libraries as separate dependencies using their latest versions, if applicable.

## Examples
- All examples in the `cf` folder of each scenario are migrated to Jakarta EE 10 and Java 17.

## Related Information
- [SapMachine | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/sapmachine)
- [Bill of Materials (BOM) | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/bill-of-materials-bom)
- [OpenRewrite Migration Composite Recipes | docs.openrewrite.org](https://docs.openrewrite.org/recipes/java/migrate/#composite-recipes)
- [Central Maven Repository | mvnrepository.com](https://mvnrepository.com/repos/central)

## [Additional Scenarios](../../README.md#7-additional-scenarios)