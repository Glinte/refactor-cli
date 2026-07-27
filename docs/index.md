# refactor-cli

`refactor-cli` invokes IntelliJ IDEA's semantic Find Usages and rename machinery from a
terminal. It is intended for coding agents that would otherwise spend time and context on
grep, textual patches, and compile-fix loops.

## Quick start

Keep the project open in IntelliJ IDEA with the refactor-cli plugin installed, then run:

```console
refactor status
refactor usages --symbol com.example.UserService --expect UserService:CLASS
refactor rename --symbol com.example.UserService \
  --expect UserService:CLASS --to AccountService
```

For local declarations and Python, select by one-based source position:

```console
refactor rename --file src/service.py --line 18 --col 9 \
  --expect load_user:FUNCTION --to load_account
```

The command prints JSON only. Exit `0` means success, `2` means the operation needs review,
`3` is a user error, `4` is an IDE/workspace error, and `5` is an internal or rollback
failure.

## Safety model

Each operation refreshes IntelliJ's VFS before reading PSI. A rename then resolves,
analyzes, checks conflicts and dirty buffers, and mutates under one per-project lock.
Semantic non-source references require `--force-non-source`; conflicts never auto-apply.
Applied changes receive a Local History label and include explicit changed-file and
old-path/new-path reports.

Use `--expect NAME[:KIND]` for automated position selectors. Pass externally edited files
with `--touched PATH`; omitting the hint remains correct because the plugin performs a full
content-root refresh.

## Limitations

The supported languages are Java, Kotlin in K2 mode, and Python with the Python plugin
installed. Dynamic Python references, reflection, and arbitrary strings cannot be
discovered reliably. The tool does not perform textual replacement, package moves, safe
delete, or headless/CI refactoring.

See the repository [README](https://github.com/Glinte/refactor-cli#readme) for installation
and complete command examples.

The [release validation](validation.md) page maps product criteria to current evidence and
clearly identifies the live-agent measurements that cannot be inferred from automated
tests.
