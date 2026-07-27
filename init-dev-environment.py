"""Interactively check the development prerequisites for this repository.

This script deliberately does not install software or change the repository. When a
requirement is missing, it explains what the user needs to do and lets them recheck,
skip it, or quit.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent


@dataclass(frozen=True)
class Requirement:
    name: str
    check: Callable[[], tuple[bool, str]]
    instructions: str


def command_output(*command: str) -> tuple[bool, str]:
    """Run a read-only version command and return its first output line."""
    executable = shutil.which(command[0])
    if executable is None:
        return False, f"`{command[0]}` was not found on PATH"

    try:
        result = subprocess.run(
            (executable, *command[1:]),
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, str(error)

    output = (result.stdout or result.stderr).strip().splitlines()
    detail = output[0] if output else f"exited with status {result.returncode}"
    return result.returncode == 0, detail


def python_check() -> tuple[bool, str]:
    version = sys.version_info
    detail = f"Python {version.major}.{version.minor}.{version.micro}"
    return version >= (3, 10), detail


def java_major_version(detail: str) -> int | None:
    match = re.search(r'version "(?:1\.)?(\d+)', detail)
    return int(match.group(1)) if match is not None else None


def check_java_runtime() -> tuple[bool, str]:
    ok, detail = command_output("java", "-version")
    if ok and (java_major_version(detail) or 0) >= 25:
        return True, detail

    executable = "java.exe" if sys.platform == "win32" else "java"
    toolchain_root = Path.home() / ".gradle" / "jdks"
    for candidate in sorted(toolchain_root.glob(f"*/bin/{executable}")):
        candidate_ok, candidate_detail = command_output(str(candidate), "-version")
        if candidate_ok and (java_major_version(candidate_detail) or 0) >= 25:
            return True, f"{candidate_detail} (Gradle toolchain)"

    if not ok:
        return False, detail
    return False, f"JDK 25+ was not found; PATH reports {detail}"


def virtual_environment_check() -> tuple[bool, str]:
    if sys.platform == "win32":
        python = ROOT / ".venv" / "Scripts" / "python.exe"
    else:
        python = ROOT / ".venv" / "bin" / "python"
    return python.is_file(), str(python.relative_to(ROOT))


def prek_check() -> tuple[bool, str]:
    ok, detail = command_output("uv", "run", "--locked", "prek", "list", "--output-format", "json")
    if not ok:
        return False, detail

    missing: list[str] = []
    for hook_type in ("pre-commit", "pre-push"):
        ok, hook_path = command_output("git", "rev-parse", "--git-path", f"hooks/{hook_type}")
        if not ok:
            return False, hook_path

        path = Path(hook_path)
        if not path.is_absolute():
            path = ROOT / path
        if not path.is_file():
            missing.append(hook_type)

    if missing:
        return False, f"missing installed Git hooks: {', '.join(missing)}"
    return True, "prek configuration and Git hooks are ready"


def requirements() -> tuple[Requirement, ...]:
    return (
        Requirement(
            "Python 3.10 or newer",
            python_check,
            "Install Python 3.10+ from https://www.python.org/downloads/, then rerun this script.",
        ),
        Requirement(
            "Git",
            lambda: command_output("git", "--version"),
            "Install Git from https://git-scm.com/downloads and ensure `git` is on PATH.",
        ),
        Requirement(
            "uv",
            lambda: command_output("uv", "--version"),
            "Install uv by following https://docs.astral.sh/uv/getting-started/installation/.",
        ),
        Requirement(
            "just",
            lambda: command_output("just", "--version"),
            "Install just by following https://just.systems/man/en/packages.html.",
        ),
        Requirement(
            "Rust toolchain",
            lambda: command_output("cargo", "--version"),
            "Install Rust with rustup from https://rustup.rs/ and ensure `cargo` is on PATH.",
        ),
        Requirement(
            "JDK 25 or newer",
            check_java_runtime,
            "Install JDK 25+ and ensure `java` is on PATH. Temurin builds are available at https://adoptium.net/.",
        ),
        Requirement(
            "Python development environment",
            virtual_environment_check,
            "From the repository root, run `uv sync --locked`.",
        ),
        Requirement(
            "prek hook configuration",
            prek_check,
            "From the repository root, run `uv run --locked prek install`.",
        ),
    )


def prompt_for_missing(requirement: Requirement) -> bool:
    print(f"  {requirement.instructions}")

    if not sys.stdin.isatty():
        return False

    while True:
        choice = input("  Press Enter to recheck, [s] to skip, or [q] to quit: ").strip().lower()
        if choice in {"", "r"}:
            ok, detail = requirement.check()
            if ok:
                print(f"  OK: {detail}")
                return True
            print(f"  Still missing: {detail}")
            print(f"  {requirement.instructions}")
        elif choice == "s":
            return False
        elif choice == "q":
            raise KeyboardInterrupt
        else:
            print("  Enter, s, and q are the available choices.")


def main() -> int:
    print("refactor-cli development environment\n")
    missing: list[str] = []
    steps = requirements()

    try:
        for number, requirement in enumerate(steps, start=1):
            print(f"[{number}/{len(steps)}] {requirement.name}")
            ok, detail = requirement.check()
            if ok:
                print(f"  OK: {detail}\n")
                continue

            print(f"  Missing: {detail}")
            if not prompt_for_missing(requirement):
                missing.append(requirement.name)
            print()
    except (EOFError, KeyboardInterrupt):
        print("\nSetup stopped. No changes were made.")
        return 1

    if missing:
        print("Setup is incomplete. Still needed:")
        for name in missing:
            print(f"  - {name}")
        print("\nNo changes were made. Rerun this script after completing those steps.")
        return 1

    print("Everything is ready. No changes were needed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
