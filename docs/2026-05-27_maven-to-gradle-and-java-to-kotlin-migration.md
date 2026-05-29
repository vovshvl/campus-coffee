# Migration plan: Maven → Gradle, Java 25, Spring Boot 4, then Kotlin

> **Historical document** (as of 2026-05-27). Superseded by the implemented project; see `CLAUDE.md`
> and `CHANGELOG.md` for the current state. Details below reflect the codebase at the time of writing.

## Context

CampusCoffee is a Spring Boot 3.5 multi-module app (`domain`, `api`, `data`, `application`,
plus a Maven-only `coverage` aggregator) built with Maven on Java 21, using a hexagonal
architecture enforced by ArchUnit. The goal is a full platform modernization:

1. Move the build from **Maven to Gradle (Kotlin DSL)**.
2. Bump the JDK from **Java 21 to Java 25** (current LTS).
3. Upgrade **Spring Boot 3.5.8 → 4.0.6** (latest stable; a major version jump).
4. Port the production code from **Java to Kotlin**, one module at a time.

These run as **four independently verifiable stages** — build tool, then JDK, then framework,
then language — each its own commit, so a regression isolates to one variable and reverts on its
own. The full test suite (JUnit 5, Mockito, RestAssured, Testcontainers, Cucumber, ArchUnit) and
the quality gates (JaCoCo 90% line / 80% branch aggregate gate; opt-in PITest mutation) stay
green at every stage.

**Feasibility (verified, May 2026):**
- **Gradle 9.5.1** is current stable (released 2026-05-14); the 9.x line supports JDK 25 and
  Kotlin 2.3.0+.
- **Spring Boot 4.0.6** (released 2026-04-23) baselines Java 17 and supports up to Java 26, so it
  runs on Java 25. Spring Boot 3.5.5+ is already JDK 25-ready (this repo is on 3.5.8), so the JDK
  bump can be validated on 3.5 before the framework upgrade.
- **Kotlin 2.3.0+** supports `jvmTarget = 25` directly (earlier Kotlin silently falls back to 24).
- **Spring Boot 4.0 ships declarative HTTP clients in `spring-web`**, so
  `spring-cloud-starter-openfeign` is no longer needed; the OSM `@FeignClient` migrates to a
  Spring `@HttpExchange` interface and Spring Cloud is dropped entirely.

Two hard constraints shape everything below:

- **Quality gate is non-decreasing.** The aggregate JaCoCo gate (`coverage/pom.xml`: 90% line,
  80% branch) must be preserved and never lowered to make a build pass. Converting Java records to
  Kotlin `data class`es introduces compiler-generated members (`equals`/`hashCode`/`toString`/
  `componentN`/`copy`) that JaCoCo counts, so extend the exclusion patterns rather than drop the
  thresholds.
- **Package names are a contract.** `de.seuhd.campuscoffee.{domain,api,data,application}` are
  hard-coded in `ArchitectureTests.java` layer rules and in the Cucumber glue path
  (`@SelectPackages`/`GLUE_PROPERTY_NAME`). Kotlin must compile into the same packages; do not
  relocate types.

### Decisions (confirmed with the user)

| Topic                         | Decision                                                                                                                                                                               |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Gradle DSL & structure        | Kotlin DSL (`build.gradle.kts`) + `gradle/libs.versions.toml` version catalog + `build-logic/` convention plugins                                                                      |
| Target JDK                    | **Java 25** (current LTS), bumped after the Gradle equivalence check                                                                                                                   |
| Target framework              | **Spring Boot 4.0.6** (latest stable; major upgrade), done while still in Java, before Kotlin                                                                                          |
| OSM client                    | Migrate `@FeignClient` → Spring `@HttpExchange`; drop Spring Cloud OpenFeign                                                                                                           |
| MapStruct under Kotlin        | **kapt** (full support for the abstract mappers with `@Mapping(expression=...)` and `@Autowired`)                                                                                      |
| Test code during Kotlin phase | Keep tests in **Java**; convert later as a separate pass                                                                                                                               |
| Cutover sequencing            | **Fully replace Maven first** (verify equivalent), delete poms, then JDK, framework, Kotlin                                                                                            |
| Module isolation              | Keep the existing compile-time submodule graph **and** test-time ArchUnit; **add** Kotlin `internal` for intra-module type hiding                                                      |
| Public surface                | Ports, domain model, DTOs, **DTO mappers** (decision), and `TestFixtures` stay `public`; impls/entities/repositories/controllers go `internal`; `explicitApi()` on the library modules |
| JPMS                          | Non-goal (Spring reflection + fat-jar/classpath launcher friction outweighs the runtime-encapsulation benefit)                                                                         |

