"""Validate and summarize measured refactor-cli benchmark results."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

USAGE_COUNTS = (10, 100, 500)
WORKFLOWS = ("patch", "compile", "cli")


@dataclass(frozen=True)
class Measurement:
    usages: int
    workflow: str
    input_tokens: int
    output_tokens: int
    wall_ms: int

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens


def positive_integer(row: dict[str, object], key: str, line: int) -> int:
    value = row.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"line {line}: {key} must be a positive integer")
    return value


def load(path: Path) -> dict[tuple[int, str], Measurement]:
    measurements: dict[tuple[int, str], Measurement] = {}
    for line_number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not text.strip():
            continue
        row = json.loads(text)
        if not isinstance(row, dict):
            raise ValueError(f"line {line_number}: expected a JSON object")
        usages = positive_integer(row, "usages", line_number)
        workflow = row.get("workflow")
        if usages not in USAGE_COUNTS or workflow not in WORKFLOWS:
            raise ValueError(
                f"line {line_number}: usages must be one of {USAGE_COUNTS} and workflow one of {WORKFLOWS}",
            )
        measurement = Measurement(
            usages=usages,
            workflow=str(workflow),
            input_tokens=positive_integer(row, "inputTokens", line_number),
            output_tokens=positive_integer(row, "outputTokens", line_number),
            wall_ms=positive_integer(row, "wallMs", line_number),
        )
        key = (usages, measurement.workflow)
        if key in measurements:
            raise ValueError(f"line {line_number}: duplicate measurement for {key}")
        measurements[key] = measurement

    expected = {(usages, workflow) for usages in USAGE_COUNTS for workflow in WORKFLOWS}
    missing = sorted(expected - measurements.keys())
    if missing:
        raise ValueError(f"missing measurements: {missing}")
    return measurements


def percentage(numerator: int, denominator: int) -> float:
    return numerator / denominator * 100


def meets_thresholds(measurements: dict[tuple[int, str], Measurement]) -> bool:
    return all(
        percentage(
            measurements[(usages, "cli")].total_tokens,
            measurements[(usages, "patch")].total_tokens,
        )
        < 5
        and percentage(
            measurements[(usages, "cli")].total_tokens,
            measurements[(usages, "compile")].total_tokens,
        )
        < 20
        for usages in USAGE_COUNTS
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path, help="Nine-row JSONL measurement file.")
    args = parser.parse_args()
    measurements = load(args.results)

    print("| Usages | Patch tokens | Compile tokens | CLI tokens | CLI / patch | CLI / compile |")
    print("| ---: | ---: | ---: | ---: | ---: | ---: |")
    passed = meets_thresholds(measurements)
    for usages in USAGE_COUNTS:
        patch = measurements[(usages, "patch")]
        compile_loop = measurements[(usages, "compile")]
        cli = measurements[(usages, "cli")]
        patch_ratio = percentage(cli.total_tokens, patch.total_tokens)
        compile_ratio = percentage(cli.total_tokens, compile_loop.total_tokens)
        print(
            f"| {usages} | {patch.total_tokens} | {compile_loop.total_tokens} | "
            f"{cli.total_tokens} | {patch_ratio:.2f}% | {compile_ratio:.2f}% |",
        )

    print()
    print("PASS" if passed else "FAIL")
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
