# Phase 4 final pass: models, entities, MapStruct, and the test suite to Kotlin

> **Historical document** (as of 2026-05-27). Superseded by the implemented project; see `CLAUDE.md`
> and `CHANGELOG.md` for the current state. Details below reflect the codebase at the time of writing.

## Where we are

The Java → Kotlin migration converted the non-model production code of all four modules,
bridge-free, using a "models last" strategy (commits `a1a18c9`, `e0608cc`, `5a5bb1b`, `2521b78`,
`9ed49b8`, `c2921f9`). Kotlin reads the still-Java records/entities via their accessors and
Lombok-generated builders during the mixed-source window (the `kotlin("plugin.lombok")` interop
plugin in `campuscoffee.kotlin-conventions`).

What remains is the deliberately-deferred cluster: the record-based **models**, the JPA
**entities**, the **MapStruct mappers**, a little remaining glue, and — because converting the
records breaks every Java caller's accessor/builder usage — the **entire Java test suite**.

## Goal and hard constraints

- Finish Phase 4: no `.java` production or test sources remain; remove the `kotlin-lombok` interop
  plugin and the Lombok/JSpecify/Java-annotation-processor machinery once nothing Java is left.
- **Behavior unchanged.** The full suite (unit, data integration on Testcontainers PostgreSQL,
  system, acceptance, ArchUnit) stays green at every committed step.
- **Coverage gate is non-decreasing** (90% line / 80% branch; currently ~96.9 / ~88.2). Handle
  Kotlin synthetic members at the coverage-config layer (exclusions/filters), never by contorting
  source and never by lowering the gate.
- **Package names are a contract**: `de.seuhd.campuscoffee.{domain,api,data,application}` are
  hard-coded in `ArchitectureTests` layer rules and the Cucumber glue path. Kotlin must compile into
  the same packages; the test conversion must not relocate types.

## Inventory of the remaining Java

**Models / value records**
- domain: `Pos`, `User`, `Review`, `OsmNode` (records, `@Builder`/`toBuilder`), `DomainModel`,
  `Identifiable` (interfaces), `ApprovalConfiguration` (record), `TestFixtures` (test data in
  `src/main`, builds models, `createX(service)` factories).
- api: `PosDto`, `UserDto`, `ReviewDto` (DTOs — fluent accessors + `@Builder`; **confirm exact
  shape**), `ErrorResponse` (record, `@Builder`, `@JsonInclude`).
- data: `ConstraintMapping` (record), `OsmResponse` (`@Data @Builder` + Jackson), entity classes.

**JPA entities** (data): `Entity` (`@MappedSuperclass`), `PosEntity`, `UserEntity`, `ReviewEntity`
(`@Version`), `AddressEntity` (`@Embeddable`).

**MapStruct mappers** — generated `*MapperImpl` stays coverage/mutation-excluded:
- api: `PosDtoMapper`, `UserDtoMapper` (`@Mapper` interfaces), `ReviewDtoMapper` (abstract class,
  `@Autowired` services, `@Mapping`, hand-written `toDomain` with `Review.builder()`).
- data: `EntityMapper` (interface), `PosEntityMapper` (abstract, `@Autowired HouseNumberConverter`,
  `@Mapping(expression="java(...)")`, hand-written `mergeHouseNumber`/`toAddress`),
  `UserEntityMapper`, `ReviewEntityMapper`, `HouseNumberConverter` (+ `Parts`).

**Remaining glue**
- api `openapi`: `CrudOperation` (annotation), `CrudOperationCustomizer`, `CrudResponseSpecification`,
  `OpenApiConfig`, `Operation`/`Resource` (enums), `Parameters`.
- api `GlobalExceptionHandler` (builds `ErrorResponse`).
- data: `OsmResponseDeserializer`, `CustomSequence` (annotation), `CustomSequenceGenerator`
  (Hibernate SPI), `ResettableSequenceRepositoryImpl` (extends `SimpleJpaRepository`), `JpaUtils`.

**Test suite** (all Java): ~7 domain, 2 api, ~12 data, ~8 application (system + Cucumber acceptance +
ArchUnit), plus `SystemTestUtils`, `AbstractSysTest`, `AbstractDataIntegrationTest`,
`CucumberSpringConfiguration`, `DataTestApplication`.

## The core challenge

Converting `Pos`/`User`/`Review`/`OsmNode` and the DTOs to Kotlin `data class`es changes
record-style accessors (`pos.name()`) to property getters (`getName()`) and removes the Lombok
builders (`Pos.builder()`). These types are used **cross-module by everything**: the mappers,
`TestFixtures`, `GlobalExceptionHandler`, the already-converted `OsmDataServiceImpl`, and the whole
Java test suite. So the model conversion and all its callers form one coupled change.

## Staging strategy (decided: pure lockstep, no bridges)

The coupled change — models + DTOs + entities + mappers + `TestFixtures` + the entire test suite — is
converted in one coordinated changeset, with **no bridges**. The records become Kotlin data classes
and every caller (mappers, fixtures, already-converted Kotlin code, and all tests) moves to property
access / constructors / `copy()` in the same step. This keeps the intermediate state clean — no
temporary accessor/builder boilerplate to add and later remove — at the cost of a single large
changeset that only compiles once the whole cluster is converted, and a regression that is harder to
isolate from test-rewrite noise (the bridge alternative was rejected for its boilerplate).

To keep that changeset as small and verifiable as possible, first land the model-*independent* glue
(F1), then the coupled conversion (F2), then strip the now-unused Lombok machinery (F3).

