//! Discord's IPC framing: a little-endian uint32 opcode, a uint32 length, then
//! that many bytes of JSON.

use serde_json::Value;

pub const OP_HANDSHAKE: u32 = 0;
pub const OP_FRAME: u32 = 1;
pub const OP_CLOSE: u32 = 2;
pub const OP_PING: u32 = 3;
pub const OP_PONG: u32 = 4;

/// Discord's own messages stay far below this; anything larger is an abuse of
/// the socket.
pub const MAX_PAYLOAD: usize = 64 * 1024;

#[derive(Clone, Debug, PartialEq)]
pub struct Frame {
    pub op: u32,
    pub json: Value,
}

impl Frame {
    pub fn new(op: u32, json: Value) -> Self {
        Self { op, json }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FrameError {
    UnknownOpcode(u32),
    TooLarge(u32),
    NotJson,
}

impl FrameError {
    /// The text Discord-style servers put in a CLOSE frame.
    pub fn message(&self) -> String {
        match self {
            Self::UnknownOpcode(op) => format!("unknown opcode {op}"),
            Self::TooLarge(len) => format!("frame of {len} bytes"),
            Self::NotJson => "frame is not JSON".to_string(),
        }
    }
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message())
    }
}

impl std::error::Error for FrameError {}

pub fn encode_frame(op: u32, payload: &Value) -> Vec<u8> {
    let body = serde_json::to_vec(payload).unwrap_or_else(|_| b"null".to_vec());
    let mut out = Vec::with_capacity(8 + body.len());
    out.extend_from_slice(&op.to_le_bytes());
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&body);
    out
}

/// Collects bytes from a stream and hands back whole frames as they complete.
#[derive(Default)]
pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<Frame>, FrameError> {
        if !chunk.is_empty() {
            self.buf.extend_from_slice(chunk);
        }
        let mut out = Vec::new();
        let mut used = 0usize;
        while self.buf.len() - used >= 8 {
            let op = u32::from_le_bytes(self.buf[used..used + 4].try_into().unwrap());
            let len = u32::from_le_bytes(self.buf[used + 4..used + 8].try_into().unwrap());
            if op > OP_PONG {
                return Err(FrameError::UnknownOpcode(op));
            }
            if len as usize > MAX_PAYLOAD {
                return Err(FrameError::TooLarge(len));
            }
            let end = used + 8 + len as usize;
            if self.buf.len() < end {
                break;
            }
            let json = if len == 0 {
                Value::Null
            } else {
                serde_json::from_slice(&self.buf[used + 8..end]).map_err(|_| FrameError::NotJson)?
            };
            out.push(Frame { op, json });
            used = end;
        }
        if used > 0 {
            self.buf.drain(..used);
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_survive_being_split_anywhere() {
        let raw = encode_frame(OP_FRAME, &serde_json::json!({ "cmd": "SET_ACTIVITY" }));
        for cut in 0..raw.len() {
            let mut decoder = FrameDecoder::new();
            assert!(decoder.push(&raw[..cut]).unwrap().is_empty());
            let frames = decoder.push(&raw[cut..]).unwrap();
            assert_eq!(frames.len(), 1);
            assert_eq!(frames[0].op, OP_FRAME);
            assert_eq!(frames[0].json["cmd"], "SET_ACTIVITY");
        }
    }

    #[test]
    fn refuses_an_unknown_opcode_and_an_oversized_frame() {
        let mut decoder = FrameDecoder::new();
        let mut raw = Vec::new();
        raw.extend_from_slice(&9u32.to_le_bytes());
        raw.extend_from_slice(&0u32.to_le_bytes());
        assert_eq!(decoder.push(&raw), Err(FrameError::UnknownOpcode(9)));

        let mut decoder = FrameDecoder::new();
        let mut raw = Vec::new();
        raw.extend_from_slice(&OP_FRAME.to_le_bytes());
        raw.extend_from_slice(&((MAX_PAYLOAD + 1) as u32).to_le_bytes());
        assert_eq!(
            decoder.push(&raw),
            Err(FrameError::TooLarge((MAX_PAYLOAD + 1) as u32))
        );
    }
}