> Provenance: working plan stored at
> `docs/2026-05-27_maven-to-gradle-and-java-to-kotlin-migration.md`.

## Module isolation and encapsulation

Three mechanisms operate at different granularities; **all are kept**:

- **Gradle subproject graph — compile-time, module granularity.** Already enforces the directional
  layer dependencies: `domain` cannot reference `api`/`data` because it does not declare them — a
  compile error today, inherited from the Maven submodules. This remains the primary compile-time
  enforcement of who-may-depend-on-whom.
- **ArchUnit — test-time, package granularity.** Keeps the layer-access rules in `ArchitectureTests`,
  expressing constraints the module graph cannot (package-level rules, and rules within a module).
  Unchanged.
- **Kotlin `internal` — compile-time, type granularity within a module.** The only genuinely new
  capability: it hides implementation types from modules that are *allowed* to depend on this one.
  The graph lets `application` depend on `api`; once it does, every `public` api type is visible.
  `internal` lets `api` expose only its ports/DTOs and hide controllers/impls. `internal` compiles
  to public bytecode (class names unmangled), so Spring DI, Hibernate, and Jackson reflection need
  no `opens`, and because the Kotlin compiler enforces it only against Kotlin sources, the Java
  tests are unaffected during Phase 4.

**Public surface** (`public`): the domain model, the api/data **ports**, the **DTOs**, the **DTO
mappers** (kept public by decision — `application` tests autowire them), and `TestFixtures`.
Everything else is **`internal`**: domain service impls, data service impls, JPA entities,
repositories (forced internal once entities are, since a public signature cannot expose an internal
type), and api controllers. Enable Kotlin **`explicitApi()`** on the `domain`/`api`/`data` library
modules so the public surface is declared deliberately.

**Dependency scoping (build hygiene, not layer enforcement).** With the `java-library` plugin,
declare a module's dependencies as `api(...)` when their types appear in that module's *public
signatures* (e.g., `api` re-exposes `domain` types in `PosController extends CrudController<PosDto,
Pos, Long>`, so `api(project(":domain"))`), and `implementation(...)` for dependencies used only
internally (Flyway, postgresql, jackson, springdoc) so they do not leak onto consumers' compile
classpaths or trigger their recompilation. Per-dependency decision; reduces accidental coupling and
speeds incremental builds — it does **not** enforce the hexagonal layers.

**JPMS is a deliberate non-goal.** `module-info.java` would add JVM-enforced runtime encapsulation
but requires `opens` for Spring/Hibernate/Jackson reflection (reopening most of what it closes),
does not fit Spring Boot's classpath-based `bootJar`/launcher, and complicates the test tooling. Its
only unique benefit over `internal` is runtime enforcement, which this stack forces open anyway.

---

## Phase 0 — Prerequisites and baseline

1. **Capture a green baseline.** Run `mvn -B verify` (Docker running for Testcontainers) and
   record: the aggregate coverage numbers from `coverage/target/site/jacoco-aggregate/index.html`,
   the produced fat jar `application/target/application-0.0.5.jar`, and the full test count. This
   is the reference the Gradle build must match.
2. **Note the version drift to reconcile.** Root `pom.xml` declares parent
   `spring-boot-starter-parent:3.5.8` but a property `spring.boot.version=3.5.7` used in the
   config-processor annotation path. Pin a **single** Spring Boot version in the catalog
   (3.5.8 for Phase 1, matching the parent BOM) and drop the duplicate. Phase 1 stays on **Java 21
   and Spring Boot 3.5.8** so the Gradle build is a clean equivalence check against the baseline.
3. **Inventory the inherited root config** that Gradle has no equivalent inheritance for and must
   move into a convention plugin: parent-level deps (`spring-boot-starter-web`, `lombok` provided,
   `jspecify` provided, `commons-lang3`, `spring-boot-starter-test` test,
   `spring-boot-configuration-processor`), the MapStruct/Lombok annotation-processor chain, the
   Surefire JVM args (`-XX:+EnableDynamicAgentLoading`, Mockito `-javaagent`, `-Xshare:off`), and
   `lombok.config` (`addLombokGeneratedAnnotation=true`, JSpecify null annotations).

