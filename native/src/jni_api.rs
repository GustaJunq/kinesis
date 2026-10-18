//! JNI bindings, class `dev.kinesis.core.nativebridge.NativeCore` (see core/src/main/kotlin/.../nativebridge).

use crate::extrude::{self, P};
use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray, JObject, JObjectArray, JString};
#[cfg(feature = "whisper")]
use jni::objects::JLongArray;
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

/// `float[] points` holds every contour back to back as x,y pairs; `int[] counts` the point count of each contour.
/// Returns `Object[]{float[] positions, float[] normals, float[] uvs, int[] indices}`, or null on bad arguments.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_extrudeText<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    points: JFloatArray<'l>,
    counts: JIntArray<'l>,
    depth: f32,
    bevel: f32,
    bevel_steps: jint,
) -> JObjectArray<'l> {
    match extrude_impl(&mut env, &points, &counts, depth, bevel, bevel_steps) {
        Ok(a) => a,
        Err(_) => JObjectArray::from(JObject::null()),
    }
}

fn extrude_impl<'l>(
    env: &mut JNIEnv<'l>,
    points: &JFloatArray<'l>,
    counts: &JIntArray<'l>,
    depth: f32,
    bevel: f32,
    steps: jint,
) -> jni::errors::Result<JObjectArray<'l>> {
    let np = env.get_array_length(points)? as usize;
    let nc = env.get_array_length(counts)? as usize;
    let mut flat = vec![0f32; np];
    env.get_float_array_region(points, 0, &mut flat)?;
    let mut cnt = vec![0i32; nc];
    env.get_int_array_region(counts, 0, &mut cnt)?;

    let mut contours: Vec<Vec<P>> = Vec::with_capacity(nc);
    let mut at = 0usize;
    for &c in &cnt {
        let c = c.max(0) as usize;
        if (at + c) * 2 > flat.len() {
            return Err(jni::errors::Error::JavaException);
        }
        contours.push((at..at + c).map(|i| P { x: flat[i * 2], y: flat[i * 2 + 1] }).collect());
        at += c;
    }
    let mesh = extrude::extrude(&contours, depth, bevel, steps);

    let out = env.new_object_array(4, "java/lang/Object", JObject::null())?;
    for (i, data) in [&mesh.positions, &mesh.normals, &mesh.uvs].into_iter().enumerate() {
        let arr = env.new_float_array(data.len() as i32)?;
        env.set_float_array_region(&arr, 0, data)?;
        env.set_object_array_element(&out, i as i32, arr)?;
    }
    let idx = env.new_int_array(mesh.indices.len() as i32)?;
    env.set_int_array_region(&idx, 0, &mesh.indices)?;
    env.set_object_array_element(&out, 3, idx)?;
    Ok(out)
}

// ------------------------------------------------------------------------------------------ Whisper

/// Whether this build of the library contains the speech-to-text engine.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperSupported<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) -> jboolean {
    if cfg!(feature = "whisper") { JNI_TRUE } else { JNI_FALSE }
}

/// Loads a ggml model. Returns an opaque handle, or 0 (see `whisperError`).
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperLoad<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>, path: JString<'l>) -> jlong {
    #[cfg(feature = "whisper")]
    {
        let Ok(path) = env.get_string(&path) else { return 0 };
        let path: String = path.into();
        let r = std::panic::catch_unwind(|| crate::whisper::load(&path));
        return match r {
            Ok(Ok(h)) => Box::into_raw(Box::new(h)) as jlong,
            Ok(Err(e)) => { set_error(e); 0 }
            Err(_) => { set_error("panic while loading the model".into()); 0 }
        };
    }
    #[cfg(not(feature = "whisper"))]
    {
        let _ = (&mut env, path);
        set_error("this build has no speech recognition".into());
        0
    }
}

/// The message of the last failed Whisper call.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperError<'l>(env: JNIEnv<'l>, _class: JClass<'l>) -> JString<'l> {
    let msg = LAST_ERROR.lock().map(|m| m.clone()).unwrap_or_default();
    env.new_string(msg).unwrap_or_else(|_| JString::from(JObject::null()))
}

static LAST_ERROR: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn set_error(msg: String) {
    if let Ok(mut m) = LAST_ERROR.lock() {
        *m = msg;
    }
}

