#!/usr/bin/env just --justfile
set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

default:
  just --list

check:
  just cli-check
  just docs-check
  just plugin-check

cli-check:
  cargo fmt --manifest-path cli/Cargo.toml --check
  cargo clippy --manifest-path cli/Cargo.toml --all-targets -- -D warnings
  cargo test --manifest-path cli/Cargo.toml

cli-build:
  cargo build --manifest-path cli/Cargo.toml

cli-release:
  cargo build --release --manifest-path cli/Cargo.toml

docs-check:
  uv run --group docs ruff check .
  uv run --group docs ruff format --check .
  uv run --group docs pyrefly check
  uv run --group docs python -m unittest discover -s benchmarks -p "test_*.py"
  uv run --group docs zensical build --clean --strict

plugin-check:
  plugin\gradlew.bat -p plugin check

plugin-run:
  plugin\gradlew.bat -p plugin runIde

plugin-build:
  plugin\gradlew.bat -p plugin buildPlugin

plugin-verify:
  plugin\gradlew.bat -p plugin verifyPluginProjectConfiguration verifyPlugin

release:
  just check
  just cli-release
  just plugin-build
  just plugin-verify
