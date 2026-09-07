# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CFLint is a static code analysis tool for CFML (ColdFusion Markup Language). It parses CFML source (via the external `CFParser`/`cfml.parsing` library), walks the resulting tag/expression tree, and runs a set of rule plugins against it to produce lint findings (HTML/XML/JSON/Text/FindBugs-XML reports).

## Build & test commands

Gradle is the primary build (Maven is still present via `pom.xml`). This branch is **Java 21 only**: compiler, toolchain, CI, and the Gradle daemon all use JDK 21. It pins cfparser `21.0.0` (cfparser `java-21`). The wrapper is Gradle **9.6.1**.

Keep these four places on 21 together: `maven.compiler.source`/`target` in `pom.xml`, toolchain `languageVersion` in `build.gradle`, and `java-version` in `gradle.yml` and `publish.yml`.

Until cfparser `21.0.0` is on GitHub Packages, install it from a cfparser `java-21` checkout (`mvn clean install`) so `mavenLocal()` resolves it.

```bash
./gradlew build              # compile + test + jar
./gradlew test               # run the JUnit test suite
./gradlew jacocoTestReport    # HTML/XML coverage under build/reports/jacoco (after test)
./gradlew fatJar             # shaded "-all" jar
./gradlew nativeCompile      # optional; needs GraalVM 25 on JAVA_HOME
```

Maven equivalent: `mvn clean install`.

**Local environment note:** Point `JAVA_HOME` at JDK 21 for `./gradlew` and `mvn`. Gradle 9 rejects very new JDKs (for example 26) at configuration time. If that happens, set `org.gradle.java.home` in `~/.gradle/gradle.properties` (machine-local) to a JDK 21 home and check with `./gradlew -v`.

**Native build (`nativeCompile`):** optional. Needs GraalVM 25+ because `org.graalvm.buildtools.native` **1.1.3** requires the newer reachability-metadata schema. CI `native-release.yml` uses GraalVM 25. Regular compile/test/CI stay on Temurin 21.

```bash
export JAVA_HOME=/path/to/graalvm-25
./gradlew nativeCompile
```

Running the CLI after building:

```bash
java -jar build/libs/CFLint-<version>-all.jar -folder <baseFolder>
java -jar build/libs/CFLint-<version>-all.jar -file <fullPathToFile>
java -jar build/libs/CFLint-<version>-all.jar -help
```

### Running a single test

Most CFLint tests are **data-driven**, not hand-written JUnit assertions. `src/test/java/com/cflint/integration/TestFiles.java` is a parameterized JUnit test that scans every `.cfm`/`.cfc` file under `src/test/resources/com/cflint/tests/` and diffs the JSON output against a sibling `<name>.expected.txt` file.

To add a test: drop a new `.cfm`/`.cfc` file into the appropriate subfolder of `src/test/resources/com/cflint/tests/`. The first run generates the `.expected.txt`; subsequent runs assert against it.

To run only one test file, edit `src/test/resources/com/cflint/test.properties` and set:

```properties
RunSingleTest=SomeFile.cfc
# or, to run whichever test file was most recently modified:
RunSingleTest=*LAST
```

then run `./gradlew test` (or run `TestFiles` as a JUnit test directly from an IDE). Remember to comment the property back out afterwards, or subsequent full-suite runs will only exercise that one file.

`AutoReplaceFailedTestResults=Y` in the same properties file will overwrite `.expected.txt` files with actual output on mismatch instead of failing — useful when intentionally changing rule output, dangerous otherwise.

Config-related unit tests live in `src/test/java/com/cflint/config/`.

## Architecture

### Scan pipeline