/// Transcribes 16 kHz mono float PCM. Returns `Object[]{long[] t0, long[] t1, String[] text}` (times in hundredths
/// of a second) or null on failure / cancellation (see `whisperError`).
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperTranscribe<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JFloatArray<'l>,
    language: JString<'l>,
    translate: jboolean,
    threads: jint,
    max_chars: jint,
) -> JObjectArray<'l> {
    #[cfg(feature = "whisper")]
    {
        let null = || JObjectArray::from(JObject::null());
        if handle == 0 { return null(); }
        let run = |env: &mut JNIEnv<'l>| -> Result<JObjectArray<'l>, String> {
            let n = env.get_array_length(&pcm).map_err(|e| e.to_string())? as usize;
            let mut samples = vec![0f32; n];
            env.get_float_array_region(&pcm, 0, &mut samples).map_err(|e| e.to_string())?;
            let lang: String = env.get_string(&language).map_err(|e| e.to_string())?.into();
            let h = unsafe { &*(handle as *const crate::whisper::Handle) };
            let segs = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| h.transcribe(&samples, &lang, translate != 0, threads, max_chars)))
                .map_err(|_| "panic during transcription".to_string())??;
            let t0: Vec<i64> = segs.iter().map(|s| s.t0).collect();
            let t1: Vec<i64> = segs.iter().map(|s| s.t1).collect();
            let a0 = long_array(env, &t0).map_err(|e| e.to_string())?;
            let a1 = long_array(env, &t1).map_err(|e| e.to_string())?;
            let strings = env.new_object_array(segs.len() as i32, "java/lang/String", JObject::null()).map_err(|e| e.to_string())?;
            for (i, s) in segs.iter().enumerate() {
                let js = env.new_string(&s.text).map_err(|e| e.to_string())?;
                env.set_object_array_element(&strings, i as i32, js).map_err(|e| e.to_string())?;
            }
            let out = env.new_object_array(3, "java/lang/Object", JObject::null()).map_err(|e| e.to_string())?;
            env.set_object_array_element(&out, 0, a0).map_err(|e| e.to_string())?;
            env.set_object_array_element(&out, 1, a1).map_err(|e| e.to_string())?;
            env.set_object_array_element(&out, 2, strings).map_err(|e| e.to_string())?;
            Ok(out)
        };
        return match run(&mut env) {
            Ok(a) => a,
            Err(e) => { set_error(e); null() }
        };
    }
    #[cfg(not(feature = "whisper"))]
    {
        let _ = (&mut env, handle, pcm, language, translate, threads, max_chars);
        set_error("this build has no speech recognition".into());
        JObjectArray::from(JObject::null())
    }
}

#[cfg(feature = "whisper")]
fn long_array<'l>(env: &mut JNIEnv<'l>, data: &[i64]) -> jni::errors::Result<JLongArray<'l>> {
    let arr = env.new_long_array(data.len() as i32)?;
    env.set_long_array_region(&arr, 0, data)?;
    Ok(arr)
}

#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperCancel<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    #[cfg(feature = "whisper")]
    if handle != 0 {
        unsafe { &*(handle as *const crate::whisper::Handle) }.cancel();
    }
    #[cfg(not(feature = "whisper"))]
    let _ = handle;
}

/// Progress of the running transcription, 0..100.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperProgress<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) -> jint {
    #[cfg(feature = "whisper")]
    if handle != 0 {
        return unsafe { &*(handle as *const crate::whisper::Handle) }.progress();
    }
    let _ = handle;
    0
}

#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_whisperFree<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    #[cfg(feature = "whisper")]
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut crate::whisper::Handle) });
    }
    #[cfg(not(feature = "whisper"))]
    let _ = handle;
}

// ------------------------------------------------------------------------------------------ Stabilization (vid.stab)

#[cfg(feature = "stabilize")]
fn plane<'a>(env: &JNIEnv, buf: &JByteBuffer, stride: jint) -> Option<crate::stab::Plane<'a>> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let len = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() || stride <= 0 { return None; }
    // SAFETY: the direct buffer is owned by the JVM and outlives this call; vid.stab only reads it here.
    Some(crate::stab::Plane { data: unsafe { std::slice::from_raw_parts(ptr as *const u8, len) }, stride: stride as usize })
}