---

## Phase 1 — Maven → Gradle (Java 21, Spring Boot 3.5.8; no language change)

Goal: a Gradle build that produces the same artifacts and enforces the same gates, code still Java
on the same JDK and framework. This is the safety net for everything after, so finish and verify
it before changing anything else.

### 1.1 Project skeleton

- **No Gradle wrapper.** Gradle is provisioned through `mise.toml` (`gradle = '9.5'`, major.minor
  like the existing `maven = '3.9'`), consistent with how java/maven are managed. Invoke `gradle`
  directly (CI via `jdx/mise-action`, interactively via the mise shim); in a non-interactive shell
  without the shim, `mise x gradle@9.5 -- gradle …`.
- `settings.gradle.kts`: `rootProject.name = "campus-coffee"` and
  `include("domain", "api", "data", "application", "coverage")`. Enable the default catalog.
- `gradle/libs.versions.toml`: capture every pinned version from the poms — Spring Boot 3.5.8,
  Spring Cloud 2025.0.0, JUnit 5.14.0, springdoc 2.8.14, MapStruct 1.6.3,
  lombok-mapstruct-binding 0.2.0, Cucumber 7.31.0, ArchUnit 1.4.1, WireMock 3.13.1, JaCoCo 0.8.13,
  PITest 1.19.1 + pitest-junit5 1.2.2. Group them with `[versions]`, `[libraries]`, `[bundles]`,
  `[plugins]`.

### 1.2 Convention plugins (`build-logic/`)

Included build `build-logic` (`pluginManagement { includeBuild("build-logic") }` in settings) with:

- **`campuscoffee.java-conventions`**: `java-library`, **Java 21 toolchain**, UTF-8, the Spring
  Boot BOM via `io.spring.dependency-management` (or `implementation(platform(...))`), Lombok
  (`compileOnly` + `annotationProcessor`, same for test), JSpecify, the **MapStruct annotation
  processor chain** in order (`mapstruct-processor`, `lombok`, `lombok-mapstruct-binding`,
  `spring-boot-configuration-processor`), and the Surefire-equivalent `test { jvmArgs(...) }` for
  the Mockito javaagent + `EnableDynamicAgentLoading` + `-Xshare:off`. Gradle's `annotationProcessor`
  ordering plus `lombok-mapstruct-binding` reproduces the Maven processor ordering.
- **`campuscoffee.jacoco-conventions`**: applies `jacoco`, binds report generation to `test`,
  registers each module's `jacoco.exec` for aggregation.
- **`campuscoffee.pitest-conventions`** (behind a `-Pmutation` property to stay opt-in): applies
  `info.solidsoft.pitest` with shared config (mutators via `-Dpitest.mutators`, the excluded
  `*MapperImpl`/`Application`/`LoadInitialData`/`domain.tests.*`, `targetTests=de.seuhd.campuscoffee.*`,
  the same `argLine`), leaving `targetClasses` to each module.

### 1.3 Per-module build files

- **`domain`**: java + jacoco conventions; deps `spring-boot-starter-validation`, `spring-tx`.
  PITest `targetClasses = de.seuhd.campuscoffee.domain.*`.
- **`api`**: deps `project(":domain")`, springdoc-openapi-starter-webmvc-ui, mapstruct,
  `spring-boot-starter-validation`. PITest `targetClasses = api.*`.
- **`data`**: deps `project(":domain")`, `spring-boot-starter-data-jpa`,
  `spring-cloud-starter-openfeign`, postgresql, flyway-core + flyway-database-postgresql (runtime),
  mapstruct, jackson-dataformat-xml; test deps for Testcontainers. PITest `targetClasses = data.*`.
  Flyway migrations stay under `src/main/resources/db/migration`.
- **`application`**: apply `org.springframework.boot` here (only `bootJar`); deps
  `project(":domain")`, `project(":api")`, `runtimeOnly(project(":data"))`, openfeign, actuator;
  full test stack (testcontainers, rest-assured with the commons-logging exclusion, assertj,
  junit-platform-suite, the four Cucumber artifacts, archunit, wiremock-standalone). PITest
  cross-module (`targetClasses = api.*, data.*`) — see 1.5. Set
  `tasks.bootJar { archiveFileName.set("application-<version>.jar") }` to match the Dockerfile.
