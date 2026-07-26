#!/usr/bin/env just --justfile
set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

default:
  just --list

plugin-check:
  plugin\gradlew.bat -p plugin check

plugin-run:
  plugin\gradlew.bat -p plugin runIde

plugin-build:
  plugin\gradlew.bat -p plugin buildPlugin
