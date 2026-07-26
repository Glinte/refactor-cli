use serde_json::{Value, json};

use crate::protocol::RpcError;

pub type AppResult<T> = Result<T, AppError>;

#[derive(Debug)]
pub struct AppError {
    code: String,
    message: String,
    exit_code: u8,
    details: Option<Value>,
}

impl AppError {
    pub fn user(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(code, message, 3)
    }

    pub fn environment(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(code, message, 4)
    }

    pub fn internal(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(code, message, 5)
    }

    fn new(code: impl Into<String>, message: impl Into<String>, exit_code: u8) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            exit_code,
            details: None,
        }
    }

    pub fn from_rpc(error: RpcError) -> Self {
        let symbolic_code = error
            .data
            .as_ref()
            .and_then(|data| data.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("INTERNAL_ERROR")
            .to_owned();
        let exit_code = error
            .data
            .as_ref()
            .and_then(|data| data.get("exitCode"))
            .and_then(Value::as_u64)
            .and_then(|value| u8::try_from(value).ok())
            .unwrap_or(5);

        Self {
            code: symbolic_code,
            message: error.message,
            exit_code,
            details: error.data,
        }
    }

    pub fn exit_code(&self) -> u8 {
        self.exit_code
    }

    pub fn as_json(&self) -> Value {
        let mut error = json!({
            "code": self.code,
            "message": self.message,
        });
        if let (Value::Object(object), Some(Value::Object(details))) = (&mut error, &self.details) {
            for (key, value) in details {
                if key != "code" && key != "exitCode" {
                    object.insert(key.clone(), value.clone());
                }
            }
        }
        error
    }
}
