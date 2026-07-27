# Release validation

This page records the evidence for the 0.1.0 release candidate. It distinguishes automated
proof from live workflow validation; a green build is not presented as evidence for a
measurement it did not perform.

## Automated gates

| Area | Evidence |
| --- | --- |
| Rust CLI | `cargo fmt --check`, Clippy with warnings denied, 7 unit tests, and release build |
| CLI contract | Invalid syntax returns JSON `INVALID_ARGUMENT` with exit 3; help/version return 0 |
| IntelliJ behavior | 49 real-PSI fixture tests, zero failures/errors/skips |
| Java | All target kinds, overload descriptors, same-name packages, class/file and case-only rename, connected/diamond method hierarchies |
| Kotlin K2 | All target kinds, companions, objects, extensions, type aliases, Java interop, connected method hierarchy |
| Python | Classes/functions/variables/parameters/locals, relative imports, annotations, same-name modules, hierarchy conflicts and multiple inheritance |
| Non-source PSI | Spring XML approval gate and rename; `persistence.xml` rename |
| Synchronization | External rewrite/create/delete, omitted/wrong hints, dirty+external conflict, smart-mode timeout |
| Mutation safety | Dry run, conflicts, read-only/dirty files, unpredicted edit/create/delete reporting, rollback and rollback-failure notification |
| Payload | 200 usages, 20-region cap, 64 KiB inline-diff cap, full file diff, rename headers |
| Benchmark harness | 10/100/500 generated usage counts, Java compilation, strict-threshold and input-validation tests |
| Documentation | Ruff, Pyrefly, and strict Zensical build |
| Plugin package | 49 fixture tests on IU-262.8665.337; Plugin Verifier compatible with IU-262.9437.22 |

The verifier reports four intentional internal API references, all from the watcher-health
field in `status`; they are pinned in `plugin/internal-api-registry.md`. All other verifier
categories remain fatal. The pinned `until-build=262.*` produces a configuration warning
by design because this project explicitly supports one IntelliJ release line.

## Product success criteria

| Criterion | State | Evidence |
| --- | --- | --- |
| Supported rename/usage matrix is green | Met | 49 IntelliJ fixture tests |
| Immediate external writes never yield stale results | Met | synchronization fixtures and mandatory full refresh |
| No unreported partial/unpredicted mutation | Met | mutation observers plus edit/create/delete and rollback fixtures |
| Default 200-usage payload fits the budget | Met | compact-payload fixture stays below 6,000 characters |
| CLI uses <5% of patch-agent tokens and <20% of compile-loop tokens | Pending live measurement | `benchmarks/` generator and strict analyzer |
| The author adopts the CLI in daily work | Met | live guarded Python rename of `java_check` to `check_java_runtime`, including dry-run review, apply, resolve, and usages verification |

## Live workflow validation

With the packaged plugin installed in IntelliJ IDEA 2026.2, the CLI reported Java,
Kotlin K2, Python, watcher health, and project readiness. A position-selected Python
function rename was then exercised end to end:

- guarded dry run exited 2 with one predicted file, one code usage, and no conflicts;
- apply exited 0 and reported exactly the two edited regions;
- resolving the new name exited 0;
- Find Usages returned the renamed call site and exited 0.

This run also verified that packaged Python support is loadable through the optional
`PythonCore` plugin dependency rather than only on the Gradle test classpath.

## Honest limitations

- Dynamic Python, reflection, arbitrary strings, resource-bundle keys, and arbitrary
  `.properties` values are not claimed as complete.
- Version 0.1 always performs the recursive correctness refresh; it does not implement
  the optional two-second watcher debounce.
- Maven and Gradle models are supplied by the already-open IntelliJ project. The fixture
  suite exercises multi-root path ambiguity but does not import build systems itself.
- A live-agent benchmark requires the pinned plugin installed in IntelliJ 2026.2 and
  provider-reported per-run token counts. Results must not be estimated from bytes.
- Live semantic validation must use an imported project model. Files in an unimported
  nested build may parse as language PSI without having resolvable cross-file references.

See the repository
[`benchmarks/README.md`](https://github.com/Glinte/refactor-cli/blob/main/benchmarks/README.md)
for the controlled procedure.