1. **`com.cflint.CFLint`** is the scan engine. `scan(File)` recurses directories (skipping dot-directories and applying `.cflintrc` files it finds along the way), reads each allowed source file, and calls `process(src, filename)`.
2. `process()` parses the file with `cfml.parsing.CFMLParser` into a Jericho HTML `Element` tree (tag-based CFML) or a `CFScriptStatement` tree (pure `.cfc`/`.cfs` cfscript), then walks it recursively (`process(Element, ...)` / `process(CFScriptStatement, ...)` / `process(CFExpression, ...)` are mutually recursive).
3. At each tag/expression, `CFLint` builds a **`com.cflint.plugins.Context`** (tracks filename, current element, current function/component, assignment/struct-key state, and a parent-chain for scope lookups) and invokes every registered **`CFLintScanner`** plugin's `element()`/`expression()` methods, passing a shared **`BugList`** that plugins append `BugInfo` messages to.
4. Plugins implementing **`CFLintStructureListener`** (a sub-interface of `CFLintScanner`) additionally get `startComponent`/`endComponent`/`startFunction`/`endFunction`/`beforeEndFile` callbacks, which is how checks that need to see a whole function/component body (e.g. unused-variable detection) work — they accumulate state in the `Context` and flush messages at the end.
5. `<cfinclude>`/`cfinclude()` and script `include` are resolved and inlined recursively during the same walk, with `includeFileStack` guarding against recursive includes.
6. Rule suppression is resolved via `Context.isSuppressed()`, checked against annotations collected by `registerRuleOverrides`/`applyRuleOverrides` (parses `@CFLintIgnore` comments and `//cflint ignore:` line comments).

### Plugins (rules)

- All rule implementations live in `src/main/java/com/cflint/plugins/core/` and implement `CFLintScanner` (usually via the no-op-everything base class `CFLintScannerAdapter`, overriding only the callbacks they need).
- A rule reports a finding via `context.addMessage(CODE, variable, ...)` / `context.addUniqueMessage(...)`; `CFLint` then converts pending `ContextMessage`s into `BugInfo` entries in the shared `BugList`.
- Every rule's message codes, severities, and message templates are registered centrally in **`src/main/resources/cflint.definition.json`** (the `ruleImpl` array — keyed by class name, not file path). A new rule class must be added there or it will never fire, since `ConfigUtils.loadPlugin` resolves plugin instances from this file.
- Rule groups (e.g. `Experimental`) are also defined in this JSON and are the basis for `-rulegroups` CLI filtering.
- See `RULES.md` for the human-readable meaning of each rule code.

#### Tag attribute classification: read vs. write

