//! vid.stab through the wrapper: a still scene filmed with a shaking camera must come out steadier.
#![cfg(feature = "stabilize")]

use kinesis_native::stab::{Plane, PlaneMut, Stabilizer};

const W: usize = 320;
const H: usize = 240;
const PAD: usize = 48;

struct Lcg(u64);
impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 33) as f32) / (u32::MAX >> 1) as f32
    }
}

/// A textured scene (blobs and edges) larger than the frame, so shifted crops are all valid.
fn scene() -> Vec<u8> {
    let (sw, sh) = (W + 2 * PAD, H + 2 * PAD);
    let mut rng = Lcg(42);
    let mut img = vec![128f32; sw * sh];
    for _ in 0..260 {
        let (cx, cy) = (rng.next() * sw as f32, rng.next() * sh as f32);
        let r = 3.0 + rng.next() * 14.0;
        let v = (rng.next() - 0.5) * 200.0;
        for y in (cy as i32 - r as i32 - 1).max(0)..(cy as i32 + r as i32 + 2).min(sh as i32) {
            for x in (cx as i32 - r as i32 - 1).max(0)..(cx as i32 + r as i32 + 2).min(sw as i32) {
                let d = (((x as f32 - cx).powi(2) + (y as f32 - cy).powi(2)).sqrt() / r).min(2.0);
                img[y as usize * sw + x as usize] += v * (1.0 - d / 2.0).max(0.0);
            }
        }
    }
    img.iter().map(|&v| v.clamp(0.0, 255.0) as u8).collect()
}

struct Frame {
    y: Vec<u8>,
    u: Vec<u8>,
    v: Vec<u8>,
}

fn crop(scene: &[u8], ox: usize, oy: usize) -> Frame {
    let sw = W + 2 * PAD;
    let mut y = vec![0u8; W * H];
    for row in 0..H {
        y[row * W..(row + 1) * W].copy_from_slice(&scene[(oy + row) * sw + ox..(oy + row) * sw + ox + W]);
    }
    Frame { y, u: vec![128; (W / 2) * (H / 2)], v: vec![128; (W / 2) * (H / 2)] }
}

/// Mean absolute difference of the luma of two frames inside the central area.
fn mad(a: &[u8], b: &[u8]) -> f64 {
    let m = 56;
    let mut s = 0u64;
    let mut n = 0u64;
    for y in m..H - m {
        for x in m..W - m {
            s += (a[y * W + x] as i32 - b[y * W + x] as i32).unsigned_abs() as u64;
            n += 1;
        }
    }
    s as f64 / n as f64
}

fn planes(f: &Frame) -> (Plane<'_>, Plane<'_>, Plane<'_>) {
    (Plane { data: &f.y, stride: W }, Plane { data: &f.u, stride: W / 2 }, Plane { data: &f.v, stride: W / 2 })
}

#[test]
fn a_shaking_camera_comes_out_steadier() {
    let sc = scene();
    let mut rng = Lcg(7);
    let n = 60;
    // Handheld-like shake: a slow drift plus a strong random jitter of up to +-7 px per frame.
    let frames: Vec<Frame> = (0..n)
        .map(|i| {
            let drift = (i as f32 * 0.4) as i32;
            let jx = ((rng.next() - 0.5) * 14.0).round() as i32;
            let jy = ((rng.next() - 0.5) * 14.0).round() as i32;
            crop(&sc, (PAD as i32 + drift / 2 + jx) as usize, (PAD as i32 + jy) as usize)
        })
        .collect();

    let mut s = Stabilizer::new(W, H, 6, 9).expect("stabilizer");
    for f in &frames {
        let (y, u, v) = planes(f);
        s.detect(y, u, v).expect("detect");
    }
    s.prepare(12, 0.0, 1, false).expect("prepare");

    let mut out: Vec<Frame> = Vec::new();
    for f in &frames {
        let mut o = Frame { y: vec![0; W * H], u: vec![128; (W / 2) * (H / 2)], v: vec![128; (W / 2) * (H / 2)] };
        {
            let (y, u, v) = planes(f);
            s.apply(y, u, v, PlaneMut { data: &mut o.y, stride: W }, PlaneMut { data: &mut o.u, stride: W / 2 }, PlaneMut { data: &mut o.v, stride: W / 2 }).expect("apply");
        }
        out.push(o);
    }
    assert_eq!(s.frames(), n as i32);

    let before: f64 = (1..n).map(|i| mad(&frames[i - 1].y, &frames[i].y)).sum::<f64>() / (n - 1) as f64;
    let after: f64 = (1..n).map(|i| mad(&out[i - 1].y, &out[i].y)).sum::<f64>() / (n - 1) as f64;
    println!("frame-to-frame difference: shaky {before:.2} -> stabilized {after:.2}");
    assert!(after < before * 0.6, "stabilized {after:.2} should be well under shaky {before:.2}");
}

#[test]
fn bad_arguments_are_rejected_not_crashed() {
    assert!(Stabilizer::new(15, 100, 5, 5).is_none());
    assert!(Stabilizer::new(100, 101, 5, 5).is_none());
    let mut s = Stabilizer::new(64, 64, 5, 5).unwrap();
    let small = vec![0u8; 10];
    let p = |d: &[u8]| Plane { data: unsafe { std::mem::transmute::<&[u8], &[u8]>(d) }, stride: 64 };
    assert!(s.detect(p(&small), p(&small), p(&small)).is_err());
    // prepare before any frame: error
    assert!(s.prepare(10, 0.0, 0, false).is_err());
}
