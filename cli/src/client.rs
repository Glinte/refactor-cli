use serde_json::Value;

use crate::{
    descriptor::Descriptor,
    error::{AppError, AppResult},
    protocol::{RpcRequest, RpcResponse},
};

pub struct Client {
    descriptor: Descriptor,
}

impl Client {
    pub fn new(descriptor: Descriptor) -> Self {
        Self { descriptor }
    }

    pub fn call(&self, method: &'static str, params: Value) -> AppResult<Value> {
        let url = format!("http://127.0.0.1:{}/rpc", self.descriptor.port);
        let request = RpcRequest::new(method, params);
        let mut response = ureq::post(url)
            .header(
                "Authorization",
                &format!("Bearer {}", self.descriptor.token),
            )
            .send_json(&request)
            .map_err(|error| {
                AppError::environment(
                    "IDE_NOT_RUNNING",
                    format!("cannot reach the IntelliJ refactor plugin: {error}"),
                )
            })?;

        let response: RpcResponse = response.body_mut().read_json().map_err(|error| {
            AppError::internal(
                "INTERNAL_ERROR",
                format!("plugin returned invalid JSON: {error}"),
            )
        })?;

        match (response.result, response.error) {
            (Some(result), None) => Ok(result),
            (None, Some(error)) => Err(AppError::from_rpc(error)),
            _ => Err(AppError::internal(
                "INTERNAL_ERROR",
                "plugin returned an invalid JSON-RPC response",
            )),
        }
    }
}