#[cfg(feature = "stabilize")]
fn plane_mut<'a>(env: &JNIEnv, buf: &JByteBuffer, stride: jint) -> Option<crate::stab::PlaneMut<'a>> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let len = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() || stride <= 0 { return None; }
    // SAFETY: as above; the destination planes are distinct buffers the caller owns exclusively during the call.
    Some(crate::stab::PlaneMut { data: unsafe { std::slice::from_raw_parts_mut(ptr, len) }, stride: stride as usize })
}

/// Whether this build contains vid.stab.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabSupported<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) -> jboolean {
    if cfg!(feature = "stabilize") { JNI_TRUE } else { JNI_FALSE }
}

/// Creates a stabilizer for YUV 4:2:0 frames of `width` × `height` (even). Returns a handle, or 0.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabNew<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, width: jint, height: jint, shakiness: jint, accuracy: jint,
) -> jlong {
    #[cfg(feature = "stabilize")]
    {
        if width <= 0 || height <= 0 { return 0; }
        return match crate::stab::Stabilizer::new(width as usize, height as usize, shakiness, accuracy) {
            Some(s) => Box::into_raw(Box::new(s)) as jlong,
            None => 0,
        };
    }
    #[cfg(not(feature = "stabilize"))]
    {
        let _ = (width, height, shakiness, accuracy);
        0
    }
}

/// Pass 1: one frame (three direct ByteBuffers with their row strides). 0 = ok.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabDetect<'l>(
    env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong,
    y: JByteBuffer<'l>, ys: jint, u: JByteBuffer<'l>, us: jint, v: JByteBuffer<'l>, vs: jint,
) -> jint {
    #[cfg(feature = "stabilize")]
    {
        if handle == 0 { return -100; }
        let (Some(py), Some(pu), Some(pv)) = (plane(&env, &y, ys), plane(&env, &u, us), plane(&env, &v, vs)) else { return -101 };
        let s = unsafe { &mut *(handle as *mut crate::stab::Stabilizer) };
        return match s.detect(py, pu, pv) { Ok(()) => 0, Err(e) => { set_error(e); -1 } };
    }
    #[cfg(not(feature = "stabilize"))]
    {
        let _ = (&env, handle, y, ys, u, us, v, vs);
        -102
    }
}

/// Between the passes (see ks_stab.h). 0 = ok.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabPrepare<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong, smoothing: jint, zoom: f64, optzoom: jint, crop_black: jboolean,
) -> jint {
    #[cfg(feature = "stabilize")]
    {
        if handle == 0 { return -100; }
        let s = unsafe { &mut *(handle as *mut crate::stab::Stabilizer) };
        return match s.prepare(smoothing, zoom, optzoom, crop_black != 0) { Ok(()) => 0, Err(e) => { set_error(e); -1 } };
    }
    #[cfg(not(feature = "stabilize"))]
    {
        let _ = (handle, smoothing, zoom, optzoom, crop_black);
        -102
    }
}

/// Pass 2: the next frame from the source planes into the destination planes. 0 = ok.
#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabApply<'l>(
    env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong,
    sy: JByteBuffer<'l>, sys: jint, su: JByteBuffer<'l>, sus: jint, sv: JByteBuffer<'l>, svs: jint,
    dy: JByteBuffer<'l>, dys: jint, du: JByteBuffer<'l>, dus: jint, dv: JByteBuffer<'l>, dvs: jint,
) -> jint {
    #[cfg(feature = "stabilize")]
    {
        if handle == 0 { return -100; }
        let (Some(a), Some(b), Some(c)) = (plane(&env, &sy, sys), plane(&env, &su, sus), plane(&env, &sv, svs)) else { return -101 };
        let (Some(d), Some(e), Some(f)) = (plane_mut(&env, &dy, dys), plane_mut(&env, &du, dus), plane_mut(&env, &dv, dvs)) else { return -101 };
        let s = unsafe { &mut *(handle as *mut crate::stab::Stabilizer) };
        return match s.apply(a, b, c, d, e, f) { Ok(()) => 0, Err(err) => { set_error(err); -1 } };
    }
    #[cfg(not(feature = "stabilize"))]
    {
        let _ = (&env, handle, sy, sys, su, sus, sv, svs, dy, dys, du, dus, dv, dvs);
        -102
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_kinesis_core_nativebridge_NativeCore_stabFree<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    #[cfg(feature = "stabilize")]
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut crate::stab::Stabilizer) });
    }
    #[cfg(not(feature = "stabilize"))]
    let _ = handle;
}
