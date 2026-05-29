# Plan: Kotlin code-quality cleanup + introduce ktlint auto-formatter

> **Historical document** (as of 2026-05-28). Superseded by the implemented project; see `CLAUDE.md`
> and `CHANGELOG.md` for the current state. Details below reflect the codebase at the time of writing.

## Context

The Java→Kotlin migration is complete and the repository is Kotlin-only. A code-quality review
focused on idiomatic Kotlin found the code to be strong overall, with a handful of concrete,
mostly-minor improvements, and no automated formatter is in place. This plan does both, in order:
first the **semantic cleanups** the review surfaced (a formatter cannot make these), then introduces
**ktlint** as a gated auto-formatter so style stays consistent without manual effort. Doing the
cleanups first means the one-time `ktlintFormat` pass covers the freshly edited code too.

Decisions confirmed with the user: formatter = **ktlint via the jlleitschuh Gradle plugin**;
enforcement = **gate it** (fail `gradle build` / CI on violations).

> Per project convention, on approval copy this plan into the repo's `docs/` directory as
> `docs/2026-05-28_kotlin-cleanup-and-ktlint.md` before starting.

Hard constraints (unchanged): coverage gate is non-decreasing (90% line / 80% branch); build via
mise (`~/.local/share/mise/shims/gradle`), no wrapper; package names are a contract; commit to `main`.
Note on `!!` vs `requireNotNull`: JaCoCo's Kotlin filter strips `!!` branches but not
`requireNotNull`, so prefer `!!` (or an accessor that uses `!!` internally) to avoid uncovered
branches — this shapes several choices below.

---

## Phase 1 — Idiomatic Kotlin fixes

### 1a. `TestFixtures`: stop deep-cloning immutable data classes; drop `commons-lang3` and `Serializable`
The fixtures are immutable `data class`es, so `SerializationUtils.clone(it)` is a Java-era defensive
copy with no purpose, and it is the only remaining use of `commons-lang3` and of `Serializable` on the
domain model.
- `domain/.../tests/TestFixtures.kt`: `getUserFixtures()/getPosFixtures()/getReviewFixtures()` return
  `USER_LIST`/`POS_LIST`/`REVIEW_LIST` directly; remove the `SerializationUtils` import. (Callers that
  need a variant already use `.copy(...)`.)
- `domain/.../model/objects/DomainModel.kt`: drop the `Serializable` supertype + import; update the
  KDoc (remove the "cloned (see TestFixtures)" rationale). Becomes
  `interface DomainModel<ID> : Identifiable<ID>`.
- `domain/build.gradle.kts`: remove `implementation(libs.commons.lang3)`.
- `gradle/libs.versions.toml`: remove the now-unused `commons-lang3` library entry.
- `build-logic/.../campuscoffee.java-conventions.gradle.kts`: drop the `commons-lang3` mention from the
  comment at line ~47 (only `spring-boot-starter-web` remains module-specific).

### 1b. Replace `java.util.Optional` with native Kotlin nullables (everywhere convertible)
Convert our own API surface; the only Optional that stays is the framework `JpaRepository.findById`
(and the one test that mocks it — see note).
- `domain/.../enums/OsmAmenity.kt`: `fromOsmValue(...): OsmAmenity?` =
  `entries.firstOrNull { it.name.lowercase() == osmValue }`; drop the Optional import; fix the KDoc.
  - `domain/.../OsmAmenityTest.kt`: `.contains(x)` → `.isEqualTo(x)`, `.isEmpty()` → `.isNull()`.
- `api/.../openapi/Parameters.kt`: `externalResource: Resource?` and
  `externalResourceName: String? get() = externalResource?.displayNameForOperation(operation)`; drop
  Optional import.
- `api/.../openapi/CrudOperationCustomizer.kt`: build the param with
  `crudOperation.externalResource.takeIf { it != NONE }`; rewrite `formatDescription`'s substitution as
  `val substitution = params.externalResourceName?.takeIf { spec.isExternalResource } ?: params.resourceName`;
  drop Optional import.
- `data/.../repositories/PosRepository.kt` / `UserRepository.kt`: derived queries return `PosEntity?` /
  `UserEntity?` (Spring Data supports nullable Kotlin returns); drop Optional imports.
