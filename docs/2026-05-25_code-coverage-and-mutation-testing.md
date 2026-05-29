# Plan: Code Coverage (JaCoCo) + Mutation Score (PITest) including integration & system tests

> **Historical document** (as of 2026-05-25). Superseded by the implemented project; see `CLAUDE.md`
> and `CHANGELOG.md` for the current state. Details below reflect the codebase at the time of writing.

## Context

CampusCoffee is a 4-module Maven project (`domain`, `api`, `data`, `application`) with a
hexagonal architecture. Today there is **no coverage or mutation tooling** at all. We want
to (1) measure line/branch **code coverage** and (2) measure **mutation score**.

The hard part is the test topology:

- `domain` has fast Mockito unit tests; `api` has one DTO test.
- **`data` has *zero* tests**: its production code is tested only indirectly.
- The **system tests** (Testcontainers + RestAssured, `*SystemTests`) and **acceptance tests**
  (Cucumber) live in the **`application`** module but run production code in
  `domain`/`api`/`data` through the full Spring context.

So coverage/mutation of `domain`/`api`/`data` is largely produced by tests that live in a
*different* module. A naive per-module JaCoCo setup would report `data` at ~0% and miss the
integration/system contribution everywhere. The good news (confirmed in
`application/src/test/.../system/AbstractSysTest.java`): system tests use
`webEnvironment = RANDOM_PORT`, i.e., an **embedded server in the same JVM as the test**.
A JaCoCo agent attached to the test JVM therefore already records coverage of
`domain`/`api`/`data`; we only need to **aggregate** the exec data back onto the right classes.

### Primary goal
This tooling exists to **improve and expand the test suite going forward** (this is a teaching
repo). It is not a one-off measurement: coverage and mutation reports are the work backlog.
Uncovered lines and surviving mutants are the concrete "write a test for this next" list. The
coverage gate is a JaCoCo `check` rule that fails the build when measured coverage is **below a
fixed configured minimum**. It compares only against that static number and keeps no history, so
it does not by itself detect regressions; you raise the configured minimum manually as coverage
improves (it acts as a maintained high-water mark). Design choices below favor low friction,
discoverable per-module reports, and a starting minimum set to current coverage rather than a high
bar that blocks contributions on day one.

### Decisions captured from the user
- **Coverage gate:** strict line + branch quality gate that **fails the build**, enforced **in CI**.
- **Mutation testing:** **opt-in Maven profile only**, kept **local** (not in CI). Each module
  mutates its own classes against its own tests (`domain.*`, `api.*`, `data.*`), and `application`
  additionally mutates `api.*`/`data.*` via `crossModule` against the system and acceptance tests.
  The per-module reports are not merged (see C2). Each surviving mutant re-runs the test suite, and
  the data and system tests use a PostgreSQL database in a container managed by Testcontainers, so a
  full run is slow.

### Verified latest versions (Maven Central `latestVersion`, May 2026)
- `org.jacoco:jacoco-maven-plugin` -> **0.8.13** (GitHub tags hint at 0.8.14; at implementation use the newest version that resolves from Central).
- `org.pitest:pitest-maven` -> **1.19.1**
- `org.pitest:pitest-junit5-plugin` -> **1.2.2**

All support Java 21. Pin these as properties so they are easy to bump.

---

## Part A: Code coverage with JaCoCo (incl. integration & system tests)

### A1. Critical prerequisite: fix the hardcoded surefire `argLine`
The root `pom.xml` hardcodes the surefire `<argLine>`. JaCoCo's `prepare-agent` injects its
agent via the `argLine` *property*; a hardcoded `<argLine>` silently drops it, so **no coverage
would be collected**. Change the surefire config to use late property substitution by
**prepending `@{argLine}`**:

```xml
<argLine>
    @{argLine}
    -XX:+EnableDynamicAgentLoading
    -javaagent:${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar
    -Xshare:off
</argLine>
```

