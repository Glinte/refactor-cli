# `refactor`: a CLI Bridge to IntelliJ Semantic Rename and Find Usages

**Status:** Draft
**Scale:** One-person project, AI-assisted, ~2 weeks focused work
**Primary user:** The author, plus any developer with a similar setup
**Target languages:** Java, Kotlin (K2), Python
**Operations:** Sync, resolve, find usages, rename
**Interface:** CLI only, JSON output
**Components:** IntelliJ IDEA plugin (Kotlin) + thin CLI (Rust)

---

# 1. Problem and Goals

## 1.1 Problem

AI coding agents refactor by generating textual patches. For project-wide changes this is
expensive and unreliable: renaming a type with 200 references across 60 files costs a
patch-based agent tens of thousands of tokens and minutes of wall clock, with many chances
to miss a reference or leave the project half-edited. On JVM projects a stronger agent runs
an edit–build–fix loop, which converges but still burns tokens and minutes per compile
round trip. The same is true of semantic search: "find every real usage of this symbol" is
a grep-read-filter loop for an agent, and grep cannot distinguish two symbols with the same
name or find references in framework configuration files.

IntelliJ IDEA already holds the needed semantic state — PSI, indexes, cross-language
references, framework-contributed references in XML and properties files — and already
implements semantic rename and Find Usages. What is missing is a precise, machine-oriented,
scriptable way to invoke them.

## 1.2 Outcome

> Make agent-driven rename and usage search **cheap**: one CLI call instead of a
> patch-or-build loop.

Expected economy for a 200-usage rename: roughly 1,000–1,500 tokens and 2–15 seconds,
versus 20,000–100,000 tokens for a naive patch loop and 5,000–30,000 for a compile-assisted
JVM loop. Two consequences drive the design:

* **The response payload is part of the budget.** Output is compact by default; diffs are
  opt-in and size-capped.
* **The benefit does not depend on agent error rates.** Even a perfect agent saves the
  tokens, which is why economy — not safety — leads. Safety benefits are real but
  secondary, strongest for Python and non-source files where no compiler catches a missed
  reference, and detection there is also weakest (see risk R3); the tool reports
  limitations rather than claiming completeness.

`usages` carries the same economy with no mutation at all and is valuable in conversations
where no rename ever happens.

## 1.3 User and setting

An individual developer who has the project open in IntelliJ IDEA locally, uses a
terminal-based coding agent that edits files directly on disk, and reviews and commits all
changes through Git themself. The agent discovers the tool through project documentation
(`CLAUDE.md` / `AGENTS.md`) pointing at `refactor --help`.

## 1.4 Goals

* **G1. Semantic, never textual.** All search and rename goes through IntelliJ's Find
  Usages and rename machinery. If IntelliJ cannot do it, the tool fails — it never falls
  back to search-and-replace.
* **G2. Exact, guarded targeting.** Qualified names first, file positions as fallback, with
  an `--expect` guard so a stale selector fails loudly instead of renaming the wrong
  symbol.
* **G3. Single-shot atomicity.** One CLI invocation resolves, analyzes, and — if clean —
  applies, all under one lock in the IDE. There is no plan object, no second call, and
  therefore no window in which the workspace can drift between analysis and mutation.
  Anything not clean is printed and left unapplied.
* **G4. Workspace coherence.** The agent writes files with its own tools; IntelliJ's VFS
  does not see those writes until refreshed. Every operation synchronizes first. This is
  the correctness core of the project, not a detail.
* **G5. Agent-oriented output.** Structured JSON, stable error codes, meaningful exit
  codes, explicit old-path→new-path mappings so an agent whose context predates the rename
  can update itself without re-reading files.
* **G6. Local-only security.** Loopback, token-authenticated, no remote traffic.

## 1.5 Non-goals

Move, safe delete, extract/inline/change-signature, package or module or directory rename,
MCP, running without a live IDE, CI/headless use, other IDEs, other languages, renaming
comments/strings, complete detection of reflective or string-encoded references, concurrent
refactorings in one project, writing to VCS, and compatibility beyond one pinned IntelliJ
release line. Several of these are natural extensions (§5); none is required for the tool
to pay for itself.

