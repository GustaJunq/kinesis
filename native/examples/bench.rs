//! Same workload as `CoreBench.text_extrude_24_glyphs_bevel` (Kotlin): `cargo run --release --example bench`.
use kinesis_native::extrude::{extrude, P};
use std::f64::consts::PI;
use std::time::Instant;

fn main() {
    let glyphs: Vec<Vec<Vec<P>>> = (0..24)
        .map(|g| {
            let outer = (0..240).map(|i| { let a = 2.0 * PI * i as f64 / 240.0; P { x: (a.cos() * (100.0 + 6.0 * (a * 7.0 + g as f64).sin())) as f32, y: (a.sin() * 100.0) as f32 } }).collect();
            let hole = (0..120).map(|i| { let a = -2.0 * PI * i as f64 / 120.0; P { x: (a.cos() * 50.0) as f32, y: (a.sin() * 50.0) as f32 } }).collect();
            vec![outer, hole]
        })
        .collect();
    let run = || { for c in &glyphs { std::hint::black_box(extrude(c, 40.0, 6.0, 3)); } };
    for _ in 0..3 { run(); }
    let mut t: Vec<f64> = (0..9).map(|_| { let s = Instant::now(); run(); s.elapsed().as_secs_f64() * 1e3 }).collect();
    t.sort_by(|a, b| a.partial_cmp(b).unwrap());
    println!("BENCH text_extrude_24_glyphs_bevel {:.3}", t[4]);
}
