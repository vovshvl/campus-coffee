# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CampusCoffee is a Spring Boot application for managing Points of Sale (POS) like cafés and coffee shops on campus. It follows a **hexagonal (ports-and-adapters) architecture** with strict layer separation enforced by ArchUnit tests.

## Architecture

The project uses a **multi-module Gradle structure** (Kotlin DSL) with four modules:

### Module Dependencies
- **domain**: Core business logic, domain models, and port interfaces (no external dependencies except validation).
- **api**: REST API layer with controllers, DTOs, and DTO mappers (depends on: domain).
- **data**: Data layer with JPA entities, repositories, and the OpenStreetMap HTTP client (depends on: domain).
- **application**: Main Spring Boot application that wires everything together (depends on: domain, api, data at runtime).

### Layer Rules (Enforced by ArchUnit)
From `application/src/test/kotlin/de/seuhd/campuscoffee/tests/architecture/ArchitectureTests.kt`:

- **api** layer may only be accessed by **application**.
- **domain** layer may only be accessed by **api**, **data**, and **application**.
- **data** layer may only be accessed by **application**.
- **application** layer may not be accessed by any layer.

### Ports and Adapters Pattern

The domain defines **port interfaces** that adapters implement:

- **API Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/api/`): Generic service interface `CrudService<DOMAIN, ID>` and concrete service interfaces such as `PosService`, `UserService`, and `ReviewService`.
- **Data Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/data/`): Generic data service interface `CrudDataService<DOMAIN, ID>` and concrete service interfaces such as `PosDataService`, `UserDataService`, `ReviewDataService`, and `OsmDataService`.

Service **implementations**:
- API services in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/implementation/`.
- Data services in `data/src/main/kotlin/de/seuhd/campuscoffee/data/implementations/`.

### Generic Base Classes

The codebase uses extensive generics to reduce duplication:

- **CrudController** (`api/src/main/kotlin/de/seuhd/campuscoffee/api/controller/CrudController.kt`): Generic REST controller for CRUD operations.
- **CrudService** / **CrudServiceImpl**: Generic CRUD service interface and implementation.
- **CrudDataService** / **CrudDataServiceImpl**: Generic data service interface and implementation.
- **DtoMapper** / **EntityMapper**: Generic mapping interfaces using MapStruct.

Domain-specific controllers/services extend these base classes (e.g., `PosController extends CrudController<PosDto, Pos, Long>`).

## Build and Run Commands

### Prerequisites
- Docker daemon must be running to use a database in the `dev` profile or to run the tests that use *Testcontainers*.
- Java 25 and Gradle 9.5, provisioned via `mise.toml` (no Gradle wrapper). Run Gradle through mise
  (CI uses `jdx/mise-action`). The build pins a **Java 25 toolchain with no auto-download**, so a
  JDK 25 must be present on the machine — mise supplies it; without it the build fails with "no
  matching toolchains".

### Build

```shell
gradle build
```

### Format and Lint (ktlint)

The Kotlin sources are formatted and linted with ktlint (official Kotlin style, configured via the root
`.editorconfig`). `gradle build` fails on violations because `ktlintCheck` is wired into `check`; apply
the fixes with:

```shell
gradle ktlintFormat
```

### Start PostgreSQL Database

```shell
docker run -d --name db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:17-alpine
```

### Run Application (dev profile)

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile:
- Enables Swagger UI at `http://localhost:8080/api/swagger-ui.html`.
- Enables API docs at `http://localhost:8080/api/api-docs`.
- Registers the dev-only `DevController` (in the `api` layer) under `/api/dev`:
  `GET /api/dev/data` reports the counts, `PUT /api/dev/data` replaces the data with the fixture
  dataset (clear + seed; idempotent), and `DELETE /api/dev/data` clears it.

The application no longer loads any data on startup (in any profile); use the `dev` endpoints above.

### Run Tests

All tests:

```shell
gradle test
```

Single test class:

```shell
gradle test --tests PosServiceTest
```

Single test method:

```shell
gradle test --tests "PosServiceTest.testMethodName"
```

### Code Coverage and Mutation Testing

- **Coverage (JaCoCo)**: the `coverage` subproject (the `jacoco-report-aggregation` plugin) aggregates
  execution data from all modules into one report at
  `coverage/build/reports/jacoco/testCodeCoverageReport/`. Aggregation is required because
  `domain`/`api`/`data` are largely covered by the `application` system and acceptance tests, not by
  their own tests. `gradle build` (or `gradle check`) builds the report and enforces the gate: the
  `coverageGate` task (a `JacocoCoverageVerification` in `coverage/build.gradle.kts`, wired into `check`)
  fails the build when aggregated line or branch coverage is below its minimums (90% line, 80% branch).
  The minimums track current coverage; raise them when adding tests, never lower them to make a build pass.