Because `prepare-agent` (added below) runs in every module via `pluginManagement`, the
`argLine` property is always defined before surefire runs.

### A2. Add JaCoCo property + agent/report to every module (root `pom.xml`)
- Add property `<jacoco.version>0.8.13</jacoco.version>` near the other plugin versions.
- Add `jacoco-maven-plugin` to the root `<build><plugins>` so it applies to all modules, with
  two executions:
  - `prepare-agent` (default phase `initialize`): sets the `argLine` property used in A1.
  - `report` (phase `test`): writes a per-module HTML/XML report to `target/site/jacoco/`.

This gives each module its own report (useful for `domain`/`api`); `data`/`application` reports
will be sparse on their own. The aggregate (A4) is the real deliverable.

### A3. Make Lombok-generated code invisible to coverage
Append to `lombok.config` (currently lacks it):

```
lombok.addLombokGeneratedAnnotation = true
```

JaCoCo 0.8.x automatically ignores methods annotated `@lombok.Generated`, so getters/setters/
builders/`equals`/`hashCode` won't drag coverage down or distort the gate.

### A4. New dedicated aggregator module: `coverage`
Create a new module `coverage/` and add it **last** in the parent `<modules>` list so it builds
after every other module's exec data exists. It contains **no production code**; its job is the
combined report **and** the cross-module gate.

`coverage/pom.xml`:
- Parent = `de.seuhd.campuscoffee:parent`, `artifactId` = `coverage`.
- compile-scope dependencies on `domain`, `api`, `data`, `application` (so `report-aggregate` can
  see every module's classes, sources, and exec data; it ignores test-scope dependencies).
  Declaring them as dependencies also makes Maven build this module after the others.
- JaCoCo executions:
  - **`report-aggregate`** (phase `verify`) -> single combined report at
    `coverage/target/site/jacoco-aggregate/` (HTML + XML). `report-aggregate` auto-collects
    `target/jacoco.exec` from all depended modules and attributes the system/acceptance-test
    coverage back onto `domain`/`api`/`data` classes. **This is the "coverage including
    integration and system tests" deliverable.**
  - **`merge`** (phase `verify`, before check) -> merge every module's
    `../*/target/jacoco.exec` into `coverage/target/jacoco-aggregate.exec` (single exec for the gate).
  - **`check`** (phase `verify`) -> the strict gate (Part B).

### A5. Enabling `jacoco:check` to see cross-module classes
`jacoco:check` only analyzes the *current* module's `target/classes` (confirmed against the
check-mojo docs), and there is no native aggregate-check goal. To gate the *whole* codebase
from the `coverage` module, copy every module's compiled classes into this module before `check`:

- Use `maven-resources-plugin:copy-resources` (phase `process-test-classes`) to copy
  `../<module>/target/classes` for domain, api, data, and application into
  `${project.build.outputDirectory}`.
- `check` then analyzes those copied classes against the merged exec from A4.

(We read each module's `target/classes` directly rather than unpacking the built jars: the
application module's Spring Boot jar relocates its classes under `BOOT-INF/classes`, where
`jacoco:check` would not find them. This keeps all gate logic in one place and avoids
build-ordering problems.)

---

## Part B: Strict coverage quality gate (enforced in CI)

### B1. The `check` rule (in `coverage/pom.xml`)
A `BUNDLE`-level rule over the copied classes and merged exec, `haltOnFailure=true`:

```xml
<rules>
  <rule>
    <element>BUNDLE</element>
    <limits>
      <limit><counter>LINE</counter>  <value>COVEREDRATIO</value><minimum>0.62</minimum></limit>
      <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.36</minimum></limit>
    </limits>
  </rule>
</rules>
```