- `data/.../implementations/CrudDataServiceImpl.kt`: replace `import java.util.Optional` with
  `import org.springframework.data.repository.findByIdOrNull`; then
  - `getById`: `repository.findByIdOrNull(id)?.let { mapper.fromEntity(it) } ?: throw NotFoundException(domainClass, id)`
  - `upsert` (the update branch): `val entity = repository.findByIdOrNull(id) ?: throw NotFoundException(domainClass, id)`
  - `findByFieldOrThrow(queryFunction: () -> ENTITY?, ...)`:
    `queryFunction()?.let { mapper.fromEntity(it) } ?: throw NotFoundException(domainClass, fieldName, fieldValue)`
- `data/.../implementations/OsmDataServiceImpl.kt`:
  - amenity: `OsmAmenity.fromOsmValue(amenityStr) ?: run { log.warn(...); throw MissingFieldException(...) }`
  - `nameDe/nameEn/description` become plain `tags["name:de"]` etc.; name fallback `nameEn ?: nameDe ?: name`;
    description `description ?: DEFAULT_DESCRIPTION`
  - `getRequiredTag`: `tags[key] ?: run { log.warn(...); throw MissingFieldException(...) }`
  - drop Optional import.
- Tests calling the changed APIs:
  - `data/.../integration/PosRepositoryIntegrationTest.kt`: `findByName(...)?.id` with `.isEqualTo(...)`;
    the miss case `assertThat(posRepository.findByName("No Such POS")).isNull()`.
  - `findById(id).orElseThrow()` in `PosEntityMapperRoundTripTest` / `OptimisticLockingIntegrationTest`:
    convert to `findByIdOrNull(id)!!` for consistency (native Kotlin; `findById` itself stays framework).
  - **Keep** `CrudDataServiceOptimisticLockTest.kt:30` `whenever(repository.findById(id)).thenReturn(Optional.of(...))`:
    it mocks the framework `findById` that `findByIdOrNull` delegates to (extensions can't be mocked).
    This is the single deliberate remaining `Optional`.
- Coverage note: the new `?:`/`?.let` add branches; all are exercised by existing found/not-found tests
  (e.g., `getByMissingIdReturnsNotFound`, `updateMissingPosReturnsNotFound`, `filterByNonexistentValue…`).

### 1c. Refactor `CrudOperationCustomizer.createSuccessResponseContent` (remove `var`/nested-`if`)
Replace the `var returnType` reassignment + nested `if/else` with a `when` and small helpers; collapse
the two `rawClass!!` to one in a named `refSchema` helper (keep `!!` for coverage, not `requireNotNull`):
```kotlin
private fun createSuccessResponseContent(handlerMethod: HandlerMethod): Content? {
    val returnType = unwrapResponseEntity(ResolvableType.forMethodReturnType(handlerMethod.method))
    val schema = when (returnType.rawClass) {
        Void::class.java, Void.TYPE -> return null
        List::class.java -> arraySchema(returnType.getGeneric(0))
        else -> refSchema(returnType)
    }
    return Content().addMediaType("application/json", MediaType().schema(schema))
}
private fun unwrapResponseEntity(t: ResolvableType) =
    if (t.rawClass == ResponseEntity::class.java) t.getGeneric(0) else t
private fun arraySchema(itemType: ResolvableType): Schema<*> =
    Schema<Any>().apply { type = "array"; items = refSchema(itemType) }
private fun refSchema(t: ResolvableType): Schema<*> =
    Schema<Any>().`$ref`("#/components/schemas/" + t.rawClass!!.simpleName)
```

### 1d. Comment cleanup (three Java-contrast remnants)
- `api/.../mapper/ReviewDtoMapper.kt:21-22`: drop the "Kotlin's protected, unlike Java's" contrast →
  "internal, not protected: MapStruct's generated subclass and the same-module test both inject these,
  and a protected member is not visible to a test in the same package."
- `domain/build.gradle.kts:16-17`: "…the processor runs via kapt." (drop "not the Java annotation processor").
- `gradle/libs.versions.toml:11`: "Testcontainers is not managed by the Boot 4 BOM at these coordinates,
  so pin it explicitly." (drop "no longer").

### 1e. Replace scattered id `!!` with an intent-revealing accessor (Option B — adopted)
Add a top-level extension on the shared `Identifiable<ID>` supertype (it declares `val id: ID?` and is
the parent of both `DomainModel` and `Dto`), in the domain module next to `Identifiable.kt`:
```kotlin
/** The identifier of a resource that must already be persisted; fails if it has not been created yet. */
val <ID : Any> Identifiable<ID>.persistedId: ID get() = id!!
```
Use it at the id `!!` sites — it covers both DTO ids (controllers) and domain ids (services):
- `ReviewServiceImpl`: `domainObject.pos.persistedId` (31), `user.persistedId` (58),
  `reviewToApprove.author.persistedId` (62)
- `CrudController.create`: `getLocation(created.persistedId)` (38)
- `PosController.importFromOsm`: `getLocation(createdPos.persistedId)` (108)

Add the `de.seuhd.campuscoffee.domain.model.objects.persistedId` import at each call site (api +
domain). The accessor uses `!!` internally, so the JaCoCo filter still strips the branch (no coverage
cost) while naming the "must be persisted" invariant in one place. `approvalConfiguration.minCount!!`
is a config value, not an id — leave it.

---

## Design note — what a non-null-id model could look like (answer to the question)

`id` is nullable because a JPA-generated identifier does not exist until the row is persisted, so a
newly built domain object genuinely has no id. Options:

- **A. Distinguish "new" from "persisted" at the type level** — e.g., a `NewPos` (no id) vs `Pos`
  (non-null `id: Long`), or a generic wrapper `Persisted<T>(val id: Long, val value: T)`. This is the
  "correct" model and removes every id `!!`, but it doubles the model surface, fights the generic
  `CrudController<…>` / `CrudService<DOMAIN, ID>` / mapper machinery (which assume one type per
  resource), and is a large change. Not recommended for this teaching codebase.
- **B. Keep one nullable-id type, add an intent-revealing accessor** (Phase 1e) — `persistedId`/
  `requireId()` centralizes the "must be persisted here" invariant behind a name and a single `!!`.
  Low churn, keeps the generics, no coverage cost. **Recommended** if we want the `!!` sites gone.
- **C. Status quo** — leave `!!` at the call sites; they sit right after a fetch or create where
  non-nullness is locally obvious, and `!!` is already coverage-friendly. Acceptable.

Recommendation: **B** — adopted as Phase 1e. (A is too heavy for this codebase and fights the
generics; C is the do-nothing fallback.)

---

## Phase 2 — Structure / readability

The structure is strong (clean generic base classes, hexagonal layout, well-sized functions); the
controller CRUD-override repetition is unavoidable (annotations can't be inherited) and is left as-is.
The one actionable item is the compiler warnings about override parameter names differing from the
supertype (which would break named-argument calls through the supertype):

- **Data port misnomer + warning** — `CrudDataService.upsert(entity: DOMAIN)` names a *domain* object
  `entity` (also the source of the `CrudDataServiceImpl`/`PosDataServiceImpl` warnings). Rename the
  port parameter to `domain` to match the impl (`CrudDataServiceImpl.upsert(domain)`) and the
  semantics. All callers are positional, so this is safe.
- **Controller overrides** — rename `create`/`update` override params `posDto`/`userDto`/`reviewDto` →
  `dto` to match `CrudController` (the `@Parameter`/`@RequestBody`/`@Valid` annotations stay on the
  param; request binding and Swagger are unaffected).
- Verify the warning set is empty after these renames (`gradle :api:compileKotlin :data:compileKotlin`).

---

## Phase 3 — Introduce ktlint (gated)

ktlint enforces the official Kotlin conventions the code largely follows (4-space indent, ~120 col,
no wildcard imports). One deliberate deviation from `ktlint_official`'s defaults: **trailing commas are
turned off** (`ij_kotlin_allow_trailing_comma* = false`), per preference. ktlint then *removes* the
trailing commas currently in ~62 files, so the one-time format is a larger — but one-time and
behavior-neutral — diff. Integration via the jlleitschuh
`org.jlleitschuh.gradle.ktlint` plugin, which auto-wires `ktlintCheck` into `check` (so the gate rides
on the existing `gradle build`/CI, like `coverageGate`) and provides `ktlintFormat`.

1. **Catalog** (`gradle/libs.versions.toml`): add `[versions]` `ktlint-plugin` (latest 12.x of
   `org.jlleitschuh.gradle:ktlint-gradle`) and `ktlint-tool` (latest 1.x ktlint engine) — resolve exact
   latest at implementation; add `[libraries]`
   `ktlint-gradle-plugin = { module = "org.jlleitschuh.gradle:ktlint-gradle", version.ref = "ktlint-plugin" }`.
2. **build-logic** (`build-logic/build.gradle.kts`): `implementation(libs.ktlint.gradle.plugin)`.
3. **Convention plugin** (`build-logic/.../campuscoffee.kotlin-conventions.gradle.kts`): add
   `id("org.jlleitschuh.gradle.ktlint")` to `plugins {}` and set the engine version from the catalog via
   `the<VersionCatalogsExtension>()` (the pattern `java-conventions` already uses):
   ```kotlin
   configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
       version.set(libs.findVersion("ktlint-tool").get().requiredVersion)
   }
   ```
   This covers `domain`/`api`/`data`/`application` source sets and each module's `build.gradle.kts` with
   no per-module edits (all four already apply `kotlin-conventions`); enforcement is automatic.
4. **`.editorconfig`** (new, repo root):
   ```
   root = true
   [*]
   charset = utf-8
   end_of_line = lf
   insert_final_newline = true
   trim_trailing_whitespace = true
   [*.{kt,kts}]
   indent_style = space
   indent_size = 4
   max_line_length = 120
   ktlint_code_style = ktlint_official
   ij_kotlin_allow_trailing_comma = false
   ij_kotlin_allow_trailing_comma_on_call_site = false
   ```
   If the first run flags a deliberate construct, disable that one rule with
   `ktlint_standard_<rule> = disabled` + a one-line why, rather than loosening the whole style.
   Also add a repo-root **`.gitattributes`** with `* text=auto eol=lf` so line endings are normalized
   to LF at the git layer — enforcement that does not depend on each editor honoring `.editorconfig`'s
   `end_of_line` (the two are complementary; both are kept).
5. **One-time format**: run `gradle ktlintFormat` (removes the existing trailing commas per the
   `.editorconfig` and applies any other fixes); manually wrap the 1–2 lines over 120 cols ktlint can't
   auto-wrap (e.g., `CrudOperationCustomizerTest.kt`). Commit **separately** from the wiring.
   - Fallback: if a ktlint version does not honor the flag override under `ktlint_official` and keeps
     re-adding commas, instead disable the two rules outright —
     `ktlint_standard_trailing-comma-on-declaration-site = disabled` and
     `ktlint_standard_trailing-comma-on-call-site = disabled` — and strip the existing commas in the
     one-time format. (`= false` is preferred because it also *forbids* new trailing commas; the
     disable-fallback merely stops enforcing either way.)
6. **Optional extensions**: apply ktlint in `build-logic/build.gradle.kts` to lint the convention
   scripts too; `addKtlintFormatGitPreCommitHook`; upload the ktlint report in CI (the gate needs no
   workflow change).

---

## Sequencing & commits

1. Phase 1 + Phase 2 semantic cleanups → one commit ("Idiomatic Kotlin cleanup: drop commons-lang3,
   replace Optional with nullables, refactor OpenAPI schema builder, fix comments & param names").
   `gradle build` green, coverage gate holds.
2. Phase 3 wiring (catalog, build-logic, convention plugin, `.editorconfig`) → one commit.
3. One-time `ktlintFormat` → a separate commit.
4. CHANGELOG `[Unreleased]` entry covering all of the above.

## Verification

1. `gradle build` after Phase 1+2: 165 tests still pass; coverage gate holds (the `?:`/`?.let`
   branches are covered; `!!` stays filtered). Confirm `gradle :api:compileKotlin :data:compileKotlin`
   emits no parameter-name warnings.
2. `gradle ktlintCheck` passes after the one-time format; `gradle build` green (formatting is
   behavior-neutral).
3. Gate proof: introduce a deliberate style violation, confirm `gradle build` fails on the ktlint
   check, then `gradle ktlintFormat` fixes it.
4. CI: push; confirm the GitHub Actions build now also enforces ktlint.

## Files touched (representative)

- Phase 1/2: `domain/.../tests/TestFixtures.kt`, `domain/.../model/objects/DomainModel.kt`,
  `domain/.../model/objects/Identifiable.kt` (+ `persistedId` extension; call sites in `ReviewServiceImpl`,
  `CrudController`, `PosController`), `domain/.../model/enums/OsmAmenity.kt` (+ `OsmAmenityTest`),
  `api/.../openapi/Parameters.kt` &
  `CrudOperationCustomizer.kt`, `api/.../mapper/ReviewDtoMapper.kt`,
  `data/.../repositories/{Pos,User}Repository.kt`, `data/.../implementations/CrudDataServiceImpl.kt` &
  `OsmDataServiceImpl.kt`, `domain/.../ports/data/CrudDataService.kt`,
  `api/.../controller/{Pos,User,Review}Controller.kt`, the affected `data` integration tests;
  `domain/build.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/.../java-conventions.gradle.kts`.
- Phase 3: `gradle/libs.versions.toml`, `build-logic/build.gradle.kts`,
  `build-logic/.../kotlin-conventions.gradle.kts`, `.editorconfig` (new), `.gitattributes` (new),
  `CHANGELOG.md`, plus the one-time formatting touches.