- **Mutation testing (PITest)**: opt-in and local via the `-Pmutation` property and the per-module
  `pitest` task (e.g., `gradle :domain:pitest -Pmutation`). Each module runs PIT against its own tests and
  writes its own report under `<module>/build/reports/pitest/index.html`: `domain` mutates `domain.*`,
  `api` mutates `api.*`, and `data` mutates `data.*`. The `application` cross-module run
  (`gradle :application:pitest -Pmutation`) additionally mutates `api.*`/`data.*` against the system and
  acceptance tests, as the Maven build did; it adds the `api`/`data` `classes/kotlin/main` directories as
  `additionalMutableCodePaths`. The generated `*MapperImpl` classes are excluded from mutation, mirroring the JaCoCo gate.
  Per-module `targetClasses` live in each module's `build.gradle.kts`; shared config is in the
  `de.seuhd.campuscoffee.pitest-conventions` convention plugin. Select the mutator group with
  `-Ppitest.mutators=DEFAULTS|STRONGER|ALL`.
- When adding a feature, also add tests; use surviving mutants to find missing assertions. The
  handwritten mapping logic in `PosEntityMapper` (house-number parsing), `ReviewDtoMapper` (expression
  mappings), and `HouseNumberConverter` contains real logic and is kept in scope for both tools.

### Docker

Build image:

```shell
docker build -t campus-coffee:latest .
```

Run with Docker Compose:

```shell
docker compose down && docker compose up
```

## Database

- **Database**: PostgreSQL 17.
- **Migrations**: Flyway (located in `data/src/main/resources/db/migration/`).
- **ORM**: JPA with Spring Data.
- **Connection**: Configured in `application/src/main/resources/application.yaml`.

Migration files follow Flyway naming convention (e.g., `V1__create_pos_table.sql`, `V2__create_users_table.sql`).

## Testing Strategy

- **Unit and Integration Tests**: In `domain/src/test/kotlin/` (e.g., `PosServiceTest`, `ReviewServiceTest`)
- **System Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/system/` (e.g., `PosSystemTests`, `UsersSystemTests`)
  - Use Testcontainers for PostgreSQL.
  - Use Spring's `RestTestClient` for API testing.
  - Extend `AbstractSystemTest` base class.
- **Acceptance Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/acceptance/`
  - Cucumber BDD tests with `.feature` files in `application/src/test/resources/de/seuhd/campuscoffee/tests/acceptance/`
  - Step definitions in `CucumberPosSteps.kt` and `CucumberReviewSteps.kt`
- **Architecture Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/architecture/`
  - ArchUnit tests enforce hexagonal architecture rules

### Test Naming

Test methods (those annotated with `@Test` or `@ParameterizedTest`) use Kotlin backtick names that
read as a sentence describing the behavior under test. The structure is the same throughout: active
voice, present tense, the subject under test first (a scenario for behavior tests, the function name
for focused unit tests), then the **outcome stated as the fact the test actually asserts** — the
explicit HTTP status for system tests (`409 Conflict`, `404 Not Found`), the exception type, or the
returned value. Avoid `should` and vague status nouns (`returns conflict`). Examples:

- ``fun `creating a POS with a duplicate name returns 409 Conflict`()`` (system test)
- ``fun `upsert throws DuplicationException for a duplicate POS name`()`` (data test)
- ``fun `findByName returns the matching POS and null when none matches`()`` (repository test)

ktlint's `function-naming` rule permits these for test-annotated functions. Non-test functions (setup
methods like `@BeforeEach`/`@AfterAll`, `@MethodSource` providers, Cucumber step definitions, and
private helpers) keep conventional camelCase names.

## Key Technologies

- **Spring Boot 4.0.6** (Spring Framework 7).
- **Kotlin** on JDK 25; nullability is expressed with Kotlin's nullable types.
- **MapStruct** for object mapping (DTOs <-> domain models <-> entities), run via kapt.
- **ktlint** for Kotlin formatting and linting (the official Kotlin style; `ktlintCheck` runs as part of `check`).
- **Bean Validation** (Jakarta Validation) for input validation (validation happens in the controllers based on the DTOs, before mapping them to domain models).
- **OpenAPI/Swagger** (SpringDoc) for API documentation.
- **Spring `@HttpExchange`** declarative HTTP client over `RestClient` (OpenStreetMap API integration).
- **Testcontainers** for system tests.
- **Cucumber** for BDD acceptance tests.
- **ArchUnit** for architecture testing.

## Important Patterns

### Error Handling

Domain exceptions in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/exceptions/`:
- `NotFoundException`: Entity not found.
- `DuplicationException`: Duplicate unique fields.
- `ValidationException`: Business rule violation.
- `MissingFieldException`: Required field missing.

