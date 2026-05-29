# Plan: Test Suite Extension and Kotlin/Groovy Migration Safety Net

> **Historical document** (as of 2026-05-25). Superseded by the implemented project; see `CLAUDE.md`
> and `CHANGELOG.md` for the current state. Details below reflect the codebase at the time of writing.

## Context

CampusCoffee is a Spring Boot hexagonal app (modules: `domain`, `api`, `data`, `application`, `coverage`).
The **long-term goal is migrating to Kotlin (production) and Groovy (tests), one module at a time**. That
migration needs a regression safety net that does not depend on the internals being rewritten.

Today's coverage is uneven and leaves the safety net thin where it matters most:
- **Reviews have zero system/acceptance coverage.** `ReviewController` (full CRUD, `GET /reviews/filter`,
  `PUT /reviews/{id}/approve`), which holds the most complex business logic (approval quorum, the
  self-approval ban, one review per author), is only pinned by `domain` Mockito unit tests that
  evaporate when `domain` is mid-rewrite.
- **Error/exception paths** (`GlobalExceptionHandler`: 404/409/400) are barely asserted at the HTTP level.
- **OSM import** (`POST /pos/import/osm/{nodeId}`) is entirely untested.
- **`data` module has zero tests** and no test scaffolding.

**Decisions confirmed with the user:** new tests stay in the current **Java/JUnit5/Cucumber** stack
(build the net first, then it protects the Groovy rewrite); OSM import gets a **WireMock system test**;
the plan covers **all three phases**.

## Layering philosophy (why this ordering)

- **Migration-invariant safety net**: **system tests and acceptance (Cucumber)** in the `application`
  module. They touch only DTOs and HTTP status codes, never internal classes, so they stay valid no
  matter which module flips to Kotlin. These carry the most weight and are built **first**.
- **Module-local complements**: **unit and integration tests** in `domain`/`data`/`api`. Fast and
  bug-localizing, but bound to internal types, so they migrate together with their module. They are the
  speed and diagnosis layer, not the net.

Establish the HTTP-level net before touching internals, so later phases (and the migration) run against
a fixed contract. No production code changes were required for the net itself: `osm.api.base-url` and
`campus-coffee.approval.min-count` are both Spring properties overridable via `@DynamicPropertySource`,
and `CrudService.clear()` already exists for teardown.

---

## Phase 1: safety net (system and acceptance), done first

### 1.1 Extend `Requests<T>` (`application/.../tests/SystemTestUtils.java`)
Reuse the existing generic record; add what reviews and error paths need:
- `retrieveByFilter(Map<String,Object> params)` returning `List<T>` (two-param filter `pos_id` plus `approved`); keep the existing single-param/200 overload.
- `approve(Long id, Long userId)`: `PUT /{id}/approve?user_id=`, assert 200, return dto.
- raw-status variants for error assertions: `approveAndReturnStatusCode`, `updateAndReturnStatusCodes`, `updateWithPathIdAndReturnStatusCode`, `retrieveByIdStatusCode`, plus the existing `createAndReturnStatusCodes`/`deleteAndReturnStatusCodes`.
- add `static Requests<ReviewDto> reviewRequests`.

### 1.2 `AbstractSysTest` clears reviews in FK order (`application/.../tests/system/AbstractSysTest.java`)
Autowire `ReviewService`; in `beforeEach()` call `reviewService.clear()` **before** `posService.clear()`/`userService.clear()` (reviews reference pos and users); add `reviewService.clear()` to `afterEach()`.

### 1.3 New `ReviewSystemTests`
Seeds users and POS from `TestFixtures`. Covers: a new review starts unapproved; retrieve all/by id;
update; delete then 404; filter by approval status; approval below quorum stays false; reaching quorum
(three distinct non-authors) approves; self-approval rejected (400); duplicate review by the same author
rejected (400); approving a missing review returns 404.

### 1.4 New `ErrorPathSystemTests`
Covers every `GlobalExceptionHandler` mapping: duplicate POS name and duplicate user return 409; missing
id on get/update returns 404; a path id that differs from the body id returns 400; review text length and
null `posId`/`authorId` return 400; a blank required POS field returns 400.

