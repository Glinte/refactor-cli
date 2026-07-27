from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from benchmarks.analyze import Measurement, load, meets_thresholds
from benchmarks.generate_fixture import USAGE_COUNTS, generate_case


def measurement(usages: int, workflow: str, total_tokens: int) -> Measurement:
    return Measurement(
        usages=usages,
        workflow=workflow,
        input_tokens=total_tokens - 1,
        output_tokens=1,
        wall_ms=1,
    )


class FixtureGeneratorTest(unittest.TestCase):
    def test_generates_exact_usage_counts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for usage_count in USAGE_COUNTS:
                generate_case(root, usage_count)
                case = root / f"usages-{usage_count}"
                sources = sorted(case.rglob("*.java"))
                occurrences = sum(source.read_text(encoding="utf-8").count("private Target ") for source in sources)

                assert usage_count == occurrences
                assert usage_count // 10 + 1 == len(sources)
                assert (case / "pom.xml").is_file()


class AnalyzerTest(unittest.TestCase):
    def complete_measurements(
        self,
        *,
        patch_tokens: int = 1_000,
        compile_tokens: int = 200,
        cli_tokens: int = 39,
    ) -> dict[tuple[int, str], Measurement]:
        result: dict[tuple[int, str], Measurement] = {}
        for usages in USAGE_COUNTS:
            result[(usages, "patch")] = measurement(usages, "patch", patch_tokens)
            result[(usages, "compile")] = measurement(usages, "compile", compile_tokens)
            result[(usages, "cli")] = measurement(usages, "cli", cli_tokens)
        return result

    def test_thresholds_are_strict(self) -> None:
        assert meets_thresholds(self.complete_measurements())
        assert not meets_thresholds(self.complete_measurements(cli_tokens=40))
        assert not meets_thresholds(
            self.complete_measurements(
                patch_tokens=10_000,
                compile_tokens=200,
                cli_tokens=40,
            ),
        )

    def test_load_requires_exactly_one_valid_row_per_case(self) -> None:
        rows = [
            {
                "usages": item.usages,
                "workflow": item.workflow,
                "inputTokens": item.input_tokens,
                "outputTokens": item.output_tokens,
                "wallMs": item.wall_ms,
            }
            for item in self.complete_measurements().values()
        ]

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text(
                "".join(f"{json.dumps(row)}\n" for row in rows),
                encoding="utf-8",
            )
            assert len(load(path)) == 9

            path.write_text(
                "".join(f"{json.dumps(row)}\n" for row in rows[:-1]),
                encoding="utf-8",
            )
            failure = ""
            try:
                load(path)
            except ValueError as error:
                failure = str(error)
            if "missing measurements" not in failure:
                raise AssertionError("missing benchmark measurements were accepted")


if __name__ == "__main__":
    unittest.main()
