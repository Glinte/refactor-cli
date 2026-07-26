# refactor-cli

## Development

Run [`init-dev-environment.py`](./init-dev-environment.py) and follow the instructions to set up your development environment.

```console
python init-dev-environment.py
```

The IntelliJ plugin is a nested Gradle build. From the repository root:

```console
just plugin-check
just plugin-run
```

> [!HELP]
> Install Python 3.10 or higher if you don't have it already. You can download it from the [official Python website](https://www.python.org/downloads/).
> Inspect your Python version by running `python --version` in your terminal. On Windows, you may need to use `py` instead of `python`.
