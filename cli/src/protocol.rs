use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Serialize)]
pub struct RpcRequest {
    jsonrpc: &'static str,
    id: u64,
    method: &'static str,
    params: Value,
}

impl RpcRequest {
    pub fn new(method: &'static str, params: Value) -> Self {
        Self {
            jsonrpc: "2.0",
            id: 1,
            method,
            params,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct RpcResponse {
    pub result: Option<Value>,
    pub error: Option<RpcError>,
}

#[derive(Debug, Deserialize)]
pub struct RpcError {
    #[allow(dead_code)]
    pub code: i32,
    pub message: String,
    pub data: Option<Value>,
}