## 1.6 Assumptions

* **A1.** IntelliJ IDEA is running with the project open, indexed, and with the required
  language plugins (Java/Kotlin bundled; Python plugin for Python projects).
* **A2.** One pinned platform release line (initially IntelliJ IDEA 2026.2), Kotlin plugin
  in K2 mode only.
* **A3.** IntelliJ is the source of semantic truth; the tool never second-guesses
  resolution.
* **A4.** The agent edits files outside the IDE constantly; stale-VFS is the normal case,
  handled by synchronization, not an error.
* **A5.** The human may be typing in the IDE concurrently; detected via modification
  stamps and rejected, never merged.
* **A6.** The project is almost always a Git repository; Git is the user's recovery tool,
  and the tool's job is to not sabotage it (atomic application, full change reporting,
  Local History label as backstop).

---

# 2. Functional Specification

## 2.1 Commands

```text
refactor status                       # projects, readiness, indexing, watcher health
refactor sync      [--touched PATH]...
refactor resolve   (--symbol FQN | --file PATH --line N --col N) [--expect NAME[:KIND]]
refactor usages    <selector> [--max 200]
refactor rename    <selector> --to NEWNAME
                   [--dry-run] [--force-non-source] [--diff none|inline|file]
                   [--touched PATH]...
```

All commands take `--project ROOT` (defaulting to the Git/content root containing the
current directory) and emit JSON on stdout. `--touched` on any command passes recently
written paths as a refresh hint.

## 2.2 Selectors and guards

* **Symbol selector** (preferred, Java/Kotlin): `--symbol com.example.UserService`, with an
  optional `#member` and JVM-descriptor suffix for overloads
  (`com.example.Svc#save(Ljava/lang/String;)V`). Stable across unrelated edits, which is
  what agents need.
* **Position selector** (fallback, required for locals and most Python): `--file`,
  one-based `--line`/`--col` on the CLI, converted to zero-based line and UTF-16 column
  internally. Fragile in exactly the way agents are fragile — one insertion above the
  target shifts everything — hence:
* **Guard**: `--expect UserService:CLASS`. On mismatch the call fails with
  `TARGET_MISMATCH` and reports what was actually found. This converts the dangerous
  failure (silently renaming an adjacent plausible symbol) into a loud one. Project docs
  instruct agents to always pass it.

Zero matches → `SYMBOL_NOT_FOUND`. Multiple materially different matches →
`AMBIGUOUS_TARGET` with the candidate list, so the agent re-selects.

## 2.3 Rename semantics

Supported targets:

| Language | Renameable |
| -------- | ---------- |
| Java     | Top-level and nested classes/interfaces/enums/records/annotations; methods; fields and enum constants; locals; parameters; type parameters |
| Kotlin   | Classes/interfaces/objects and named companions; functions; properties; locals; parameters; type aliases; type parameters |
| Python   | Classes; functions/methods; module-level variables; parameters; locals |

Behavior:

* Semantic code references updated, **including non-source references** (Spring XML,
  `persistence.xml`, resource bundles — these are real PSI references contributed by
  framework support, not text matches, and skipping them would ship silent breakage).
* Renaming a method renames its entire override hierarchy, per IntelliJ semantics; when the
  selected target is not the hierarchy root, output reports `hierarchyRoot`.
* Language-required file renames (public Java top-level type) are part of the operation and
  appear in `renamedPaths`.
* Comments and arbitrary strings are excluded. Cosmetic related-variable renames are
  disabled.
* Case-only renames (`Foo`→`foo`) are supported and explicitly tested on case-insensitive
  filesystems, where the file rename is a known VFS trap.

## 2.4 Apply-or-report

`rename` is single-shot with a conservative gate:

* **Applies immediately** when: target resolves uniquely (and passes the guard), the new
  name is valid, there are no conflicts, all affected files are writable and have no
  unsaved IDE modifications, and either no non-source files are affected or
  `--force-non-source` was given.
