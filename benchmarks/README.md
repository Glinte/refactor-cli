# Live-agent benchmark

This harness measures the release criterion from the product design: the same coding agent
renames a Java type with 10, 100, and 500 semantic usages using:

1. a naive textual patch loop;
2. an edit/build/fix loop;
3. `refactor rename`.

The benchmark deliberately requires provider-reported token counts. File size, diff size,
and character-based token estimates are not substitutes.

## Prepare fixtures

```console
python benchmarks/generate_fixture.py ../refactor-cli-benchmark
```

Open each generated Maven project in the pinned IntelliJ IDEA 2026.2 release and let
indexing finish. Initialize each as a Git repository and commit the generated state.

## Run each workflow

Use the same model, context policy, system instructions, and fresh conversation for every
measurement. Start each run from a clean clone or worktree. Give the agent this task:

```text
Rename the Java class benchmark.Target to RenamedTarget everywhere, verify the project,
and stop when the rename is complete.
```

For the patch workflow, make `refactor` unavailable and instruct the agent to use textual
patches. For the compile workflow, make `refactor` unavailable and instruct it to use an
edit/build/fix loop. For the CLI workflow, keep this repository's standard `AGENTS.md`
instruction and the plugin active. Do not mention the expected token thresholds inside
the task.

Record provider-reported input/output tokens and elapsed wall time in a JSONL file:

```json
{"usages":10,"workflow":"patch","inputTokens":1,"outputTokens":1,"wallMs":1}
{"usages":10,"workflow":"compile","inputTokens":1,"outputTokens":1,"wallMs":1}
{"usages":10,"workflow":"cli","inputTokens":1,"outputTokens":1,"wallMs":1}
```

Repeat those three rows for 100 and 500 usages, replacing the sample values with
measurements. Analyze the nine rows with:

```console
python benchmarks/analyze.py results.jsonl
```

The analyzer exits zero only when every fixture uses less than 5% of the patch workflow's
tokens and less than 20% of the compile-loop workflow's tokens.
