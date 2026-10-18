//! Compiles vid.stab (GPL-2.0-or-later, vendored in third_party/vidstab) and its small C wrapper when the
//! `stabilize` feature is on. Everything else in the crate is plain Rust.
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/ks_stab.c");
    println!("cargo:rerun-if-changed=src/ks_stab.h");
    println!("cargo:rerun-if-changed=third_party/vidstab");
    if std::env::var_os("CARGO_FEATURE_STABILIZE").is_none() {
        return;
    }
    let dir = std::path::Path::new("third_party/vidstab/src");
    let mut build = cc::Build::new();
    build
        .include(dir)
        .include("src")
        .std("gnu99")
        .opt_level(3)
        .pic(true)
        .warnings(false)
        .file("src/ks_stab.c");
    for f in [
        "boxblur.c", "frameinfo.c", "libvidstab.c", "localmotion2transform.c", "motiondetect.c", "motiondetect_opt.c",
        "serialize.c", "transform.c", "transformfixedpoint.c", "transformtype.c", "vsvector.c",
    ] {
        build.file(dir.join(f));
    }
    build.compile("kinesis_vidstab");
    println!("cargo:rustc-link-lib=m");
}