## Stages

### F1 — model-independent glue (no build-plugin change)
Convert the glue that does **not** touch record accessors/builders and needs neither kapt nor
kotlin-jpa: the `openapi` package (annotation, enums, customizer, config), the
`DomainModel`/`Identifiable` interfaces, and `JpaUtils`. The models stay Java, so the Java test suite
still compiles and passes. Verify; commit. (Shrinks the F2 changeset.)

### F2 — the coupled lockstep conversion (one changeset)
- Build: add `kotlin("kapt")` to `api`/`data` (switch MapStruct from `annotationProcessor` to `kapt`)
  and `kotlin("plugin.jpa")` to `data`.
- Models: `Pos`/`User`/`Review`/`OsmNode` + `ApprovalConfiguration` → data classes (init-block
  validation for `Pos`); DTOs + `ErrorResponse` → data classes; `ConstraintMapping`,
  `OsmResponse`/`OsmResponseDeserializer` → Kotlin.
- Entities → Kotlin (`var`, `kotlin-jpa` no-arg), keeping the column/constraint `const val`s and
  `@Version`; `CustomSequence`/`CustomSequenceGenerator` and `ResettableSequenceRepositoryImpl` → Kotlin.
- Mappers → Kotlin via kapt (`*DtoMapper`, `*EntityMapper`, `EntityMapper`, `HouseNumberConverter`);
  re-home `ReviewDtoMapper.toDomain` and `PosEntityMapper`'s house-number logic.
  `@Mapping(expression="java(...)")` may stay (MapStruct emits Java `*MapperImpl`).
- `TestFixtures` and `GlobalExceptionHandler` → Kotlin; update already-Kotlin callers
  (`OsmDataServiceImpl` `OsmNode.builder()` → constructor/`copy`).
- Convert the **entire test suite** to Kotlin in the same changeset (domain, api, data, application
  incl. Cucumber steps/runner/config, ArchUnit, `SystemTestUtils`/`AbstractSysTest`), using property
  access / constructors / `copy()`; keep packages unchanged (ArchUnit + Cucumber glue contract).
- Verify the full suite green; commit. Build subsets up locally — leaf models → DTOs → entities →
  mappers → fixtures → tests — before the single commit.

### F3 — strip Lombok machinery; build cleanup
No Java sources remain, so remove `kotlin("plugin.lombok")`, Lombok, `lombok-mapstruct-binding`,
JSpecify, and the Java `-parameters`/annotation-processor config; rely on `kapt` for MapStruct and
`javaParameters` for Spring binding. Simplify the convention plugins. Verify full suite + gate +
`bootJar`/`docker compose up`; commit.

## Hard technical items (call out and verify)

- **kapt + MapStruct**: MapStruct generates Java `*MapperImpl` from Kotlin mappers; the generated
  classes stay in the `**/*MapperImpl.*` coverage/mutation exclusion. Validate with
  `ReviewDtoMapperTest`, `PosEntityMapperRoundTripTest`, `HouseNumberConverterTest`.
- **kotlin-jpa entity annotations**: JPA annotations on Kotlin `var` properties may need `@field:`
  use-site targets (`@Id`, `@GeneratedValue`, `@CustomSequence`, `@Column`, `@Enumerated`,
  `@Embedded`, `@Version`). The no-arg plugin supplies the JPA constructor. Verify with the data
  integration tests against real PostgreSQL.
- **`AddressEntity` equality**: `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with **no**
  included fields makes all instances equal — almost certainly a vestigial no-op. Investigate what
  (if anything) relies on it (`PosEntityMapperRoundTripTest`) and decide: replicate the no-op or
  treat it as a latent bug. Do not silently change behavior.
- **Optimistic locking**: keep `@Version` on `ReviewEntity`; `OptimisticLockingIntegrationTest` and
  `CrudDataServiceOptimisticLockTest` are the guards.
- **Custom sequence generator**: `CustomSequenceGenerator : SequenceStyleGenerator()` and the
  `@IdGeneratorType(CustomSequenceGenerator::class)` annotation; `ResettableSequenceIntegrationTest`
  guards sequence naming/reset.
- **Cucumber + ArchUnit**: package contract unchanged; re-run `testArchitecture` and the acceptance
  suite after the application-test conversion.
- **Coverage**: data-class `equals`/`hashCode`/`toString`/`componentN`/`copy` are synthetic; rely on
  JaCoCo's Kotlin filtering and, if needed, exclusion config — not source changes — and add tests for
  any genuine logic gap (e.g., `Pos` validation).

## Risks and mitigations

- **MapStruct expression mappings under kapt** (`PosEntityMapper`, `ReviewDtoMapper`) — highest-touch;
  the round-trip/mapper tests (converted in the same changeset) are the guard.
- **kotlin-jpa annotation placement** — the most likely source of an integration-test failure;
  surface it early by compiling and running the `data` module first within the F2 work.
- **Large F2 changeset (lockstep)** — mitigated by F1 shrinking the cluster and by building up subsets
  locally (leaf models → DTOs → entities → mappers → fixtures → tests), running each module's tests as
  it converts, before the single commit.

## End-to-end verification (each committed step)

1. `gradle build` — all unit/integration/system/acceptance/architecture tests green.
2. Aggregate coverage ≥ gate and ≥ the running baseline; gate enforced in `check`.
3. `gradle :application:bootJar`, run it (or `docker compose up`); hit `GET /api/pos` and Swagger UI
   at `/api/swagger-ui.html` under `dev` (Flyway, JPA, MapStruct, and the OSM client end to end).
