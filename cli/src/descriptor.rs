use std::{
    env, fs,
    path::{Path, PathBuf},
    time::Duration,
};

use serde::Deserialize;
use serde_json::{Value, json};

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

        let mut candidates = Vec::new();
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
            if descriptor.protocol_version == 1 {
                candidates.push((entry.path(), descriptor));
            }
        }

        let mut matches = Vec::new();
        for chunk in candidates.chunks(16) {
            let checked = std::thread::scope(|scope| {
                chunk
                    .iter()
                    .map(|(path, descriptor)| {
                        scope.spawn(move || {
                            (
                                path.clone(),
                                descriptor.clone(),
                                descriptor_is_live(descriptor),
                            )
                        })
                    })
                    .collect::<Vec<_>>()
                    .into_iter()
                    .filter_map(|handle| handle.join().ok())
                    .collect::<Vec<_>>()
            });
            for (path, descriptor, live) in checked {
                if !live {
                    let _ = fs::remove_file(path);
                } else if descriptor
                    .projects
                    .iter()
                    .any(|candidate| same_path(candidate, project))
                {
                    matches.push(descriptor);
                }
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

fn descriptor_is_live(descriptor: &Descriptor) -> bool {
    descriptor_probe(descriptor)
        .ok()
        .and_then(|body| body.get("result")?.get("protocolVersion")?.as_u64())
        == Some(u64::from(descriptor.protocol_version))
}

fn descriptor_probe(descriptor: &Descriptor) -> Result<Value, ureq::Error> {
    let config = ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_millis(300)))
        .timeout_global(Some(Duration::from_secs(1)))
        .max_redirects(0)
        .build();
    let agent: ureq::Agent = config.into();
    let project = descriptor
        .projects
        .first()
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_default();
    agent
        .post(format!("http://127.0.0.1:{}/rpc", descriptor.port))
        .header("Authorization", &format!("Bearer {}", descriptor.token))
        .send_json(json!({
            "jsonrpc": "2.0",
            "id": 0,
            "method": "status",
            "params": {
                "project": project
            },
        }))
        .and_then(|mut response| response.body_mut().read_json::<Value>())
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
    use std::{
        io::{Read, Write},
        net::TcpListener,
        path::{Path, PathBuf},
        thread,
    };

    use super::{Descriptor, descriptor_is_live, same_path};

    #[test]
    fn identical_paths_match() {
        assert!(same_path(Path::new("."), Path::new(".")));
    }

    #[test]
    fn liveness_probe_authenticates_the_exact_plugin() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test server");
        let port = listener.local_addr().expect("test address").port();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept probe");
            let mut request = Vec::new();
            loop {
                let mut chunk = [0_u8; 1024];
                let bytes = stream.read(&mut chunk).expect("read probe");
                assert!(bytes > 0, "probe closed before sending its request body");
                request.extend_from_slice(&chunk[..bytes]);
                let Some(header_end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n")
                else {
                    continue;
                };
                let headers = String::from_utf8_lossy(&request[..header_end]);
                let content_length = headers
                    .lines()
                    .find_map(|line| {
                        line.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .and_then(|value| value.trim().parse::<usize>().ok())
                    })
                    .unwrap_or(0);
                if request.len() >= header_end + 4 + content_length {
                    break;
                }
            }
            let request = String::from_utf8_lossy(&request);
            assert!(
                request
                    .to_ascii_lowercase()
                    .contains("authorization: bearer test-token")
            );
            let body = r#"{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":1}}"#;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len(),
            )
            .expect("write probe response");
            stream.flush().expect("flush probe response");
        });
        let descriptor = Descriptor {
            protocol_version: 1,
            ide_pid: std::process::id(),
            ide_build: "IU-252.test".to_owned(),
            port,
            token: "test-token".to_owned(),
            projects: vec![PathBuf::from(".")],
        };

        assert!(descriptor_is_live(&descriptor));
        server.join().expect("join test server");
    }
}
