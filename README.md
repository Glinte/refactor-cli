# refactor-cli

> [!NOTE]
> This is AI generated use at your own risk. Doesn't look risky but the CLI isn't that useful either tbh.

`refactor-cli` gives terminal tools and coding agents access to IntelliJ IDEA's semantic
rename and Find Usages engines. It supports Java, Kotlin (K2), and Python without falling
back to textual search-and-replace.

The project consists of a small Rust executable and an IntelliJ plugin. The plugin keeps
all PSI/index/refactoring logic inside the IDE; the executable discovers the open project,
sends one authenticated request over loopback, prints JSON, and exits with a stable code.

## Requirements

- IntelliJ IDEA 2026.2 with the project open and indexing complete.
- The bundled Java and Kotlin plugins. Python operations also require Python support.
- Rust stable to build the CLI and JDK 25 to build the plugin.

## Build and install

```console
cargo build --release --manifest-path cli/Cargo.toml
plugin/gradlew -p plugin buildPlugin
```

On Windows, run `plugin\gradlew.bat -p plugin buildPlugin`. Install the ZIP produced under
`plugin/build/distributions/` using **Settings → Plugins → Install Plugin from Disk**, then
restart IDEA. Put `cli/target/release/refactor` (or `refactor.exe`) on `PATH`.

The plugin listens only on `127.0.0.1`, creates a random 256-bit token on each IDE start,
and writes a user-private descriptor under `~/.refactor-agent/`. The CLI validates that
descriptor with an authenticated status request before using it.

## Use

Every command emits JSON. `--project` defaults to the nearest Git root.

```console
refactor status
refactor sync --touched src/main/java/com/example/User.java
refactor resolve --symbol com.example.User --expect User:CLASS
refactor usages --symbol 'com.example.User#greet(Ljava/lang/String;)V' --max 200
refactor rename --symbol com.example.User --expect User:CLASS --to Account
refactor rename --file example/model.py --line 12 --col 7 \
  --expect User:CLASS --to Account --diff inline
```

Use qualified symbols for Java/Kotlin declarations. Append `#member` and a JVM descriptor
when an overload is ambiguous. Use a one-based file/line/UTF-16-column position for local
symbols and most Python declarations. Always add `--expect NAME[:KIND]` in automated
workflows so a stale position fails instead of targeting a nearby symbol.

Rename is apply-or-report:

- exit `0`: query succeeded or rename was applied;
- exit `2`: dry run, conflict, or non-source reference needs review;
- exit `3`: invalid selector/name or another user error;
- exit `4`: IDE, indexing, dirty-buffer, sync, or filesystem problem;
- exit `5`: refactoring/rollback or internal failure.

Non-source semantic references are never silently skipped. If IntelliJ reports one, the
first call exits `2`; review the affected files and rerun with `--force-non-source`.
Unsaved affected editor buffers block mutation. Each applied rename is labeled in Local
History and reports every observed changed file, file rename, and changed line region.
Diffs are opt-in: `--diff inline` is capped at 64 KiB and `--diff file` writes the full
patch to a temporary file.

Python results always warn that dynamic/string-encoded references cannot be proven
complete. Comments, arbitrary strings, package/directory moves, and headless use are
intentionally out of scope.

## Agent project instruction

Add a short rule like this to `AGENTS.md` or `CLAUDE.md`:

```text
For Java, Kotlin, or Python symbol usages and renames, prefer `refactor` over grep or
textual patches. Keep the project open in IntelliJ, pass `--expect NAME:KIND`, and pass
recently edited paths with `--touched`. Treat exit 2 as review-required and inspect the
JSON before rerunning with `--force-non-source`.
```

## Development

```console
python init-dev-environment.py
just check
just release
```

The [product design](product-design-document.md) defines the protocol, safety model, and
acceptance matrix. The shared wire contract is
[`schema/protocol-v1.schema.json`](schema/protocol-v1.schema.json).

## Benchmark

The release benchmark uses generated 10/100/500-usage Java fixtures and compares the same
agent using textual patches, an edit/build/fix loop, and this CLI. Generate fixtures and
validate provider-reported measurements with the scripts documented in
[`benchmarks/README.md`](benchmarks/README.md). No byte-derived or fabricated token result
is accepted.