Set the minimums to current coverage, not an aspirational target: **run the aggregate report once
first and set the line/branch minimums at (or just below) the measured values** so the gate
passes on day one. The build fails only when coverage falls below these configured numbers. As
tests are added, manually raise the minimums toward the new measured level, working toward target
minimums of roughly **0.80 line / 0.70 branch**. The `0.62`/`0.36` values above are the current
measured coverage, used as the configured minimums so the gate passes today while still catching
regressions; they are the starting point, not the goal. A `check` rule cannot fail only because a
specific change lowered coverage of the lines
it touched; that kind of per-change enforcement needs separate diff-coverage tooling and is out
of scope here.

### B2. Exclude non-meaningful classes from the gate
Apply `<excludes>` on the `check` (and ideally the reports) for code that shouldn't count:
- `de/seuhd/campuscoffee/domain/tests/**`: `TestFixtures` lives in `domain/src/main` (production source).
- `**/Application.*`, `**/LoadInitialData.*`: bootstrap/dev-only wiring.
- Optionally OpenAPI customizers under `api/.../openapi/**`.

**Do not blanket-exclude `**/*MapperImpl.*`.** Two mappers carry real logic that such a pattern
would hide from coverage and mutation testing:
- `data/.../mapper/PosEntityMapper.java` has `default` methods `splitHouseNumber` and
  `mergeHouseNumber`: regex parsing of a house number, `String`->`int` conversion, an
  `IllegalArgumentException` branch when the number contains no digit, and null handling. (This
  logic compiles into the `PosEntityMapper` interface bytecode, not the `*Impl`, so it is measured
  even if the impl were excluded, but it must be tested, see Part D.)
- `api/.../mapper/ReviewDtoMapper.java` is an abstract class whose `@Mapping(expression = "java(...)")`
  navigates objects (`source.pos().getId()`) and calls `posService.getById(...)` / `userService.getById(...)`,
  and sets the `approved=false` / `approvalCount=0` defaults. MapStruct inlines these expressions
  into `ReviewDtoMapperImpl`, so excluding the impl would hide them.

The remaining impls (`UserDtoMapperImpl`, `PosDtoMapperImpl`, `UserEntityMapperImpl`,
`ReviewEntityMapperImpl`) are pure field-to-field mappings. Preferred approach: exclude no mappers
and let the system tests cover them; the generated null-guard branches that surface as uncovered
are accurate signal. If those generated branches prove too noisy for the gate, exclude only those
four declarative impls **by explicit name** and always keep `ReviewDtoMapperImpl` and
`PosEntityMapperImpl` in scope. Do not enable MapStruct's `@Generated`-based JaCoCo filtering, as
it would also strip the inlined `ReviewDtoMapperImpl` logic.

### B3. Wire the gate into CI
`.github/workflows/build.yml` currently runs `mvn -B package`. Change to:

```yaml
run: mvn -B verify --file pom.xml
```

`verify` runs after `package` and triggers the `coverage` module's `report-aggregate` + `check`.
Notes:
- System tests already run in the `test` phase under surefire (`*SystemTests` matches the
  default `*Tests` pattern), so Docker is already used in CI today, and no new CI infra is needed.
