//! Native helpers of the Kinesis Editor. The JNI entry points live in `jni_api`; the algorithms are plain Rust.

pub mod extrude;
mod jni_api;
#[cfg(feature = "whisper")]
pub mod whisper;
#[cfg(feature = "stabilize")]
pub mod stab;