* **Reports without applying** (exit 2) when any conflict exists, when non-source files
  would change without the flag, or when `--dry-run` was given. The report contains
  everything the agent needs to decide: usage summary, affected files split
  source/non-source, conflicts, warnings. The agent then re-runs with the flag or fixes the
  conflict.

Conflicts never auto-apply; that is not configurable. Analysis and mutation happen inside
one server-side request under the project mutex, so nothing can change between them.

## 2.5 Output

`usages` (truncated example):

```json
{
  "target": {"qualifiedName": "com.example.UserService", "kind": "CLASS"},
  "totalUsages": 214, "truncated": true,
  "summary": {"code": 198, "imports": 12, "nonSource": 2, "nonCode": 2, "overrides": 0},
  "usages": [
    {"path": "src/main/java/.../UserController.java",
     "line": 7, "col": 11, "kind": "CODE",
     "excerpt": "private final UserService userService;"}
  ],
  "warnings": []
}
```

`rename`, applied:

```json
{
  "status": "APPLIED",
  "target": {"oldQualifiedName": "com.example.UserService",
             "newQualifiedName": "com.example.AccountService"},
  "hierarchyRoot": null,
  "renamedPaths": [
    {"from": "src/main/java/com/example/UserService.java",
     "to":   "src/main/java/com/example/AccountService.java"}
  ],
  "changedFiles": [
    {"path": "src/main/java/com/example/AccountService.java", "kind": "SOURCE",
     "expected": true, "editCount": 3, "regions": [[3,3],[12,12],[40,41]]},
    {"path": "src/main/resources/applicationContext.xml", "kind": "NON_SOURCE",
     "expected": true, "editCount": 1, "regions": [[18,18]]}
  ],
  "localHistoryLabel": "refactor req_01K456DEF",
  "timings": {"syncMs": 240, "analysisMs": 910, "mutationMs": 692},
  "diff": null
}
```

Design rules: the default payload for a 200-usage rename fits ~1,500 tokens; `regions` are
post-change inclusive line ranges capped at 20 per file with a `regionsTruncated` flag;
diffs are opt-in (`--diff inline` capped at 64 KB, `--diff file` writes the full diff to a
temp path and prints the path) and represent file renames with git-style rename headers,
never delete-plus-add. `renamedPaths` exists so the agent never parses a diff to learn a
path changed. Files that changed but were not predicted are still reported, flagged
`"expected": false`; an unreported change is a correctness bug.

## 2.6 Exit codes and errors

```text
0  applied / query succeeded
2  needs review (conflicts, non-source without flag, --dry-run)
3  user error        SYMBOL_NOT_FOUND, AMBIGUOUS_TARGET, TARGET_MISMATCH,
                     UNSUPPORTED_SYMBOL, INVALID_NAME, FILE_NOT_FOUND,
                     POSITION_OUT_OF_RANGE
4  environment       IDE_NOT_RUNNING, PROJECT_NOT_FOUND, PROJECT_INDEXING,
                     PROJECT_BUSY, SYNC_TIMEOUT, LANGUAGE_PLUGIN_MISSING,
                     DIRTY_AFFECTED_DOCUMENT, EXTERNAL_CHANGE_CONFLICT,
                     READ_ONLY_FILE, IDE_VERSION_UNSUPPORTED
5  internal          REFACTORING_FAILED, ROLLBACK_FAILED, INTERNAL_ERROR
```

Agents branch on exit code without parsing; details are in the JSON `code` and `message`.
`ROLLBACK_FAILED` means the workspace may be inconsistent: the JSON enumerates every file
observed to change and names the Local History label, and the plugin raises an IDE
notification balloon so the human — the party who must act — sees it even without watching
the agent transcript. Recovery guidance points at `git status`/`git restore` first, Local
History second.

## 2.7 Dirty state

* **Unsaved IDE buffers** in affected files block mutation (`DIRTY_AFFECTED_DOCUMENT`):
  applying would silently save unrelated human edits.
