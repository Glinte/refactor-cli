use std::{
    env, fs,
    path::{Path, PathBuf},
};

use serde::Deserialize;

use crate::error::{AppError, AppResult};

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct Descriptor {
    pub protocol_version: u32,
    pub ide_pid: u32,
    pub ide_build: String,
    pub port: u16,
    pub token: String,
    pub projects: Vec<PathBuf>,
}

impl Descriptor {
    pub fn discover(project: &Path) -> AppResult<Self> {
        let directory = descriptor_directory()?;
        let entries = fs::read_dir(&directory).map_err(|error| {
            AppError::environment(
                "IDE_NOT_RUNNING",
                format!(
                    "cannot read descriptor directory {}: {error}",
                    directory.display()
                ),
            )
        })?;

        let mut matches = Vec::new();
        for entry in entries.flatten() {
            if entry.path().extension().and_then(|value| value.to_str()) != Some("json") {
                continue;
            }
            let Ok(bytes) = fs::read(entry.path()) else {
                continue;
            };
            let Ok(descriptor) = serde_json::from_slice::<Self>(&bytes) else {
                continue;
            };
            if descriptor.protocol_version == 1
                && descriptor
                    .projects
                    .iter()
                    .any(|candidate| same_path(candidate, project))
            {
                matches.push(descriptor);
            }
        }

        match matches.len() {
            0 => Err(AppError::environment(
                "IDE_NOT_RUNNING",
                format!(
                    "no running IntelliJ refactor plugin advertises {}",
                    project.display()
                ),
            )),
            1 => Ok(matches.remove(0)),
            _ => Err(AppError::environment(
                "PROJECT_BUSY",
                format!(
                    "multiple IntelliJ instances advertise {}; close all but one",
                    project.display()
                ),
            )),
        }
    }
}

fn descriptor_directory() -> AppResult<PathBuf> {
    if let Some(path) = env::var_os("REFACTOR_AGENT_HOME") {
        return Ok(PathBuf::from(path));
    }

    env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .map(PathBuf::from)
        .map(|path| path.join(".refactor-agent"))
        .ok_or_else(|| {
            AppError::environment(
                "IDE_NOT_RUNNING",
                "cannot locate the user home directory for plugin discovery",
            )
        })
}

fn same_path(left: &Path, right: &Path) -> bool {
    let left = left.canonicalize().unwrap_or_else(|_| left.to_path_buf());
    let right = right.canonicalize().unwrap_or_else(|_| right.to_path_buf());

    if cfg!(windows) {
        left.to_string_lossy()
            .eq_ignore_ascii_case(&right.to_string_lossy())
    } else {
        left == right
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::same_path;

    #[test]
    fn identical_paths_match() {
        assert!(same_path(Path::new("."), Path::new(".")));
    }
}