### 1.5 Acceptance: reviews feature (Cucumber, Java)
`reviews.feature` plus `CucumberReviewSteps`. Cucumber glue is package-scoped, so the shared container,
`@DynamicPropertySource`, and `@CucumberContextConfiguration` were extracted out of `CucumberPosSteps`
into `CucumberSpringConfiguration`, which both step files share. Review steps use distinct step text and a
distinctly named `@DataTableType` to avoid collisions. Scenarios mirror the high-value system tests.

### 1.6 OSM import: WireMock system test
`OsmImportSystemTests` starts WireMock in a static initializer (same lifecycle as the Postgres container,
because Feign resolves `${osm.api.base-url}` at context build, so the port must be known before the
context starts) and points `osm.api.base-url` at the stub. Cases: valid XML creates a POS (verifying the
amenity-to-PosType mapping and parsed postcode), a 404, and a missing tag. The exhaustive XML-parsing
matrix lives in the Phase 3 unit test.

---

## Phase 2: integration tests (`data` module)

Add Testcontainers scaffolding to `data/pom.xml` (`spring-boot-starter-test` is inherited from the parent).
The data integration tests use `@SpringBootTest(webEnvironment = NONE)` with a test-only
`DataTestApplication` and a shared static Postgres container, because the data layer needs its custom
repository base class (`resetSequence`), `ConstraintRetriever`, mappers, and Flyway wired together.
Files under `data/src/test/java/de/seuhd/campuscoffee/data/integration/`:
- `PosRepositoryIntegrationTest`: `findByName` hit and miss; a duplicate name violates the unique constraint.
- `ReviewRepositoryIntegrationTest`: `findAllByPosAndApproved` (both partitions) and `findAllByPosAndAuthor`.
- `CrudDataServiceDuplicationTest`: a duplicate unique field becomes a `DuplicationException`; a non-unique
  integrity violation (a CHECK constraint) is re-thrown rather than mapped.
- `ResettableSequenceIntegrationTest`: reset, then the next inserts get ids 1 and 2.
- `PosEntityMapperRoundTripTest`: persist a POS with a house number suffix and read it back from the database.

---

## Phase 3: unit tests (fast, module-local)

**`data`**:
- `mapper/HouseNumberConverterTest`: split and merge over `21a`, `100`, `5`, and the `21-a` suffix edge,
  plus the null/empty and no-digit inputs that a validated `Pos` never reaches.
- `implementations/OsmDataServiceTest`: drive `OsmDataServiceImpl` with a mocked `OsmFeignClient`:
  required-tag matrix, amenity resolution, the name fallback (`name:en`, then `name:de`, then `name`),
  the default description, and malformed XML (missing node, missing id, single non-array tag).

**`domain`**:
- `model/PosTest`: `validatePostalCode` boundaries (1067/99998 valid, 1066/99999/0 rejected) and the
  `validateHouseNumber` pattern (accept and reject cases). The planned `PosValidationTest` was folded
  in here to keep `Pos` validation in one class.
- `implementations/PosTypeMappingTest`: the OSM amenity to `PosType` mapping for every amenity, and the
  unparsable-postcode path.

**`api`**:
- `mapper/ReviewDtoMapperTest`: `toDomain` always sets `approved=false`/`approvalCount=0` regardless of
  input; `fromDomain` projects `posId`/`authorId` from the nested objects.
- `openapi/CrudOperationCustomizerTest`: the OpenAPI customizer over real controller methods (array,
  object-reference, and no-content success schemas, the error schema, and the external-resource path).

---

## Mapper refactoring (done)

To make the MapStruct-generated `*MapperImpl` exclusion sound, the mapping logic that carries real
behavior was moved into hand-written, independently testable units:
- `HouseNumberConverter` (data) holds the house number split and merge logic, unit-tested directly by
  `HouseNumberConverterTest` (including the empty and no-digit inputs). `PosEntityMapper` delegates to it.