- Library modules (`domain`/`api`/`data`) apply `io.spring.dependency-management` for the BOM but
  **not** the Spring Boot plugin, so they emit plain jars (equivalent to today's `skipIfEmpty`).
- Scope dependencies per *Module isolation and encapsulation*: `api(...)` only where a module
  re-exposes the dependency's types in its public signatures (e.g., `api(project(":domain"))`),
  `implementation(...)` for internal-only deps (Flyway, postgresql, jackson, springdoc). Behavior
  is identical to Maven's transitive `compile` either way; the difference is preventing accidental
  compile-time coupling, so it is fine to land this in Phase 1 and refine when types move.

### 1.4 Coverage aggregation (replaces the `coverage` Maven module)

- Convert `coverage` to a Gradle subproject applying built-in **`jacoco-report-aggregation`** (and
  optionally `test-report-aggregation`), depending on `domain`, `api`, `data`, `application`.
- Add a `JacocoCoverageVerification` task on the aggregated exec data with the **same gate**
  (BUNDLE: LINE ≥ 0.90, BRANCH ≥ 0.80) and **same exclusions** (`domain/tests/**`, `**/Application.*`,
  `**/LoadInitialData.*`, `**/*MapperImpl.*`), wired into `check` so `./gradlew check` fails on a
  breach (matching `mvn verify`).
- Confirm the aggregated number matches the Phase 0 baseline within rounding.

### 1.5 Mutation testing

- `info.solidsoft.pitest` per module behind `-Pmutation`. The Maven `crossModule=true` application
  run (mutating `api.*`/`data.*` against system + acceptance tests) maps to the plugin's
  `pitest.aggregator`/additional source-set config on `application`. Finickiest piece; since
  mutation is local-only, land per-module first and refine cross-module after. Keep the
  `*MapperImpl` exclusions and the `DEFAULTS|STRONGER|ALL` selector.

### 1.6 Toolchain, Docker, CI (still Java 21)

- **`mise.toml`**: keep `java = 'temurin-21'`; **remove the `"asdf:maven"` entry** once the poms
  are deleted (1.7) — the Gradle wrapper supplies Gradle. Keep `gcloud`/`python` as-is.
- **`Dockerfile`**: build stage runs `./gradlew :application:bootJar` (copy wrapper + `build-logic`
  + catalog first for layer caching); update the runtime `COPY` from
  `application/target/application-0.0.5.jar` to `application/build/libs/application-<version>.jar`.
  `compose.yaml` is unaffected (builds via the Dockerfile).
- **`.github/workflows/build.yml`**: replace `mvn -B verify` with `./gradlew build` (runs `check`
  incl. the coverage gate); add `gradle/actions/setup-gradle` for caching (mise still provides the
  JDK); update coverage artifact upload paths to `coverage/build/reports/jacoco/...` and
  `*/build/reports/...`.

### 1.7 Phase 1 verification (gate before Phase 2)

- `./gradlew clean build` green; same test count (system + acceptance + arch + unit).
- Aggregate coverage matches the baseline and the gate is enforced (briefly break a test to confirm
  the build fails, then restore).
- `./gradlew :application:bootJar` runs; `docker compose up` starts the app and Swagger UI responds
  at `/api/swagger-ui.html` (dev profile).
- **Delete all `pom.xml` files** and `mvnw`/`.mvn` if present; drop maven from `mise.toml`. Commit.

---

## Phase 2 — Java 21 → 25 (JDK bump only)

Smallest possible change, isolated so any JDK-25 incompatibility is unambiguous.

- Bump the **Java toolchain to 25** in `campuscoffee.java-conventions` (`languageVersion = 25`).
  Source/target compatibility stays Java 25.
- **`mise.toml`**: `java = 'temurin-25'`.
- **`Dockerfile`**: build stage to a JDK 25 image; runtime stage to `eclipse-temurin:25-jre`.
- **CI**: mise now provisions Temurin 25 (no workflow change beyond the mise tool version).
- **Dependency compatibility under JDK 25's stricter runtime** — verify and bump only what breaks:
  - **Lombok**: ensure the BOM-managed version supports JDK 25 (override in the catalog if the
    annotation processor errors on the new class-file version).
  - **Mockito / ByteBuddy**: the agent-based mocking must support JDK 25; the existing
    `-XX:+EnableDynamicAgentLoading` flag stays, and ByteBuddy experimental support may be needed
    for the newest class-file version. Bump Mockito if needed.
  - **PITest** (local-only) and any other bytecode-manipulating tool.
  - Spring Boot 3.5.8 itself already supports JDK 25, so no framework change here.
- **Verify**: `./gradlew clean check` green on JDK 25; gate holds; `bootJar` runs on JDK 25;
  `docker compose up` works. Commit.

---

## Phase 3 — Spring Boot 3.5.8 → 4.0.6 (major framework upgrade, still Java)

Done while the code is still Java (better refactoring tooling and the existing Java test suite as a
guard) and before Kotlin. Spring Boot 4.0 brings Spring Framework 7, restructured starters, and
deprecated-API removals, so treat it as a real migration.

- **Bump the catalog** to Spring Boot 4.0.6; switch the BOM accordingly. Bump **springdoc** to the
  release compatible with Spring Boot 4 / Spring Framework 7 (current 2.8.14 targets Spring 6 —
  verify and raise). Reconcile any **starter coordinate changes** in 4.0 across the modules
  (`web`, `validation`, `data-jpa`, `actuator`, `test`).
- **Drop Spring Cloud entirely**: remove `spring-cloud-starter-openfeign` from `data` and
  `application`, and remove the Spring Cloud BOM/version from the catalog.
- **Migrate the OSM client** (`OsmFeignClient` in `data`'s `client` package) to a Spring
  declarative HTTP interface: replace `@FeignClient` with an `@HttpExchange` interface (method
  still returns the raw XML `String`), register it via `HttpServiceProxyFactory` /
  `@ImportHttpServices` backed by a `RestClient` whose base URL binds `osm.api.base-url`. Re-create
  the behavior in `OsmFeignClientConfig` (base URL, timeouts, any error handling) as `RestClient`
  builder customizations. `OsmDataService`, the WireMock-based `OsmDataServiceTest`, and
  `OsmImportSystemTests` continue to validate the integration unchanged.
- **Sweep deprecated/removed APIs** surfaced by the 4.0 compile (Spring Framework 7 removals) and
  **config-property migrations** (run `spring-boot-properties-migrator` temporarily against
  `application.yaml`, including the `dev` profile and custom `osm.api.*` /
  `campus-coffee.approval.*` properties).
- **Verify**: full suite green (unit, data integration, system, Cucumber acceptance, ArchUnit —
  layer rules unaffected); `bootJar` runs; Swagger UI works; the OSM import path passes via its
  WireMock/system tests. Commit.

---

## Phase 4 — Java → Kotlin, module by module

Bottom-up order so no Kotlin module needs to read Lombok-generated members from a Java dependency:
**domain → api → data → application**. (`api` and `data` both depend only on `domain` and are
independent; their relative order is free.) Tests stay Java, validating each module's conversion.

### Cross-cutting Kotlin setup (added per module as it converts)

- Pin **Kotlin 2.3.0+** in the catalog (required for `jvmTarget = 25`) and set
  `compilerOptions { jvmTarget = JVM_25 }`.
- Plugins: `kotlin("jvm")`, `kotlin("plugin.spring")` (all-open for Spring proxies),
  `kotlin("kapt")` (MapStruct). The **`data`** module also needs `kotlin("plugin.jpa")` (no-arg +
  open for `@Entity`/`@Embeddable` and lazy proxies).
- **Lombok interop during a module's conversion window**: apply `kotlin("plugin.lombok")` pointed at
  the root `lombok.config` so a half-converted module compiles. Remove the Lombok plugin +
  dependency once a module is fully Kotlin.
- kapt replaces the Java `annotationProcessor` for MapStruct in Kotlin modules; keep
  `spring-boot-configuration-processor` via kapt where config classes exist.
- Drop JSpecify `@Nullable`/`@NonNull` as files convert — Kotlin's `T?`/`T` expresses nullability
  natively; the existing annotations are an accurate guide for which types are nullable.
- `@Slf4j` → `private val log = LoggerFactory.getLogger(<Class>::class.java)` (or a `companion
  object` logger). No new logging dependency.
- **Encapsulation** (see *Module isolation and encapsulation*): as each module converts, mark its
  impls/entities/repositories/controllers `internal`, keep ports/model/DTOs/DTO mappers/`TestFixtures`
  `public`, and enable `explicitApi()` on the library modules. The Java tests are unaffected because
  the Kotlin compiler enforces `internal` only against Kotlin sources.
- Coverage: extend the JaCoCo exclusions / use JaCoCo's Kotlin filtering for `data class` generated
  members; **add tests rather than lower thresholds** if conversion exposes a gap. Re-check the
  aggregate after each module.

### The builder bridge (because tests stay Java)

Java tests and Cucumber steps construct models/DTOs via Lombok builders — `Pos.builder()…build()`,
`User.builder()`, `Review.builder()`, `PosDto.builder()` — and `TestFixtures` (in `domain/src/main`)
and `ReviewDtoMapper.toDomain` use `Review.builder()`. Kotlin `data class`es have no Lombok builder,
so converting these classes breaks Java callers.

Approach: give each converted Kotlin model/DTO a **hand-written `builder()`** (a small companion
object returning a fluent builder) so Java call sites compile unchanged during the mixed phase.
Remove these bridges in the later test-conversion pass, switching Kotlin callers to named-argument
constructors and `copy()`.

### 4.1 `domain` (largest, pure logic, well covered)

- Records `Pos`/`User`/`Review` → Kotlin `data class` implementing `DomainModel<Long>`; move the
  record-constructor validation (`Pos` postal-code/house-number checks) into an `init` block; add
  the builder bridge. Enums (`PosType`, `CampusType`, `OsmAmenity`) → Kotlin `enum class`.
- Generic ports/impls (`CrudService`, `CrudServiceImpl`, `CrudDataService`, the `*Service`
  interfaces) → Kotlin generics with the same bounds (`<DOMAIN : DomainModel<ID>, ID>`).
  `CrudServiceImpl`'s `@RequiredArgsConstructor` + `domainClass` field → primary constructor.
  `PosServiceImpl` (OSM `when`-mapping, `@Transactional`) converts directly.
- `TestFixtures` (production code in `domain/src/main`) → Kotlin; keep its public API stable so
  every test module still compiles.
- Exceptions and `ApprovalConfiguration` → Kotlin.
- Verify: domain `PosServiceTest`/`ReviewServiceTest`/etc. (Java) pass against Kotlin domain; full
  `./gradlew check` green; aggregate coverage holds.

### 4.2 `api`

- DTOs (`PosDto`, `UserDto`, `ReviewDto`) → Kotlin `data class` implementing `Dto<Long>` with the
  builder bridge (Cucumber `PosDto.builder()` depends on it). Keep Jakarta Bean Validation
  annotations on DTO fields — they stay in the controller-layer validation path.
- Generic `CrudController`, `DtoMapper`, concrete controllers (`PosController` etc.) → Kotlin;
  `@RestController`/`@Controller`/`@RequestMapping` unchanged; constructor injection via primary
  constructor.
- MapStruct via kapt: simple mappers (`PosDtoMapper`, `UserDtoMapper`) → Kotlin
  `@Mapper(componentModel="spring")` interfaces. `ReviewDtoMapper` (abstract, `@Autowired` services,
  hand-written `toDomain`) → Kotlin `abstract class` with the same members.
- OpenAPI customizers (`CrudOperationCustomizer`, `@CrudOperation`) → Kotlin.
- Verify: `ReviewDtoMapperTest`, `CrudOperationCustomizerTest` (Java) pass; generated `*MapperImpl`
  classes still match the coverage/mutation exclusion globs.

### 4.3 `data`

- Needs `kotlin("plugin.jpa")` + `kotlin("plugin.spring")`. Base `Entity` (`@MappedSuperclass`,
  `@PrePersist`/`@PreUpdate`) and `PosEntity`/`UserEntity`/`ReviewEntity`/`AddressEntity` → Kotlin
  classes with `var` properties; the JPA plugin supplies the no-arg constructor and opens them,
  replacing `@NoArgsConstructor`/`@AllArgsConstructor`/`@Getter`/`@Setter`. Keep the column/constraint
  name constants as `companion object const val`s (referenced by `ConstraintMapping`). Keep
  `@Version` on `ReviewEntity`.
- `PosEntityMapper` (abstract, `@Autowired HouseNumberConverter`, `@Mapping(expression="java(...)")`
  + hand-written `mergeHouseNumber`/`toAddress`) → Kotlin abstract class via kapt. Re-express the
  `expression="java(...)"` mappings as MapStruct expressions or `@AfterMapping`/default methods in
  Kotlin; preserve `HouseNumberConverter` split/merge logic verbatim.
- `HouseNumberConverter` (+ `Parts` record) → Kotlin `@Component` with a nested data class.
- Repositories (`JpaRepository` + `ResettableSequenceRepository`), custom sequence generators
  (`@CustomSequence`), the **OSM `@HttpExchange` client interface** (now declarative, from Phase 3),
  data service impls (`CrudDataServiceImpl` 4-type-param generic with the
  `REPOSITORY extends JpaRepository<…> & ResettableSequenceRepository` intersection bound → Kotlin
  `where` clause) → Kotlin.
- Verify: data integration tests (`PosRepositoryIntegrationTest`, `OptimisticLockingIntegrationTest`,
  `ResettableSequenceIntegrationTest`, `PosEntityMapperRoundTripTest`, `HouseNumberConverterTest`,
  `OsmDataServiceTest`, …) all Java, pass against Kotlin data using real PostgreSQL Testcontainers.

### 4.4 `application`

- `Application` (`@SpringBootApplication` main) → Kotlin (`fun main(args)` +
  `runApplication<Application>(*args)`). `LoadInitialData` → Kotlin. Both remain in the
  coverage/mutation exclusion lists.
- Configuration/wiring classes → Kotlin. `data` is `runtimeOnly`, so nothing here references it at
  compile time.
- Verify: the **entire** suite — system tests (`AbstractSysTest` + RestAssured), Cucumber acceptance
  tests, and **ArchUnit** (`testArchitecture`) — passes. Kotlin runtime classes live in `kotlin.*`
  and do not affect the `de.seuhd.campuscoffee.*` layer rules.

---

## End-to-end verification

After each stage (1, 2, 3) and after each Phase 4 module:

1. `./gradlew clean check` — all unit/integration/system/acceptance/architecture tests green.
2. Aggregate coverage ≥ baseline and the gate enforced (`coverage` verification task in `check`).
3. `./gradlew :application:bootJar`, then run the jar (or `docker compose up`); hit `GET /api/pos`
   and Swagger UI at `/api/swagger-ui.html` under the `dev` profile (clears repos and loads initial
   data) — confirms Flyway migrations, JPA mapping, MapStruct mapping, and the OSM declarative HTTP
   client path end to end.
4. Optionally `./gradlew -Pmutation pitest` per module (local only).
5. CI: push and confirm the GitHub Actions Gradle build is green and uploads coverage artifacts.

## Risks and mitigations

- **Spring Boot 4.0 major upgrade** (Spring Framework 7 removals, starter restructuring,
  config-property changes, springdoc compatibility) → done in Java (not mid-Kotlin) with the full
  Java suite as a guard; use `spring-boot-properties-migrator`; bump springdoc to a Spring-7-ready
  release.
- **`@HttpExchange` migration of the OSM client** → validate with the WireMock-based
  `OsmDataServiceTest` and `OsmImportSystemTests` before moving on; re-create timeouts/error
  handling as `RestClient` customizations.
- **JDK 25 stricter runtime** → may require bumping Lombok and Mockito/ByteBuddy; the dynamic-agent
  load flag is already set.
- **Coverage dip from Kotlin `data class` synthetic members** → extend JaCoCo exclusions / Kotlin
  filtering; add tests, never lower the gate.
- **MapStruct expression mappings under kapt** (`PosEntityMapper`, `ReviewDtoMapper`) → highest-touch
  conversion; validate with the existing round-trip/mapper tests.
- **Mixed Java/Kotlin compilation within a module** → `kotlin("plugin.lombok")` during the window;
  convert a module's production files in one pass to minimize the mixed state.
- **PITest cross-module run** → land per-module first; refine the cross-module run after (local-only).
- **Builder bridges are temporary** → tracked for removal in the later test-to-Kotlin pass.

## Rollback

Each stage and each Phase 4 module is a separate commit on `main` (per project convention, commit
directly to the current branch). Stages 1–3 keep the code in Java, so a build/JDK/framework problem
reverts by reverting that stage's commit without touching the others. Phase 4 modules convert
bottom-up with the Java test suite green at each step, so a broken module reverts independently of
already-converted ones.