Several rules (notably `IMPLICIT_SCOPE`, and anything else that needs to know "does this tag attribute *read* a variable or *define* one") depend on `com.cflint.tools.CFMLTagInfo.isAssignmentAttribute(elementName, attributeName)`. It consults the `cfml.dictionary` tag definitions (from the external `com.github.cfmleditor:cfml.dictionary` artifact — a local sibling checkout exists at `~/development/github/cfparser/cfml.dictionary` for reference, e.g. `bin/main/org.cfeclipse.cfml/dictionary/cf11.xml`) two ways:
- a `<return parameter="name" .../>` on the `<tag>` element (e.g. `cfparam`'s `name=`), or
- a `<parameter name="..." returnVarType="...">` on the attribute itself (any non-blank `returnVarType` — `variablename`/`query`/`Struct`/`array`/`xml`/etc — means providing that attribute writes a variable, not just the literal string `"variablename"`).

The dictionary has real gaps/inconsistencies (e.g. `cfloop`'s `item=` has no `returnVarType` at all, even though it behaves exactly like `index=` which does), so `CFMLTagInfo.isAssignmentAttribute` also carries a small block of hardcoded exceptions above the dictionary lookup (`cfprocparam`'s `variable`, `cfloop`'s `item`, and the universal custom-tag/`cfmodule` `returnVariable=` convention which unknown/custom tags can't express via the dictionary at all since they have no entry in it).

In `com.cflint.CFLint.process()`, most tags flow through a generic branch that unpacks attributes via `unpackTagExpressions()` and picks assignment-vs-read context via `tagInfo.isAssignmentAttribute(...)`. A handful of tags (`cfcomponent`, `cffunction`, `cfquery`, `cfqueryparam`, `cfloop`+`query=`, `cfcatch`, `cfargument`/`cfdocumentsection`, `cfset`/`cfif`/`cfelseif`/`cfreturn`, `cfscript`, `cfinclude`) get their own dedicated branch instead — worth double-checking any such branch actually applies the same assignment/read distinction (the `cfquery` branch didn't, and always treated `name=`/`result=` as reads, until fixed in this session).

#### IMPLICIT_SCOPE (`com.cflint.plugins.core.ImplicitScopeChecker`)

Flags a bare/unscoped identifier read that might depend on ColdFusion's implicit-scope search fallback chain (`cgi`/`file`/`url`/`form`/`cookie`/`client` — checked only after `variables`/`local`/`arguments` fail to resolve the name). Design: it's a **whole-file (`.cfm`) or whole-function (`.cfc`) set-based check**, not order/lexically-scoped — it accumulates every bare write and bare read seen anywhere in that unit into sets (`unscopedAssignedVariables`, `variableScopedVariables`, `implicitIdentifierVariables`), then at `beforeEndFile`/`endFunction` flags any read whose name never appears as a bare write or explicit `variables.x` reference anywhere in that same unit. `com.cflint.StackHandler` (`context.getCallStack()`) is the other half of "known variable" — it's how loop items/index, query-loop columns, `cfcatch` names, and function arguments get recognized.

A large false-positive audit was done against a real codebase in 2026-07 (see conversation/git history around that date for the fixes applied) — the biggest bug found was a set called `implicitScopedVariables` that unconditionally flagged any bare read whose name was *also* read via `url.x`/`form.x`/etc. anywhere in the file, **even when a legitimate bare write for that same name also existed** (a very common pattern: `mode = URL.mode`). That set has been removed. `<cfoutput query="...">` also now gets the same `StackHandler` push/pop for the query's columns that `<cfloop query="...">` already had (it previously had none at all). If `IMPLICIT_SCOPE` false positives come up again, check `TestCFBugs_ImplicitScope.java` first — it has regression tests for each fixed category (prior-assignment-from-implicit-scope, `cfquery name=`, `cfloop item=`, scope keyword as literal arg to `StructKeyExists`/etc, `cfoutput query=` columns).

### Configuration layering

Configuration is resolved through a chain of `CFLintConfiguration` implementations (see `com.cflint.config`):

- `CFLintConfig` — a single config (global default is `src/main/resources/cflint.definition.json`; project-level overrides come from `.cflintrc` files, JSON or deprecated XML).
- `CFLintChainedConfig` — wraps a child config plus a parent, used to build the effective config as `CFLint.scan()`/`scan(File)` walks into directories containing `.cflintrc` (see `setupConfigAncestry` and the `.cflintrc` handling inside `scan(File)`).
- Precedence, closest-to-the-code-wins: global config → `-configfile` → `-rulegroups` → CLI `-includeRule`/`-excludeRule` → `.cflintrc` folder configs → inline `@CFLintIgnore`/`//cflint ignore:` annotations. This is documented in detail in `README.md` under "Precedence of configuration settings" — consult it before changing config-resolution code.
- `.cflintrc` files ending in `-<environmentName>` are also honored when an environment name is set (`getEnvSuffix()`), allowing per-environment rule sets.

### Entry points

- `com.cflint.cli.CFLintCLI` — the command-line entry point (`Main-Class` in the jar manifest); parses args via `commons-cli` and drives everything through `CFLintAPI`.
- `com.cflint.api.CFLintAPI` — the embeddable Java API (`scan(List<String>)`, `scan(String source)`, `scan(String source, String filename)`); wraps a `CFLint` instance and exposes results as `CFLintResult` (`getJSON()`, `writeJSON()`, etc.). This is what `TestFiles` and third-party JVM integrations use.
- `com.cflint.ant.CFLintTask` — an Ant task wrapper.
- Output formats (`HTMLOutput`, `JSONOutput`, `XMLOutput`, `TextOutput`, `com.cflint.xml.stax.*` marshallers) all render from the same `BugList`/`CFLintStats` produced by a scan; the FindBugs XML flavor is produced via XSLT (`src/main/resources/findbugs/cflint-to-findbugs.xsl`) rather than a marshaller class.

### Entity size limit on newer JDKs

`com.cflint.CFLint` raises `jdk.xml.totalEntitySizeLimit` to 10,000,000 at class init (unless already set). Without that, JDK 25+ can fail loading `cfml.dictionary` XML with `SAXParseException: JAXP00010004`. The bump is harmless on Java 21 and keeps the same binary usable on newer runtimes.

### Performance

Benchmarked against a real 5,235-file CFML codebase (`-folder` scan) in 2026-07:

- **Fixed**: `Pattern.compile(...)` was happening inline inside hot per-tag/per-name/per-comment methods (`ValidName`'s case checks, `HintChecker`/`ArgHintChecker`'s doc-comment scanners, `QueryParamChecker`, and `CFLint.java`'s `@CFLintIgnore`/`cflint ignore:`/`CFLINT-DISABLE` comment matchers) — recompiled from scratch on every single name/tag/comment checked across every file, instead of once. Hoisted to `private static final Pattern` fields (or, where the pattern is built from a `.cflintrc` config value rather than a literal, a small per-instance cache keyed by that config string). Also: `CFLint.getStructureListeners()` was reallocating and re-filtering the full plugin list via `instanceof` on every `startComponent`/`endComponent`/`startFunction`/`endFunction`/`beforeEndFile` event; now cached in a field, invalidated only when `extensions` actually changes (`addScanner`/`setConfiguration`).
- **Impact of the above**: modest, ~3-4% wall-clock (≈96.5s → ≈93s on the benchmark folder, consistent across repeated runs). CFML parsing itself dominates total runtime, so rule-level micro-optimization has a low ceiling.
- **Not done — biggest lever if revisited**: scanning is single-threaded end to end. `CFLintAPI.scan()` has an `ExecutorService`/`setThreaded()` scaffold, but the actual parallel branch is commented out — and even uncommented it wouldn't help a single large `-folder` scan, since it would only parallelize across top-level folder *arguments* passed to the CLI, not across the thousands of files discovered by recursing into one folder. The likely reason it was abandoned: `CFLint` holds a lot of mutable per-scan state shared across a single instance (`StackHandler`, `BugList`, plugin instances that themselves accumulate mutable tracking state, e.g. `ImplicitScopeChecker`) — scanning multiple files concurrently on one shared `CFLint`+plugin set would race. A correct fix needs one `CFLint` instance (with its own freshly-constructed plugin set) per worker thread, with results merged after — a real architectural change, not a mechanical one. Given the benchmark above only used ~1.3 cores throughout a ~93s run, this is plausibly a multi-x win on a multi-core machine for large folder scans, but wasn't attempted this session — flag it if someone asks "why is this slow on a huge repo" again.

### Dependency note

CFLint depends on `com.github.cfmleditor:cfml.parsing` (a fork of CFParser) for CFML tokenizing/parsing and ANTLR-generated cfscript grammar (`cfml.CFSCRIPTLexer`/`CFSCRIPTParser`). Most parser-level bugs (bad tokenization, grammar gaps) live upstream in that dependency, not in this repo — check there first if a rule seems to receive a malformed tree.

**The version is declared twice** and both must move together: a `cfparser.version` property in `pom.xml`, and a hardcoded coordinate in `build.gradle`'s `dependencies` block. A Maven-only bump leaves the Gradle build — which is the primary build and what CI runs — silently resolving the old artifact. See the `bump-cfparser` skill.

Two things make a bump look like it worked when it did not:

- **Merging upstream publishes nothing.** cfparser only deploys to GitHub Packages on a tag push, a release, or a manual workflow dispatch, so a fix merged to cfparser `master` stays invisible here until someone publishes it. A pin was stuck on a five-month-stale `2.15.0-SNAPSHOT` this way, and the intervening `2.15.1-SNAPSHOT` had a *failed* publish run, so that coordinate never carried the code its tag suggested.
- **Gradle caches `-SNAPSHOT` modules for 24 hours**, and the workflows restore a Gradle cache. A green CI run shortly after an upstream republish may well have compiled against the previous artifact. Confirm through a branch whose cache key differs, or resolve via Maven.

Worth knowing about the parser API: `parseCFMLExpression` caches parse trees and is what this repo calls (five sites in `CFLint.java`); cfparser's own tag traversal uses the uncached `parseCFExpression`, so upstream benchmarks of "the cache" may not describe this code path. `fireStartedProcessing` constructs a fresh `CFMLParser` per file, so that cache is per-file and never accumulates across a scan. On this machine a source checkout of that upstream project (including `cfml.dictionary`, the tag/attribute definition XML consumed by `CFMLTagInfo`) exists at `~/development/github/cfparser` — useful for reading tag definitions directly rather than guessing from the published jar, though CFLint's `build.gradle` pulls a released artifact, not this local source.