* **External agent writes** are the normal case — handled by sync, never rejected.
* **Both at once** on the same file is a genuine ambiguity: `EXTERNAL_CHANGE_CONFLICT`,
  no side chosen.
* **Uncommitted Git changes** are none of the tool's business; the user runs Git.

---

# 3. Technical Specification

## 3.1 Architecture

```text
agent / user ──(CLI, JSON out)── refactor (Rust binary)
                                     │  HTTP JSON-RPC, loopback, bearer token
                                     ▼
                              IntelliJ plugin (Kotlin)
                                     │  sync · resolve · usages · rename
                                     ▼
                              IntelliJ Platform
                              VFS · PSI · indexes · Find Usages ·
                              rename processors · Local History
```

All intelligence lives in the plugin. The Rust CLI is deliberately thin: parse args, read
the descriptor file, POST one JSON-RPC request, print the response, map the error code to
an exit code. Estimated ~500 lines.

## 3.2 Transport

The plugin binds an HTTP server to `127.0.0.1:<ephemeral>` and writes a descriptor to a
per-user location (`~/.refactor-agent/<hash-of-project-root>.json`):

```json
{"protocolVersion": 1, "idePid": 12345, "ideBuild": "IU-262.x",
 "port": 43127, "token": "<random-256-bit>", "projects": ["/home/user/project"]}
```

Descriptor is user-only permissions; token rotates on IDE restart; stale descriptors
(dead PID) are ignored and cleaned. If several live descriptors claim the same project, the
CLI errors and asks the user to close one — no guessing. Requests carry
`Authorization: Bearer`; non-loopback connections are rejected regardless of token. The
server exposes exactly the four operations; no file-read, command-execution, or PSI
scripting endpoints. Request size and per-operation time limits apply.

## 3.3 Threading

Three platform rules shape everything: index/PSI reads need a (cancellable, smart-mode)
read action; PSI/document writes need a write action on the EDT inside a command; VFS
refresh is asynchronous and must not run inside a read action. Every operation is
therefore: **sync (no lock) → analyze (cancellable smart read) → mutate (command +
write action on EDT)**.

## 3.4 Workspace synchronization

The load-bearing section. The agent writes files; the VFS is a cached snapshot that learns
of external writes only from the OS watcher (asynchronous, unreliable on network
filesystems and at watch limits) or an explicit refresh (default trigger: window focus). A
backgrounded IDE can be arbitrarily stale.

Before every operation:

1. If `--touched` paths were given, refresh exactly those plus parent directories
   (catches creations/deletions) via `refreshAndFindFileByNioPath`. **Hinted refreshes
   always execute** — they cost <100 ms and skipping them reopens the staleness hole.
2. Otherwise, asynchronously refresh the project content roots recursively. This full
   refresh may be skipped only when a successful one completed within 2 s **and** the
   platform watcher reports operational **and** no watcher event arrived since — a degraded
   watcher disables the debounce entirely, since it would otherwise trust exactly the
   mechanism whose unreliability motivates this section.
3. `PsiDocumentManager.commitAllDocuments()`.
4. If refresh triggered indexing, wait for smart mode up to 30 s, else `SYNC_TIMEOUT`
   with elapsed time so the agent retries instead of hanging.

`refactor sync` exposes this directly so an agent pays once before a batch. Honest limit:
refresh cannot make re-indexing instant; after a large rewrite the first call may be
dominated by indexing, and timings report `syncMs` separately so the cost is attributable.

## 3.5 Resolution

Symbol selectors resolve through the language index (`JavaPsiFacade`; Kotlin declaration
index; Python qualified-name resolution), restricted to project scope, filtered by
descriptor when given. Position selectors: path → `VirtualFile` → `PsiFile` → offset →
element → walk up to the named declaration, resolving references so that selecting a
*usage* selects its declaration. Both paths end identically: apply the `--expect` guard,
take a `SmartPsiElementPointer`, compute a fingerprint (language, kind, qualified name,
file, range, container) that mutation re-verifies after re-sync — a pointer that survived
reparse but now aims at a different declaration is caught.

