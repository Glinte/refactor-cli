# refactor-cli

> [!NOTE]
> This is AI generated use at your own risk. Doesn't look risky but the CLI isn't that useful either tbh.

`refactor-cli` is a thin Rust command-line client backed by an IntelliJ plugin. The CLI
discovers the IDE instance for a project, sends an authenticated JSON-RPC request over
loopback, prints JSON, and maps protocol failures to stable process exit codes.

The repository currently contains the working transport skeleton. `status` is implemented
end to end; `sync`, `resolve`, `usages`, and `rename` have command/protocol seams ready for
their IntelliJ PSI implementations.

## Project layout

- `cli/` — Rust CLI argument parsing, descriptor discovery, transport, and error mapping.
- `plugin/` — IntelliJ plugin server, descriptor lifecycle, and operation router.
- `schema/` — shared JSON Schema for the versioned transport boundary.
- `product-design-document.md` — behavior and architecture specification.

## Development

Run [`init-dev-environment.py`](./init-dev-environment.py) and follow the instructions to set up your development environment.

```console
python init-dev-environment.py
```

The IntelliJ plugin is a nested Gradle build. From the repository root:

```console
just cli-check
just plugin-check
just plugin-run
```

After starting the sandbox IDE and opening a project, query it with:

```console
cargo run --manifest-path cli/Cargo.toml -- status --project .
```

> [!HELP]
> Install Python 3.10 or higher if you don't have it already. You can download it from the [official Python website](https://www.python.org/downloads/).
> Inspect your Python version by running `python --version` in your terminal. On Windows, you may need to use `py` instead of `python`.
