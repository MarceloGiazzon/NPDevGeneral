# RuntimeHost: Base Template Migration Digest

## Purpose
Reusable static Spring Boot runtime host base/template.

## Current Role
This folder is copied by NPDevGenerator when assembling generated final apps.

## Important Note
This template is not built directly. Generated final apps are assembled outside this folder and receive materialized Gradle build files plus generated artifacts.
Use this digest plus PROJECT_DIGEST.md to keep the RuntimeHost boundary clean.

## Checkpoint 2 - RuntimeHost Postgres Profile Fidelity

RuntimeHost tests now carry a dedicated `test,postgres` profile in `src/test/resources/application-postgres.yml`.
That profile uses the Testcontainers JDBC driver with `jdbc:tc:postgresql:15:///npdev_test?TC_REUSABLE=true`, keeps Flyway enabled, disables Flyway clean behavior, and sets `npdev.runtime.mode=postgres`.

The RuntimeHost template now separates unit-friendly template checks from generated-runtime integration proof:

- `./gradlew test` excludes generated-runtime-dependent source and test classes when the `npdev-generated` mount is absent, so template-local unit checks stay runnable.
- `./gradlew integrationTest` owns generated-runtime-dependent RuntimeHost integration tests and fails before compilation with a `generated-runtime-mount missing` message when required generated runtime classes are not present.
- Linux maturity CI generates the canonical RuntimeHost sample app before running `./gradlew integrationTest` from `NPDevSamples/canonical-demo/Output/App`, which provides the generated runtime mount and runs the selected Postgres/Testcontainers proof in the assembled app boundary.
- The CP2 integration source set is intentionally limited to `PublicationRollbackE2EIT`, `TenantIsolationE2EIT`, and `JwtAuthExternalBetaIT` plus their shared base class so the generated-app proof does not compile scenario-specific step tests for other sample models.

Local setup notes:

- Docker Desktop or a compatible Docker daemon must be running before the selected RuntimeHost Postgres proof is executed.
- On Windows, the generated app Gradle test task supplies the Docker Desktop npipe Testcontainers settings when `DOCKER_HOST` is not already set.
- H2 remains available to unit and slice tests through `application-test.yml`, but `application-postgres.yml` overrides the datasource, driver, and dialect whenever the `postgres` profile is active.
- Generated-runtime integration tests are explicitly JUnit-tagged as `integration`; the integration source set compiles them separately and the `integrationTest` task runs that tag only.

## Checkpoint 1 - Linux RuntimeHost Spring Integration Coverage

The Linux maturity workflow now runs the selected RuntimeHost Spring integration tests with `-Dspring.profiles.active=test,postgres`:

- `com.finalexec.PublicationRollbackE2EIT`
- `com.finalexec.TenantIsolationE2EIT`
- `com.finalexec.JwtAuthExternalBetaIT`

The formerly misleading standalone `TenantIsolationIT` name has been replaced by `PublicationChainTenantReferenceValidationTest`, which describes the service-layer cross-tenant reference rejection behavior without implying a full Spring integration test.

CP1 claims the Linux CI wiring only. Direct RuntimeHost execution from this template folder is intentionally not used as CP1 proof because generated runtime classes are materialized by assembled final apps. The generated-app RuntimeHost execution proof is deferred to Checkpoint 2 by human correction during CP1 review.