## 3.6 Usages

Runs on the platform Find Usages infrastructure with language handlers, in a cancellable
smart read, post-sync. Collects to `--max` plus a count-only pass for the true total;
classifies (code / import / non-source / override / non-code); library scope excluded.
This service is shared verbatim by rename's analysis phase — building `usages` first is
mostly building rename's front half.

## 3.7 Rename execution

Per language, a dedicated adapter drives IntelliJ's rename processor **non-interactively**
— never `setName()` directly (skips language handlers), never dialog automation. Settings:
semantic usages on, non-source references on, comments/text/cosmetic-related off,
full override hierarchy on. A test-mode-style guard turns any attempted modal dialog into
`UNSUPPORTED_SYMBOL` instead of a hung IDE.

Mutation sequence: acquire project mutex → re-sync → re-resolve + fingerprint check →
modification-stamp check → commit documents → **`LocalHistory.putSystemLabel`** →
capture before-text of candidate files → run the rename as one command in a write action →
complete postponed PSI → capture after-state *including unpredicted files* → derive
`renamedPaths` + regions (+ diff on request) → save documents → release mutex.

Rollback on failure, layered: (1) the Local History label — placed pre-mutation, cheap,
independent of the undo stack, works for multi-file and non-source changes, and is the
primary story because programmatic `UndoManager.undo` wants a `FileEditor` context and can
itself fail on inconsistent PSI; (2) best-effort command undo when an editor context
exists; (3) `ROLLBACK_FAILED` + IDE notification per §2.6.

Concurrency: reads run concurrently; one sync at a time (joiners attach to the in-flight
one); one usage-search-bearing analysis at a time; one mutation at a time, blocking new
analysis; cancellable until the write command starts.

## 3.8 Kotlin K2

The largest technical risk. The K2 rename path runs on the Analysis API; adapters respect
`analyze {}` session lifetimes (no symbols escaping the session, no caching across read
actions). Any `@ApiStatus.Internal` symbol used is recorded in a registry file (symbol,
why no stable alternative, what breaks if it vanishes) — the input to every IDE-version
upgrade. K2 only; K1 is not supported.

## 3.9 Rust CLI notes

`clap` for args, `serde`/`serde_json` for the protocol, `ureq` (blocking — a CLI making one
request has no business with async, and async Rust is the worst first Rust), `anyhow` for
errors. Wire DTOs are defined once as JSON Schema in the repo and mirrored by hand on both
sides — at ~10 types, codegen is overkill. Single static binary, fast startup, which
matters when an agent invokes it dozens of times per session.

## 3.10 Testing

Model-level functional tests on real IntelliJ project fixtures (before-directory →
expected-after-directory whole-tree comparison), per JetBrains' own recommendation — no
mocked PSI. Budget real calendar time for making the test framework boot; it is
notoriously fiddly.

Matrix highlights: Java Gradle/Maven/multi-module; overloads; rename initiated from an
override; same simple name in several packages; filename-coupled public type; **case-only
rename on macOS/Windows volumes**; Java↔Kotlin cross-references; **Spring XML +
`persistence.xml` + resource-bundle references**; Kotlin objects/companions/extensions/
type aliases with K2 asserted in setup; Python src-layout, namespace packages, aliased and
relative imports, annotations, same-name-many-modules, dynamic imports (expected-limitation
tests). Sync suite: external write then immediate call with no focus event; external
create/delete; missing/wrong/omitted `--touched` (correctness must not depend on the
hint); hinted refresh inside the debounce window (must still run); debounce disabled under
degraded watcher; `EXTERNAL_CHANGE_CONFLICT`; `SYNC_TIMEOUT`. Payload suite: 200-usage
result under the token budget; region caps; diff truncation; rename headers. Negative:
every §2.6 code, dialog-attempt guard, mid-write processor exception, unpredicted-file
reporting, rollback failure incl. the notification.

