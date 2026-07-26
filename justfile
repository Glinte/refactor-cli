#!/usr/bin/env just --justfile
set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

default:
  just --list

cli-check:
  cargo test --manifest-path cli/Cargo.toml

cli-build:
  cargo build --manifest-path cli/Cargo.toml

plugin-check:
  plugin\gradlew.bat -p plugin check

plugin-run:
  plugin\gradlew.bat -p plugin runIde

plugin-build:
  plugin\gradlew.bat -p plugin buildPlugin
