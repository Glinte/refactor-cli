# Semantic refactoring

For Java, Kotlin, or Python symbol usages and renames, prefer the `refactor` CLI over grep
or textual replacement when the project is open in IntelliJ IDEA.

- Pass `--expect NAME:KIND` for automated selectors.
- Pass recently edited files with `--touched PATH`; a full refresh remains the fallback.
- Treat exit code 2 as review-required. Inspect the JSON before rerunning with
  `--force-non-source`.
- Do not replace a failed semantic operation with global search-and-replace.

Run `refactor --help` for selectors and output options.
