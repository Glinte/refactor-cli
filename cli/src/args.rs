use std::{fmt, path::PathBuf};

use clap::{Args, Parser, Subcommand, ValueEnum};
use serde_json::{Map, Value};

use crate::error::{AppError, AppResult};

#[derive(Debug, Parser)]
#[command(
    name = "refactor",
    version,
    about = "IntelliJ-backed semantic refactoring"
)]
pub struct Cli {
    /// IntelliJ project root. Defaults to the nearest Git root.
    #[arg(long, global = true, value_name = "ROOT")]
    pub project: Option<PathBuf>,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Report connected projects and IDE readiness.
    Status,
    /// Refresh files recently changed outside the IDE.
    Sync {
        #[arg(long, value_name = "PATH")]
        touched: Vec<PathBuf>,
    },
    /// Resolve a selector to an IntelliJ symbol.
    Resolve {
        #[command(flatten)]
        selector: SelectorArgs,

        /// Paths recently changed outside the IDE; always refreshed before resolution.
        #[arg(long, value_name = "PATH")]
        touched: Vec<PathBuf>,
    },
    /// Find semantic usages of a symbol.
    Usages {
        #[command(flatten)]
        selector: SelectorArgs,

        #[arg(long, default_value_t = 200, value_parser = clap::value_parser!(u32).range(1..=10_000))]
        max: u32,

        /// Paths recently changed outside the IDE; always refreshed before searching.
        #[arg(long, value_name = "PATH")]
        touched: Vec<PathBuf>,
    },
    /// Rename a symbol through IntelliJ's refactoring engine.
    Rename {
        #[command(flatten)]
        selector: SelectorArgs,

        #[arg(long, value_name = "NEW_NAME")]
        to: String,

        #[arg(long)]
        dry_run: bool,

        #[arg(long)]
        force_non_source: bool,

        #[arg(long, value_enum, default_value_t = DiffMode::None)]
        diff: DiffMode,

        #[arg(long, value_name = "PATH")]
        touched: Vec<PathBuf>,
    },
}

#[derive(Debug, Args)]
pub struct SelectorArgs {
    /// Fully qualified symbol, optionally with #member and a JVM descriptor.
    #[arg(long, value_name = "FQN", conflicts_with_all = ["file", "line", "col"])]
    symbol: Option<String>,

    /// Project-relative source path for a position selector.
    #[arg(long, value_name = "PATH", requires_all = ["line", "col"], conflicts_with = "symbol")]
    file: Option<PathBuf>,

    /// One-based source line.
    #[arg(long, requires = "file", value_parser = clap::value_parser!(u32).range(1..))]
    line: Option<u32>,

    /// One-based UTF-16 source column.
    #[arg(long, requires = "file", value_parser = clap::value_parser!(u32).range(1..))]
    col: Option<u32>,

    /// Expected symbol guard in NAME[:KIND] form.
    #[arg(long, value_name = "NAME[:KIND]")]
    expect: Option<String>,
}

impl SelectorArgs {
    pub fn into_value(self) -> AppResult<Value> {
        let mut selector = Map::new();

        match (self.symbol, self.file, self.line, self.col) {
            (Some(symbol), None, None, None) => {
                selector.insert("symbol".to_owned(), Value::String(symbol));
            }
            (None, Some(file), Some(line), Some(col)) => {
                selector.insert(
                    "file".to_owned(),
                    Value::String(file.to_string_lossy().into_owned()),
                );
                selector.insert("line".to_owned(), Value::from(line));
                selector.insert("col".to_owned(), Value::from(col));
            }
            _ => {
                return Err(AppError::user(
                    "SYMBOL_NOT_FOUND",
                    "provide either --symbol or all of --file, --line, and --col",
                ));
            }
        }

        if let Some(expect) = self.expect {
            selector.insert("expect".to_owned(), Value::String(expect));
        }

        Ok(Value::Object(selector))
    }
}

#[derive(Clone, Copy, Debug, Default, ValueEnum)]
pub enum DiffMode {
    #[default]
    None,
    Inline,
    File,
}

impl fmt::Display for DiffMode {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let value = match self {
            Self::None => "none",
            Self::Inline => "inline",
            Self::File => "file",
        };
        formatter.write_str(value)
    }
}

#[cfg(test)]
mod tests {
    use clap::Parser;

    use super::Cli;

    #[test]
    fn parses_symbol_rename() {
        let cli = Cli::try_parse_from([
            "refactor",
            "rename",
            "--symbol",
            "com.example.User",
            "--expect",
            "User:CLASS",
            "--to",
            "Account",
        ]);

        assert!(cli.is_ok());
    }

    #[test]
    fn position_selector_requires_line_and_column() {
        let cli = Cli::try_parse_from(["refactor", "resolve", "--file", "src/User.java"]);

        assert!(cli.is_err());
    }

    #[test]
    fn parses_touched_hint_for_usages() {
        let cli = Cli::try_parse_from([
            "refactor",
            "usages",
            "--symbol",
            "com.example.User",
            "--touched",
            "src/User.java",
        ]);

        assert!(cli.is_ok());
    }

    #[test]
    fn usages_max_is_bounded_for_response_safety() {
        let cli = Cli::try_parse_from([
            "refactor",
            "usages",
            "--symbol",
            "com.example.User",
            "--max",
            "10001",
        ]);

        assert!(cli.is_err());
    }
}