- `verify` will also start running the existing PMD `check`/`cpd-check` (they are
  `failOnViolation=false`, so they won't break the build).
- Modify the workflow so the coverage reports are available after the CI run: add a step that
  uploads `coverage/target/site/jacoco-aggregate/` (and, if useful, the per-module
  `*/target/site/jacoco/`) via `actions/upload-artifact`, so contributors can download and browse
  exactly which lines/branches are uncovered without running anything locally.

---

## Part C: Mutation testing with PITest (opt-in profile, local)

### C1. Versions + properties (root `pom.xml`)
Add properties:
```
<pitest.version>1.19.1</pitest.version>
<pitest.junit5.version>1.2.2</pitest.junit5.version>
<!-- PIT mutator group, one of DEFAULTS (stable set), STRONGER (more aggressive mutators),
     or ALL (every mutator). Override per run, e.g., -Dpitest.mutators=ALL -->
<pitest.mutators>DEFAULTS</pitest.mutators>
```
Defaulting to `DEFAULTS` keeps the first runs fast and the surviving mutants meaningful. Switching
to `STRONGER` or `ALL` produces more and harder-to-kill mutants, which is the point once the suite
is being expanded: more surviving mutants means more missing-assertion gaps to close. The override
is a single flag, so no config change is needed to move between groups.

### C2. The `mutation` profile (root `pom.xml`): per-module runs plus an application run
Add a `<profile><id>mutation</id>` (not active by default, so normal and CI builds are
unaffected). PITest is a per-module tool: each invocation mutates code and runs that module's own
tests, and `crossModule=true` additionally mutates the classes of the modules it depends on.

Each module mutates its own classes against its own tests, and `application` adds a cross-module
run for the system tests:
- **`domain`** (`domain/pom.xml`): `targetClasses = de.seuhd.campuscoffee.domain.*`, killed by its
  Mockito unit tests. No `crossModule` (domain depends on no other module).
- **`api`** (`api/pom.xml`): `targetClasses = de.seuhd.campuscoffee.api.*`, killed by its unit
  tests. Controllers and the exception handler have no api-local tests, so they show as not killed
  in this report; the application run below covers them.
- **`data`** (`data/pom.xml`): `targetClasses = de.seuhd.campuscoffee.data.*`, killed by its unit
  and integration tests.
- **`application`** (`application/pom.xml`, `crossModule=true`): `targetClasses =
  de.seuhd.campuscoffee.api.*` and `.data.*`, killed by the system and acceptance tests.
  `crossModule` is required because api and data reach it as dependency jars.

The reports are not merged. The api and data classes appear in two reports (a module's own and
application's), and PIT's `report-aggregate` overwrites rather than unions duplicate mutations: a
*survived* result overwrites a *killed* one, so a merged score would be badly understated. The
`coverage` module therefore disables the inherited `mutationCoverage` (it has no code of its own)
and does not aggregate mutation; it aggregates only JaCoCo coverage. Read each module's report for
what its own tests catch and the application report for what the system tests catch.

The shared plugin config and the `mutationCoverage` execution live in the profile's
`<pluginManagement>`; each module opts in by declaring the plugin with its `targetClasses`.

Shared PITest configuration (in `pluginManagement`):
- `<mutators><mutator>${pitest.mutators}</mutator></mutators>`: selects the group from C1
  (`DEFAULTS` by default; override with `-Dpitest.mutators=STRONGER` or `=ALL`).
- `<targetTests>` = `de.seuhd.campuscoffee.*`; `<targetClasses>` is set per module (above).
- `<excludedClasses>` mirroring B2 (`*.tests.*` fixtures, `Application`, `LoadInitialData`), plus
  the generated `de.seuhd.campuscoffee.*.*MapperImpl` classes, which hold only field copying and
  null guards. The hand-written mapper logic stays in scope: `PosEntityMapper`'s house-number
  parsing (with its `IllegalArgumentException` branch), `ReviewDtoMapper`'s expression mappings,
  and `HouseNumberConverter` are high-value targets.
- `<outputFormats>HTML,XML</outputFormats>` and `<timestampedReports>false</timestampedReports>`:
  HTML for humans, XML for programmatic inspection, at a fixed `target/pit-reports` path per module.
- `<parseSurefireArgLine>false</parseSurefireArgLine>` plus an explicit `<argLine>`
  (`-XX:+EnableDynamicAgentLoading -Xshare:off` and the Mockito `-javaagent`). surefire's argLine
  begins with `@{argLine}`, which PIT cannot resolve and would otherwise pass literally to the
  JVMs it launches.
- `<threads>1</threads>`: the system tests share a single static Postgres container
  (`AbstractSysTest`), so concurrent test processes would collide on it.
- `<timeoutConstant>30000</timeoutConstant>`: the application's tests are slow to start (Spring
  context and Testcontainers); without extra time PIT would treat a still-running test as hung and
  miscount the mutant.
- `<failWhenNoMutations>false</failWhenNoMutations>` and `<withHistory>true</withHistory>`.

### C3. How it is run (documented in README/CLAUDE.md)
```shell
# Full run. Clean first so stale reports are not reused. Slow, because PIT re-runs the full suite, including the data and system tests that start a Postgres container, for every mutant.
mvn -P mutation clean test

# Stronger or exhaustive mutator groups (more, harder-to-kill mutants):
mvn -P mutation clean test -Dpitest.mutators=STRONGER
mvn -P mutation clean test -Dpitest.mutators=ALL

# Scope to one module while iterating (runs only domain, skipping the slow Testcontainers modules):
mvn -P mutation test -pl domain -DtargetClasses=de.seuhd.campuscoffee.domain.implementation.ReviewServiceImpl
```
Reports are per module at `<module>/target/pit-reports/index.html` (`domain`, `api`, `data`, and
`application`). A full run is expensive and is intended to be run on demand, locally.

---

## Part D: Workflow for growing the test suite (the point of all this)

Document a short loop in `CLAUDE.md`/`README.md` so contributors (and students) use the tooling
to drive new tests:

1. **Find gaps:** open the aggregate JaCoCo report; sort packages by coverage. `data` and any
   thinly-tested service implementations surface first as the highest-value targets. Concrete
   known gaps to start with: `PosEntityMapper.splitHouseNumber` (the no-digit
   `IllegalArgumentException` branch and the suffix-parsing path) and the review approval rules in
   `ReviewServiceImpl`.
2. **Write tests** for the uncovered lines/branches: unit tests in `domain`/`api` where logic is
   isolable, system/acceptance tests in `application` for end-to-end paths.
3. **Check effectiveness with mutation testing:** run PITest (scoped with `-DtargetClasses=...`
   while iterating) on the class you just tested. **Surviving mutants are the to-do list:** they
   reveal assertions that are missing even though the line is "covered." Add assertions until they
   die. This is what pushes the suite from "executes the code" to "actually verifies behavior."
4. **Raise the minimum:** once coverage rises, manually increase the configured gate minimums
   (B1) to the new level so the bar reflects the improved suite.

Coverage measures breadth (which code runs under test); mutation score measures depth (whether
the tests actually detect changed behavior); the gate fails the build whenever measured coverage
is below the configured minimum.

---

## Documentation & comment quality

Document the setup so contributors can use and maintain it, and keep all prose and comments free
of AI-slop patterns (guidelines: https://gist.github.com/ossa-ma/f3baa9d25154c33095e22272c631f5a1).

- **What to document** (in `README.md` and/or `CLAUDE.md`):
  - How to generate the per-module and aggregate coverage reports, and where they are written.
  - How the CI coverage gate works, what the current line/branch minimums are, and how to raise them.
  - How to run mutation testing: the `mutation` profile, the per-module and application runs (which
    are not merged), and scoping a run with `-DtargetClasses=...`; note that it is local and slow.
  - The Part D workflow for turning report gaps into new tests.
- **Where to comment in the config**: add a comment only where it gives a reader information the
  code cannot: the non-obvious reason a setting exists. Each comment must describe the code
  as it currently is, not how it got there. Good targets: why the surefire `argLine` starts with
  `@{argLine}` (the JaCoCo agent is injected through that property and is otherwise dropped); why
  the `coverage` module copies the modules' classes before `jacoco:check` (`check` only analyzes the
  current module's classes); why `application` sets `crossModule=true` (api and data are
  dependencies, not its own code);
  why the mappers stay in scope (their inlined logic is real behavior). Do not write change-history
  comments ("changed from `package` to `verify`", "previously excluded", "now uses `crossModule`").
  The git log covers that. Never restate what a declaration plainly says.
- **Comment style:** plain, factual technical prose. Avoid: filler transitions ("It's worth
  noting", "Importantly", "Notably"); gravitas words ("crucial", "essential", "robust"); the
  verbs "leverage", "utilize", "streamline", "harness"; "not X but Y" parallelism; rhetorical
  questions ("The result? ..."); rule-of-three padding; em-dash overuse; decorative arrows/unicode;
  and pedagogical hand-holding ("Let's dive in", "Think of it as..."). State what the code does
  and why.

---

## Files to create / modify

**Create**
- `coverage/pom.xml`: aggregator module (merge + report-aggregate + class copy + check gate).

**Modify**
- `pom.xml` (root): add `jacoco.version`, `pitest.version`, `pitest.junit5.version` properties;
  add `coverage` to `<modules>` (last); add `jacoco-maven-plugin` to `<build><plugins>`;
  prepend `@{argLine}` to the surefire `<argLine>` (A1); add the `mutation` `<profile>` (shared PIT
  config in its `<pluginManagement>`).
- `domain/pom.xml`, `api/pom.xml`, `data/pom.xml`, and `application/pom.xml`: add the per-module
  `mutation` profile that sets each module's `targetClasses` (and `crossModule` for application) (C2).
- `lombok.config`: add `lombok.addLombokGeneratedAnnotation = true`.
- `.github/workflows/build.yml`: `mvn -B package` to `mvn -B verify`, plus an `actions/upload-artifact`
  step that publishes the coverage reports so they are available after the CI run (B3).
- `README.md` / `CLAUDE.md`: document the coverage reports, the CI gate and how to adjust its
  minimums, the mutation-testing workflow, and the Part D loop (see Documentation & comment quality).

---

## Verification

1. **Coverage collects integration/system coverage**
   - `mvn clean verify`
   - Open `coverage/target/site/jacoco-aggregate/index.html`. Confirm the **`data`** package
     (which has no unit tests) shows substantial line/branch coverage, proof that the
     `application` system/acceptance tests are being attributed correctly. If `data` shows ~0%,
     the `@{argLine}` fix (A1) or the aggregation is misconfigured.
2. **Gate fails when it should**
   - Temporarily set the `check` `LINE` minimum to `0.99` and confirm `mvn verify` fails in the
     `coverage` module; restore to a calibrated threshold and confirm it passes.
3. **Per-module reports exist**
   - Confirm `domain/target/site/jacoco/index.html` etc. are generated.
4. **CI**
   - Push a branch; confirm the `Build CampusCoffee` workflow runs `verify`, generates the
     aggregate report, and that the gate participates in the build status.
5. **Mutation (local, opt-in)**
   - Quick check: `mvn -P mutation test -pl domain`, then open `domain/target/pit-reports/index.html`
     and confirm domain mutants killed by the Mockito unit tests.
   - Full run: `mvn -P mutation clean test`, then open the per-module reports. Confirm `domain`,
     `api`, and `data` show kills from their own tests, and `application/target/pit-reports` shows
     kills from the system tests across `api.*`/`data.*`. Confirm a normal `mvn verify` (no
     `-P mutation`) does **not** trigger PITest.

## Risks / notes
- **`@{argLine}` is load-bearing**: without it JaCoCo records nothing. This is the most common
  failure mode; verify step 1 specifically guards it.
- **Calibrate thresholds before enabling the hard gate** so CI doesn't go red on day one
  (set them from the first measured aggregate run).
- **Mutation runtime**: a full mutation run re-runs the tests for every mutant, including the
  system tests against a PostgreSQL database in a container managed by Testcontainers, so it can take
  a long time; the profile is deliberately opt-in and local, and the docs steer toward scoping
  `-DtargetClasses` while iterating.
- **Version drift**: pinned versions are the latest resolvable on Central as of May 2026; bump
  the three properties to the newest resolvable at implementation time.
