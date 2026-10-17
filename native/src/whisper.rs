//! Local speech-to-text with whisper.cpp (through `whisper-rs`). Everything runs on the device; the only thing it
//! needs is a ggml model file that the app downloads once or the user imports.

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::Arc;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

pub struct Segment {
    /// Start and end in hundredths of a second.
    pub t0: i64,
    pub t1: i64,
    pub text: String,
}

pub struct Handle {
    ctx: WhisperContext,
    cancel: Arc<AtomicBool>,
    progress: Arc<AtomicI32>,
}

pub fn load(path: &str) -> Result<Handle, String> {
    let ctx = WhisperContext::new_with_params(path, WhisperContextParameters::default()).map_err(|e| e.to_string())?;
    Ok(Handle { ctx, cancel: Arc::new(AtomicBool::new(false)), progress: Arc::new(AtomicI32::new(0)) })
}

impl Handle {
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    pub fn progress(&self) -> i32 {
        self.progress.load(Ordering::Relaxed)
    }

    /// Transcribes 16 kHz mono `pcm`. `language` is an ISO code ("pt", "en"…) or "auto"; `max_chars` splits long
    /// sentences into caption-sized segments (0 = whisper's own segmentation).
    pub fn transcribe(&self, pcm: &[f32], language: &str, translate: bool, threads: i32, max_chars: i32) -> Result<Vec<Segment>, String> {
        self.cancel.store(false, Ordering::Relaxed);
        self.progress.store(0, Ordering::Relaxed);
        let mut state = self.ctx.create_state().map_err(|e| e.to_string())?;
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_n_threads(threads.max(1));
        params.set_translate(translate);
        params.set_language(if language.is_empty() { Some("auto") } else { Some(language) });
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_suppress_blank(true);
        params.set_suppress_nst(true);
        params.set_no_context(true);
        if max_chars > 0 {
            params.set_token_timestamps(true);
            params.set_max_len(max_chars);
            params.set_split_on_word(true);
        }
        let progress = self.progress.clone();
        params.set_progress_callback_safe(move |p: i32| progress.store(p, Ordering::Relaxed));
        // The flag outlives the call: `self` holds the Arc and `transcribe` borrows `self`.
        unsafe extern "C" fn aborted(user_data: *mut c_void) -> bool {
            (*(user_data as *const AtomicBool)).load(Ordering::Relaxed)
        }
        unsafe {
            params.set_abort_callback(Some(aborted));
            params.set_abort_callback_user_data(Arc::as_ptr(&self.cancel) as *mut c_void);
        }

        state.full(params, pcm).map_err(|e| e.to_string())?;
        if self.cancel.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        self.progress.store(100, Ordering::Relaxed);
        let n = state.full_n_segments().map_err(|e| e.to_string())?;
        let mut out = Vec::with_capacity(n as usize);
        for i in 0..n {
            let text = state.full_get_segment_text(i).map_err(|e| e.to_string())?;
            let t = text.trim();
            if t.is_empty() || (t.starts_with('[') && t.ends_with(']')) || (t.starts_with('(') && t.ends_with(')')) {
                continue; // [MUSIC], (applause)… are not speech
            }
            out.push(Segment {
                t0: state.full_get_segment_t0(i).map_err(|e| e.to_string())?,
                t1: state.full_get_segment_t1(i).map_err(|e| e.to_string())?,
                text: t.to_string(),
            });
        }
        Ok(out)
    }
}