- `ReviewDtoMapper.toDomain` is now a hand-written method that resolves the POS and author by id and
  resets the approval state on creation; it is unit-tested directly. `fromDomain` is a declarative
  projection that MapStruct generates.

After this, the generated `*MapperImpl` classes hold only field copying and null guards and are excluded
from the gate; the hand-written mapper classes stay in scope so future hand-written mappings are gated.
The OpenAPI `CrudOperationCustomizer` is hand-written and is unit-tested rather than excluded.

## Coverage gate

The gate lives in `coverage/pom.xml` (`check-aggregate`); policy is raise-never-lower. It excludes test
data (`TestFixtures`), startup wiring (`Application`, `LoadInitialData`), and the generated `*MapperImpl`
classes. Starting at LINE 0.62 / BRANCH 0.36, it was raised through 0.83/0.50 and 0.85/0.57 and is pinned
at **LINE 0.90 / BRANCH 0.80** (gated coverage measured 0.937 line / 0.818 branch).

## Mutation-testing round (done)

### Infrastructure defect: module-local tests were not participating in mutation
The original setup only mutated `domain.*` (domain run) and `api.*`/`data.*` (application run, crossModule,
against the system/acceptance tests). The api and data modules ran no mutation of their own, so their
unit and integration tests (`OsmDataServiceTest`, `HouseNumberConverterTest`, `ReviewDtoMapperTest`,
`CrudOperationCustomizerTest`, `CrudDataServiceDuplicationTest`, ...) never participated; their classes
showed as `NO_COVERAGE`/`SURVIVED` even though they were well tested.