Global exception handler: `api/src/main/kotlin/de/seuhd/campuscoffee/api/exceptions/GlobalExceptionHandler.kt`.
It extends `ResponseEntityExceptionHandler`, so the standard Spring MVC exceptions also map to their
proper status codes (an unmapped path returns 404, a wrong HTTP method 405) instead of a generic 500.

The REST API is JSON-only: `ApiPathConfig` removes the XML message converter, so a client's `Accept`
header cannot switch responses to XML (the OSM client parses XML with its own `XmlMapper`).

### MapStruct Configuration

MapStruct runs as a Kotlin annotation processor via kapt, applied through the `de.seuhd.campuscoffee.kotlin-kapt-conventions` convention plugin (`build-logic/`); the `api` and `data` modules declare `kapt(mapstruct-processor)`. The generated `*MapperImpl` classes are excluded from the coverage and mutation gates.

### Custom Sequence Generation

JPA entities use custom sequence generators defined in `data/src/main/kotlin/de/seuhd/campuscoffee/data/persistence/generators/` to allow resetting sequences when running in the `dev` profile.

### OpenAPI Customization

Custom OpenAPI annotations in `api/src/main/kotlin/de/seuhd/campuscoffee/api/openapi/`:
- `@CrudOperation` for common CRUD operations.
- `CrudOperationCustomizer` for customizing OpenAPI spec.
- Reduces repetitive annotations in controllers.

## Configuration

- Main config: `application/src/main/resources/application.yaml`.
- Dev profile activates on `spring.config.activate.on-profile: dev`.
- Custom properties:
  - `osm.api.base-url`: OpenStreetMap API endpoint.
  - `campus-coffee.approval.min-count`: Minimum number of approvals needed for reviews to be approved.

## REST API Endpoints

Base URL: `http://localhost:8080/api`.

### POS Endpoints

- `GET /pos` - Get all POS.
- `GET /pos/{id}` - Get POS by ID.
- `GET /pos/filter?name={name}` - Filter by name.
- `POST /pos` - Create POS.
- `POST /pos/import/osm/{nodeId}?campus_type={type}` - Import from OpenStreetMap.
- `PUT /pos/{id}` - Update POS.
- `DELETE /pos/{id}` - Delete POS.

### User Endpoints

- `GET /users` - Get all users.
- `GET /users/{id}` - Get user by ID.
- `GET /users/filter?login_name={name}` - Filter by login name.
- `POST /users` - Create user.
- `PUT /users/{id}` - Update user.
- `DELETE /users/{id}` - Delete user.

### Review Endpoints

- `GET /reviews` - Get all reviews.
- `GET /reviews/{id}` - Get review by ID.
- `GET /reviews/filter?pos_id={id}&approved={true/false}` - Filter reviews.
- `POST /reviews` - Create review.
- `PUT /reviews/{id}/approve` - Approve review (requires different user than author).

## Working with the Codebase

### Adding a New Entity

1. Create domain model in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/model/objects/`.
2. Create service interface in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/api/` (extend `CrudService<DOMAIN, ID>`).
3. Create data service interface in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/data/` (extend `CrudDataService<DOMAIN, ID>`).
4. Create service implementation in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/implementation/` (extend `CrudServiceImpl<DOMAIN, ID>`).
5. Create JPA entity in `data/src/main/kotlin/de/seuhd/campuscoffee/data/persistence/entities/`.
6. Create repository in `data/src/main/kotlin/de/seuhd/campuscoffee/data/persistence/repositories/` (extend `JpaRepository`).
7. Create entity mapper in `data/src/main/kotlin/de/seuhd/campuscoffee/data/mapper/` (extend `EntityMapper`).
8. Create data service implementation in `data/src/main/kotlin/de/seuhd/campuscoffee/data/implementations/` (extend `CrudDataServiceImpl<DOMAIN, ENTITY, RESPOSITORY, ID>`).
9. Create DTO in `api/src/main/kotlin/de/seuhd/campuscoffee/api/dtos/` (extend `Dto<ID>`).
10. Create DTO mapper in `api/src/main/kotlin/de/seuhd/campuscoffee/api/mapper/` (extend `DtoMapper<DOMAIN, DTO>`).
11. Create controller in `api/src/main/kotlin/de/seuhd/campuscoffee/api/controller/` (extend `CrudController<DTO, DOMAIN, ID>`). Map paths relative to the resource (e.g., `@RequestMapping("/widgets")`); the `/api` base is applied centrally by `ApiPathConfig`.
12. Create Flyway migration in `data/src/main/resources/db/migration/`.

### Constraint Violations

Database uniqueness constraints are automatically converted to `DuplicationException` via `ConstraintMapping` in `data/src/main/kotlin/de/seuhd/campuscoffee/data/constraints/`. Register custom constraint mappings there.