Benchmark (release-gating, published in README): fixture repo with 10/100/500-usage
symbols, renamed by (a) a naive patch-loop agent, (b) a compile-assisted edit-build-fix
agent on JVM, (c) the same agent via this CLI; record tokens and wall clock. Target: <5%
of (a)'s tokens, <20% of (b)'s. Running through a real agent also tests whether the
`--help` text and project docs successfully steer tool selection.

## 3.11 Risks

* **R1. K2 rename needs internal API / churns per release.** 60–80% per major IDE upgrade.
  Mitigate: pinned release line, adapter isolation, internal-API registry. Accepted cost of
  the approach.
* **R2. AI assistance is weak on IntelliJ internals** — sparse, outdated training data;
  confident hallucinated APIs. Mitigate: keep a local clone of `intellij-community` and
  make the agent grep real source instead of recalling it; treat every unverified API
  claim as wrong until found in source.
* **R3. Python detection incomplete** in dynamic code: >80% in sufficiently dynamic
  projects. Mitigate: warnings in every Python result; never claim reflective coverage.
  The token economy holds regardless.
* **R4. Stale VFS** — near-certain without mitigation and the top source of wrong results
  if unaddressed; hence §3.4 and its dedicated suite, with sync latency budgeted
  separately so it can't be hidden.
* **R5. Unpredicted file changes** (30–50%, mostly framework files): full after-capture,
  `expected: false` flagging, unreported change = test failure.
* **R6. Upstream ships equivalent tooling** (50–70% within 12 months). Accepted: the
  project is sized so that personal utility plus Rust/plugin learning already repays it;
  if upstream wins, adopt upstream.
* **R7. Test-framework setup burns days.** High. Budgeted explicitly in the schedule.

## 3.12 Delivery plan (~12 focused days)

* **Days 1–3:** plugin skeleton, loopback server + descriptor + auth, project status,
  sync + commit + dumb-mode wait, resolve with guards. First ugly threading discoveries
  happen here — good.
* **Days 4–5:** `usages` end to end, classification, caps, payload shape. First usable
  artifact.
* **Days 6–9:** rename adapters (Java → Python → Kotlin, easiest first), mutation
  pipeline, Local History label, result derivation (`renamedPaths`, regions, diffs),
  apply-or-report gate.
* **Days 10–12:** test matrix, sync suite, negative tests, benchmark, docs
  (`refactor --help` written for agents, sample `CLAUDE.md` stanza).
* **Whenever a Kotlin break is needed:** the Rust CLI, in parallel — it depends only on
  the frozen JSON schema.

Schedule risk concentrates in K2 rename edge cases and test-framework setup, not code
volume.

---

# 4. Success Criteria

1. `usages` and `rename` work for every row of the §2.3 matrix on the committed fixtures;
   the test matrix is fully green — scope shrinks by deleting rows and documenting them as
   unsupported, never by shipping red tests.
2. An agent that writes files and immediately invokes the tool gets a correct result or a
   structured failure — never output computed from stale PSI.
3. No test produces an unreported change or an unreported partial rename.
4. The default 200-usage rename payload fits the token budget.
5. The benchmark hits <5% tokens vs. the patch baseline and <20% vs. the JVM compile-loop
   baseline.
6. The author reaches for `refactor rename` instead of asking the agent to patch — the
   only adoption metric a personal tool needs.

---

# 5. Future Extensions (explicitly deferred)

* **Safe delete** — cheap once `usages` exists (same Find Usages foundation); a
  conservative applies-only-when-zero-blocking-usages version is a few days.
* **Move** — top-level declaration to package/file/module. The Java processor is
  long-standing and drivable; Kotlin's K2 move is substantially internal API and needs a
  feasibility spike before any commitment.
* **MCP mode** — a thin wrapper over the same protocol, only if a GUI client that cannot
  shell out becomes part of the workflow.
* **Headless backend** — the remote-development backend is a headless IDE; the CLI↔plugin
  protocol boundary is chosen so the plugin could run there unchanged, enabling CI use.
* **Exact pre-application diff** — execute-capture-revert inside a command; viable only if
  revert proves perfectly reliable, and never worth blocking v1 on.