Fix: **run PIT per module** (the standard multi-module setup). `domain`, `api`, and `data` each mutate
their own package against their own tests, discovered natively in each module's `target/test-classes`
(no copying). `application` keeps its `crossModule` run over `api.*`/`data.*` against the system and
acceptance tests (crossModule mutates the api/data classes that arrive as dependency jars; the tests are
application's own, already on its classpath, so again no copying). The generated `*MapperImpl` classes are
excluded from mutation (`de.seuhd.campuscoffee.*.*MapperImpl`), matching the JaCoCo gate, since they hold
only generated field copying.

**The reports are not merged.** The api/data classes are covered by two reports (the module's own
unit/integration tests and application's system tests), and PIT's `report-aggregate` *overwrites*
duplicate mutations (last module in reactor order wins) rather than unioning them, so a merged report
would silently discard one side (verified: `api.openapi` shows 12 of 31 killed in the api report, but the
merge showed the application run's 1/31). The coverage module therefore aggregates only JaCoCo line/branch
coverage, not mutation. To read mutation results: a module's own `target/pit-reports` shows what its
tests catch; `application/target/pit-reports` shows what the system tests catch (e.g., the controllers,
which have no api-local tests, are killed only there). An earlier attempt to get one unified report by
copying the api/data test classes onto the application classpath worked but was discarded as too hacky;
a single correct *and complete* number is only possible that way, because `report-aggregate` cannot union.

### Tests no longer hard-code production values
Boundary inputs are derived from the production source of truth instead of duplicated literals:
`Pos.MIN/MAX_POSTAL_CODE` (made package-private), `ReviewDto.MIN/MAX_REVIEW_LENGTH` (extracted, used by
both the `@Size` constraint and the tests), and `OsmDataServiceImpl.DEFAULT_DESCRIPTION`. `ReviewServiceTest`
derives its approval counts from `approvalConfiguration.minCount()`.

### Assertions strengthened (behavioral, never on implementation details)
- `OsmAmenityTest` (new, domain): pins `OsmAmenity.fromOsmValue` string->enum resolution, which lives in
  domain but was only exercised from the data layer.
- `CrudDataServiceImplTest` (new, data): unit-tests `isConstraintViolation` (made package-private) with
  crafted exceptions, driving the message-match and root-cause-match branches separately. This is the
  named "constraint matching" prime suspect, which no black-box test can distinguish.
- `PosTypeMappingTest.importMapsAllOsmNodeFieldsToPos` (new): asserts the full OSM->Pos field mapping
  (`PosServiceImpl.convertOsmNodeToPos`); the existing test only asserted `type`, so the name/description/
  street/city/campus mapping survived under `ALL` (NakedReceiver mutants).
- `UserServiceTest` (new, domain): `UserServiceImpl` had no domain unit test; pins the login-name and id
  lookups delegating through the data port.
- `ErrorPathSystemTests.filterByNonexistentValueReturnsNotFound`: covers the filter-miss 404 path.

Left unasserted on purpose (would require testing implementation details or text formatting, against the
project's testing rules): exception-message content (`JpaUtils.extractColumnName`,
`ConstraintMapping.extractValue`), the OpenAPI summary/description strings the `CrudOperationCustomizer`
produces (its test asserts response *schema structure*, not the doc text), and
`ValidationException.formatViolations` (message formatting on a currently-unused constructor).

### Result and remaining survivors
Per-module DEFAULTS reports: `domain` 30/40 killed, `data` 43/97, `api` 13/79 (low because its unit tests
do not cover the controllers, which are killed in the `application` report), `application` (system tests)
128/176. Under `ALL`, the remaining survivors were checked individually rather than assumed equivalent.
They fall into three kinds. Equivalent or unreachable mutants: the `ReviewServiceImpl.approve` logging
block, `Objects.requireNonNull` guards on `@NonNull` values, the unreachable data-layer update-missing
path the service pre-checks, and an exhaustive-switch default. Message- and text-formatting-only
differences we do not assert. And 12 `Pos` `RUN_ERROR`s under `ALL`: PIT's minion dies (`PitError`/
`EOFException`) while running the `Pos` validation-method mutants. The cause is internal to PIT's
execution of those mutants under the aggressive set, not a test gap (`PosTest` covers the validation);
it was not pinned down. The three real gaps `ALL` surfaced were fixed (OSM->Pos mapping and
`UserServiceImpl`); `formatViolations` was assessed and rejected as formatting.

Run the default set first (`mvn -P mutation clean test`) to drive strengthening; run the complete set
(`-Dpitest.mutators=ALL`) as the final check. Caveat: PIT's history file is in the temp dir and survives
`clean`; delete `*_pitest_history.bin` when the test set changes so stale results are not reused.

`mvn clean verify` is green after this round: all modules build, all tests pass, and the LINE 0.90 /
BRANCH 0.80 gate still holds.

## Production bug found and fixed

The new system tests surfaced that `ReviewServiceImpl.upsert` ran the one-review-per-author check on
every upsert, so `PUT /api/reviews/{id}` always returned 400. The check is now scoped to creation
(`id == null`), covered by `ReviewServiceTest.updatingExistingReviewSkipsDuplicateCheck` (domain) and
`ReviewSystemTests.updateReviewChangesText` (system). Still open and flagged: an update resets
`approvalCount`/`approved` because `ReviewDtoMapper.toDomain` resets them to a new review's values
(unapproved, zero count).

## ArchUnit impact: none required

`ArchitectureTests` needs no changes: the `coverage` module has no Java classes; new application-module
tests live under `de.seuhd.campuscoffee.tests..` (already the application layer); module-local tests run
in their own module's test source set and stay within their own layer plus `domain`. Constraint to keep
it clean: a module-local test must not import a sibling adapter layer.

## Risks

- **Single-threaded PITest and shared container:** keep PIT effectively single-threaded; do not enable
  JUnit parallel execution for the system suite (it shares one Postgres container).
- **System-test runtime growth:** reuse the single shared container and single Spring context; group the
  context-forking tests (OSM, lowered quorum) into as few classes as possible.
- **WireMock and Feign timing:** the WireMock port must exist before context creation (static init).

## Verification

- `mvn clean install` (Docker running for Testcontainers): all modules build, new tests green.
- `mvn verify`: the aggregate JaCoCo report builds and the pinned gate passes.
- CI (`.github/workflows/build.yml` runs `mvn -B verify`) stays green.
