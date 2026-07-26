mod args;
mod client;
mod descriptor;
mod error;
mod protocol;

use std::{env, path::PathBuf, process::ExitCode};

use clap::Parser;
use serde_json::{Map, Value};

use crate::{
    args::{Cli, Command, SelectorArgs},
    client::Client,
    descriptor::Descriptor,
    error::{AppError, AppResult},
};

fn main() -> ExitCode {
    match run() {
        Ok(value) => {
            print_json(&value);
            ExitCode::SUCCESS
        }
        Err(error) => {
            print_json(&error.as_json());
            ExitCode::from(error.exit_code())
        }
    }
}

fn run() -> AppResult<Value> {
    let cli = Cli::parse();
    let project_is_explicit = cli.project.is_some();
    let project = resolve_project(cli.project, project_is_explicit)?;
    let descriptor = Descriptor::discover(&project)?;
    let client = Client::new(descriptor);
    let (method, params) = command_request(cli.command, &project)?;

    client.call(method, Value::Object(params))
}

fn resolve_project(project: Option<PathBuf>, explicit: bool) -> AppResult<PathBuf> {
    let start = match project {
        Some(path) => path,
        None => env::current_dir().map_err(|error| {
            AppError::environment(
                "PROJECT_NOT_FOUND",
                format!("cannot read current directory: {error}"),
            )
        })?,
    };

    let start = start.canonicalize().map_err(|error| {
        AppError::user(
            "PROJECT_NOT_FOUND",
            format!("project path {} is unavailable: {error}", start.display()),
        )
    })?;

    if explicit {
        return Ok(start);
    }

    Ok(start
        .ancestors()
        .find(|candidate| candidate.join(".git").exists())
        .unwrap_or(&start)
        .to_path_buf())
}

fn command_request(
    command: Command,
    project: &std::path::Path,
) -> AppResult<(&'static str, Map<String, Value>)> {
    let mut params = Map::from_iter([(
        "project".to_owned(),
        Value::String(project.to_string_lossy().into_owned()),
    )]);

    let method = match command {
        Command::Status => "status",
        Command::Sync { touched } => {
            params.insert("touched".to_owned(), paths_value(touched));
            "sync"
        }
        Command::Resolve { selector } => {
            add_selector(&mut params, selector)?;
            "resolve"
        }
        Command::Usages { selector, max } => {
            add_selector(&mut params, selector)?;
            params.insert("max".to_owned(), Value::from(max));
            "usages"
        }
        Command::Rename {
            selector,
            to,
            dry_run,
            force_non_source,
            diff,
            touched,
        } => {
            add_selector(&mut params, selector)?;
            params.insert("to".to_owned(), Value::String(to));
            params.insert("dryRun".to_owned(), Value::Bool(dry_run));
            params.insert("forceNonSource".to_owned(), Value::Bool(force_non_source));
            params.insert("diff".to_owned(), Value::String(diff.to_string()));
            params.insert("touched".to_owned(), paths_value(touched));
            "rename"
        }
    };

    Ok((method, params))
}

fn add_selector(params: &mut Map<String, Value>, selector: SelectorArgs) -> AppResult<()> {
    params.insert("selector".to_owned(), selector.into_value()?);
    Ok(())
}

fn paths_value(paths: Vec<PathBuf>) -> Value {
    Value::Array(
        paths
            .into_iter()
            .map(|path| Value::String(path.to_string_lossy().into_owned()))
            .collect(),
    )
}

fn print_json(value: &Value) {
    match serde_json::to_string_pretty(value) {
        Ok(json) => println!("{json}"),
        Err(error) => {
            println!(r#"{{"code":"INTERNAL_ERROR","message":"failed to encode output: {error}"}}"#)
        }
    }
}
