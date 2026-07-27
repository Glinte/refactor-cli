"""Generate deterministic Java projects for the refactor-cli agent benchmark."""

from __future__ import annotations

import argparse
from pathlib import Path

USAGE_COUNTS = (10, 100, 500)
USAGES_PER_FILE = 10

POM = """\
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>benchmark</groupId>
  <artifactId>refactor-cli-benchmark</artifactId>
  <version>1.0-SNAPSHOT</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
</project>
"""

TARGET = """\
package benchmark;

public final class Target {
}
"""


def consumer(file_index: int, usage_count: int) -> str:
    fields = "\n".join(
        f"    private Target value{file_index:03d}_{usage_index:02d};" for usage_index in range(1, usage_count + 1)
    )
    return f"""\
package benchmark;

final class Use{file_index:03d} {{
{fields}
}}
"""


def generate_case(root: Path, usage_count: int) -> None:
    case = root / f"usages-{usage_count}"
    sources = case / "src" / "main" / "java" / "benchmark"
    sources.mkdir(parents=True)
    (case / "pom.xml").write_text(POM, encoding="utf-8")
    (sources / "Target.java").write_text(TARGET, encoding="utf-8")

    remaining = usage_count
    file_index = 1
    while remaining:
        in_file = min(remaining, USAGES_PER_FILE)
        (sources / f"Use{file_index:03d}.java").write_text(
            consumer(file_index, in_file),
            encoding="utf-8",
        )
        remaining -= in_file
        file_index += 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "output",
        type=Path,
        help="New or empty directory that will receive usages-10/100/500 projects.",
    )
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error(f"{output} is not empty")
    output.mkdir(parents=True, exist_ok=True)

    for usage_count in USAGE_COUNTS:
        generate_case(output, usage_count)

    print(f"Generated benchmark fixtures under {output}")


if __name__ == "__main__":
    main()
