//! Safe wrapper of the vid.stab C wrapper (`ks_stab.c`). Frames are YUV 4:2:0 planar with a stride per plane.

use std::os::raw::{c_double, c_int};

#[repr(C)]
pub struct KsStab {
    _private: [u8; 0],
}

extern "C" {
    fn ks_stab_new(width: c_int, height: c_int, shakiness: c_int, accuracy: c_int) -> *mut KsStab;
    fn ks_stab_detect(s: *mut KsStab, y: *const u8, ys: c_int, u: *const u8, us: c_int, v: *const u8, vs: c_int) -> c_int;
    fn ks_stab_prepare(s: *mut KsStab, smoothing: c_int, zoom: c_double, optzoom: c_int, crop_black: c_int) -> c_int;
    fn ks_stab_apply(
        s: *mut KsStab,
        sy: *const u8, sys: c_int, su: *const u8, sus: c_int, sv: *const u8, svs: c_int,
        dy: *mut u8, dys: c_int, du: *mut u8, dus: c_int, dv: *mut u8, dvs: c_int,
    ) -> c_int;
    fn ks_stab_frames(s: *const KsStab) -> c_int;
    fn ks_stab_free(s: *mut KsStab);
}

/// One plane: data and its stride (bytes per row).
#[derive(Clone, Copy)]
pub struct Plane<'a> {
    pub data: &'a [u8],
    pub stride: usize,
}

pub struct PlaneMut<'a> {
    pub data: &'a mut [u8],
    pub stride: usize,
}

pub struct Stabilizer {
    ptr: *mut KsStab,
    width: usize,
    height: usize,
}

// The C state is only touched through &mut self.
unsafe impl Send for Stabilizer {}

fn check(len: usize, stride: usize, w: usize, h: usize, what: &str) -> Result<(), String> {
    if stride < w || len < stride * (h - 1) + w {
        return Err(format!("{what} plane too small for {w}x{h} (stride {stride}, len {len})"));
    }
    Ok(())
}

impl Stabilizer {
    /// `shakiness` 1..10, `accuracy` 1..15; even width/height >= 16.
    pub fn new(width: usize, height: usize, shakiness: i32, accuracy: i32) -> Option<Self> {
        let ptr = unsafe { ks_stab_new(width as c_int, height as c_int, shakiness, accuracy) };
        if ptr.is_null() { None } else { Some(Self { ptr, width, height }) }
    }

    fn check_frame(&self, y: (usize, usize), u: (usize, usize), v: (usize, usize)) -> Result<(), String> {
        let (cw, ch) = ((self.width + 1) / 2, (self.height + 1) / 2);
        check(y.0, y.1, self.width, self.height, "Y")?;
        check(u.0, u.1, cw, ch, "U")?;
        check(v.0, v.1, cw, ch, "V")
    }

    pub fn detect(&mut self, y: Plane, u: Plane, v: Plane) -> Result<(), String> {
        self.check_frame((y.data.len(), y.stride), (u.data.len(), u.stride), (v.data.len(), v.stride))?;
        let r = unsafe { ks_stab_detect(self.ptr, y.data.as_ptr(), y.stride as c_int, u.data.as_ptr(), u.stride as c_int, v.data.as_ptr(), v.stride as c_int) };
        if r == 0 { Ok(()) } else { Err(format!("motion detection failed ({r})")) }
    }

    pub fn prepare(&mut self, smoothing: i32, zoom: f64, optzoom: i32, crop_black: bool) -> Result<(), String> {
        let r = unsafe { ks_stab_prepare(self.ptr, smoothing, zoom, optzoom, crop_black as c_int) };
        if r == 0 { Ok(()) } else { Err(format!("preparing the transforms failed ({r})")) }
    }

    pub fn apply(&mut self, y: Plane, u: Plane, v: Plane, dy: PlaneMut, du: PlaneMut, dv: PlaneMut) -> Result<(), String> {
        self.check_frame((y.data.len(), y.stride), (u.data.len(), u.stride), (v.data.len(), v.stride))?;
        self.check_frame((dy.data.len(), dy.stride), (du.data.len(), du.stride), (dv.data.len(), dv.stride))?;
        let r = unsafe {
            ks_stab_apply(
                self.ptr, y.data.as_ptr(), y.stride as c_int, u.data.as_ptr(), u.stride as c_int, v.data.as_ptr(), v.stride as c_int,
                dy.data.as_mut_ptr(), dy.stride as c_int, du.data.as_mut_ptr(), du.stride as c_int, dv.data.as_mut_ptr(), dv.stride as c_int,
            )
        };
        if r == 0 { Ok(()) } else { Err(format!("transforming a frame failed ({r})")) }
    }

    pub fn frames(&self) -> i32 {
        unsafe { ks_stab_frames(self.ptr) }
    }
}

impl Drop for Stabilizer {
    fn drop(&mut self) {
        unsafe { ks_stab_free(self.ptr) }
    }
}
