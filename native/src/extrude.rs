//! Extruded, optionally bevelled, solids from flat outlines (the contours of text glyphs).
//!
//! A line-by-line port of `dev.kinesis.core.geom.TextExtrusion` (Kotlin), so both produce the same geometry:
//! coordinates are Kinesis pixels (+x right, +y down); the front face is at z = -depth/2, the back at z = +depth/2.

use std::collections::HashMap;
use std::f64::consts::PI;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct P {
    pub x: f32,
    pub y: f32,
}

#[derive(Clone, Copy)]
struct V3 {
    x: f32,
    y: f32,
    z: f32,
}

impl V3 {
    fn sub(self, o: V3) -> V3 {
        V3 { x: self.x - o.x, y: self.y - o.y, z: self.z - o.z }
    }
    fn cross(self, o: V3) -> V3 {
        V3 { x: self.y * o.z - self.z * o.y, y: self.z * o.x - self.x * o.z, z: self.x * o.y - self.y * o.x }
    }
    fn dot(self, o: V3) -> f32 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }
    fn normalized(self) -> V3 {
        let l = (self.x * self.x + self.y * self.y + self.z * self.z).sqrt();
        if l < 1e-9 { self } else { V3 { x: self.x / l, y: self.y / l, z: self.z / l } }
    }
}

/// Indexed triangle mesh: x,y,z / unit normals / u,v per vertex.
#[derive(Default, Debug, Clone)]
pub struct Mesh {
    pub positions: Vec<f32>,
    pub normals: Vec<f32>,
    pub uvs: Vec<f32>,
    pub indices: Vec<i32>,
}

impl Mesh {
    fn vertex(&mut self, x: f32, y: f32, z: f32, nx: f32, ny: f32, nz: f32, u: f32, v: f32) -> i32 {
        self.positions.extend_from_slice(&[x, y, z]);
        self.normals.extend_from_slice(&[nx, ny, nz]);
        self.uvs.extend_from_slice(&[u, v]);
        (self.positions.len() / 3 - 1) as i32
    }

    fn pos(&self, i: i32) -> V3 {
        let i = i as usize * 3;
        V3 { x: self.positions[i], y: self.positions[i + 1], z: self.positions[i + 2] }
    }

    /// Adds a triangle whose front side faces `normal`.
    fn tri_facing(&mut self, a: i32, b: i32, c: i32, normal: V3) {
        let f = self.pos(b).sub(self.pos(a)).cross(self.pos(c).sub(self.pos(a)));
        if f.dot(normal) >= 0.0 {
            self.indices.extend_from_slice(&[a, b, c]);
        } else {
            self.indices.extend_from_slice(&[a, c, b]);
        }
    }
}

const SMOOTH_ANGLE_DEG: f64 = 35.0;

type Ref = (usize, usize);

/// `contours` are closed polygons (the last point is not repeated) in any winding; nesting decides what is
/// filled. `bevel` (clamped to depth/2) rounds the front and back edges with `bevel_steps` segments.
pub fn extrude(contours: &[Vec<P>], depth: f32, bevel: f32, bevel_steps: i32) -> Mesh {
    let polys: Vec<Vec<P>> = contours.iter().map(|c| clean(c)).filter(|p| p.len() >= 3 && signed_area(p).abs() > 1e-4).collect();
    if polys.is_empty() || depth <= 0.0 {
        return Mesh::default();
    }

    let nesting: Vec<usize> =
        (0..polys.len()).map(|i| (0..polys.len()).filter(|&j| j != i && contains(&polys[j], polys[i][0])).count()).collect();
    let oriented: Vec<Vec<P>> = polys
        .iter()
        .enumerate()
        .map(|(i, p)| {
            let want_positive = nesting[i] % 2 == 0;
            if (signed_area(p) > 0.0) == want_positive { p.clone() } else { p.iter().rev().copied().collect() }
        })
        .collect();

    let mut cap_triangles: Vec<Ref> = Vec::new();
    for outer in 0..oriented.len() {
        if nesting[outer] % 2 != 0 {
            continue;
        }
        let holes: Vec<usize> = (0..oriented.len())
            .filter(|&h| {
                nesting[h] == nesting[outer] + 1
                    && contains(&oriented[outer], oriented[h][0])
                    && !(0..oriented.len()).any(|o| {
                        o != outer
                            && nesting[o] == nesting[outer]
                            && contains(&oriented[o], oriented[h][0])
                            && signed_area(&oriented[o]).abs() < signed_area(&oriented[outer]).abs()
                    })
            })
            .collect();
        cap_triangles.extend(triangulate(&oriented, outer, &holes));
    }

    let b = depth / 2.0;
    let bev = bevel.clamp(0.0, b);
    let steps = if bev > 1e-3 { bevel_steps.max(1) } else { 0 };

    // Profile rings from the front face to the back face: (inset, z, sin, cos·side).
    struct Ring {
        inset: f32,
        z: f32,
        s: f32,
        c: f32,
    }
    let mut rings: Vec<Ring> = Vec::new();
    if steps == 0 {
        rings.push(Ring { inset: 0.0, z: -b, s: 1.0, c: 0.0 });
        rings.push(Ring { inset: 0.0, z: b, s: 1.0, c: 0.0 });
    } else {
        for j in 0..=steps {
            let th = j as f64 / steps as f64 * PI / 2.0;
            rings.push(Ring {
                inset: (bev as f64 * (1.0 - th.sin())) as f32,
                z: (-b as f64 + bev as f64 * (1.0 - th.cos())) as f32,
                s: th.sin() as f32,
                c: -th.cos() as f32,
            });
        }
        for j in (0..=steps).rev() {
            let th = j as f64 / steps as f64 * PI / 2.0;
            rings.push(Ring {
                inset: (bev as f64 * (1.0 - th.sin())) as f32,
                z: (b as f64 - bev as f64 * (1.0 - th.cos())) as f32,
                s: th.sin() as f32,
                c: th.cos() as f32,
            });
        }
    }

    let miters: Vec<Vec<P>> = oriented.iter().map(|p| miter_offsets(p)).collect();
    let mut out = Mesh::default();
    let (mut min_x, mut max_x, mut min_y, mut max_y) = (f32::INFINITY, f32::NEG_INFINITY, f32::INFINITY, f32::NEG_INFINITY);
    for p in oriented.iter().flatten() {
        min_x = min_x.min(p.x);
        max_x = max_x.max(p.x);
        min_y = min_y.min(p.y);
        max_y = max_y.max(p.y);
    }
    let w = (max_x - min_x).max(1e-3);
    let h = (max_y - min_y).max(1e-3);

    let at = |ci: usize, k: usize, inset: f32| -> P {
        let p = oriented[ci][k];
        let o = miters[ci][k];
        P { x: p.x - o.x * inset, y: p.y - o.y * inset }
    };

    // Caps
    for (front, ring) in [(true, &rings[0]), (false, &rings[rings.len() - 1])] {
        let nz = if front { -1.0f32 } else { 1.0 };
        let mut ids: HashMap<Ref, i32> = HashMap::new();
        let mut vid = |out: &mut Mesh, r: Ref| -> i32 {
            *ids.entry(r).or_insert_with(|| {
                let p = at(r.0, r.1, ring.inset);
                out.vertex(p.x, p.y, ring.z, 0.0, 0.0, nz, (p.x - min_x) / w, (p.y - min_y) / h)
            })
        };
        for t in (0..cap_triangles.len()).step_by(3) {
            let a = vid(&mut out, cap_triangles[t]);
            let c1 = vid(&mut out, cap_triangles[t + 1]);
            let c2 = vid(&mut out, cap_triangles[t + 2]);
            out.tri_facing(a, c1, c2, V3 { x: 0.0, y: 0.0, z: nz });
        }
    }

    // Side walls
    let cos_limit = SMOOTH_ANGLE_DEG.to_radians().cos() as f32;
    for (ci, poly) in oriented.iter().enumerate() {
        let n = poly.len();
        let edge_normals: Vec<P> = (0..n)
            .map(|k| {
                let a = poly[k];
                let c = poly[(k + 1) % n];
                let dx = c.x - a.x;
                let dy = c.y - a.y;
                let l = (dx * dx + dy * dy).sqrt().max(1e-9);
                P { x: dy / l, y: -dx / l }
            })
            .collect();
        let perimeter = ((0..n).map(|k| dist(poly[k], poly[(k + 1) % n]) as f64).sum::<f64>() as f32).max(1e-3);
        let mut run = 0.0f32;
        for k in 0..n {
            let k2 = (k + 1) % n;
            let en = edge_normals[k];
            let normal_at = |vertex: usize| -> P {
                let prev = edge_normals[(vertex + n - 1) % n];
                let next = edge_normals[vertex];
                let other = if vertex == k { prev } else { next };
                if other.x * en.x + other.y * en.y >= cos_limit { normalize(P { x: prev.x + next.x, y: prev.y + next.y }) } else { en }
            };
            let n0 = normal_at(k);
            let n1 = normal_at(k2);
            let len = dist(poly[k], poly[k2]);
            let u0 = run / perimeter;
            let u1 = (run + len) / perimeter;
            run += len;
            for r in 0..rings.len() - 1 {
                let ra = &rings[r];
                let rb = &rings[r + 1];
                if (ra.z - rb.z).abs() < 1e-6 && (ra.inset - rb.inset).abs() < 1e-6 {
                    continue;
                }
                let v = |out: &mut Mesh, ring: &Ring, vertex: usize, nn: P, u: f32, vv: f32| -> i32 {
                    let p = at(ci, vertex, ring.inset);
                    let nv = V3 { x: nn.x * ring.s, y: nn.y * ring.s, z: ring.c }.normalized();
                    out.vertex(p.x, p.y, ring.z, nv.x, nv.y, nv.z, u, vv)
                };
                let va = r as f32 / (rings.len() - 1) as f32;
                let vb = (r + 1) as f32 / (rings.len() - 1) as f32;
                let i0 = v(&mut out, ra, k, n0, u0, va);
                let i1 = v(&mut out, ra, k2, n1, u1, va);
                let i2 = v(&mut out, rb, k2, n1, u1, vb);
                let i3 = v(&mut out, rb, k, n0, u0, vb);
                let mid = V3 { x: en.x * ((ra.s + rb.s) / 2.0), y: en.y * ((ra.s + rb.s) / 2.0), z: (ra.c + rb.c) / 2.0 };
                out.tri_facing(i0, i1, i2, mid);
                out.tri_facing(i0, i2, i3, mid);
            }
        }
    }
    out
}

// ------------------------------------------------------------------ polygons

fn clean(points: &[P]) -> Vec<P> {
    let mut out: Vec<P> = Vec::with_capacity(points.len());
    for &p in points {
        if out.last().map_or(true, |&l| dist(l, p) > 1e-4) {
            out.push(p);
        }
    }
    while out.len() > 1 && dist(out[0], out[out.len() - 1]) <= 1e-4 {
        out.pop();
    }
    // Drop collinear points (they only add slivers).
    let mut changed = true;
    while changed && out.len() > 3 {
        changed = false;
        for i in 0..out.len() {
            let n = out.len();
            let a = out[(i + n - 1) % n];
            let b = out[i];
            let c = out[(i + 1) % n];
            if cross(a, b, c).abs() < 1e-6 * (dist(a, b) + dist(b, c) + 1.0) {
                out.remove(i);
                changed = true;
                break;
            }
        }
    }
    out
}

pub fn signed_area(p: &[P]) -> f32 {
    let mut s = 0.0f64;
    for i in 0..p.len() {
        let a = p[i];
        let b = p[(i + 1) % p.len()];
        s += a.x as f64 * b.y as f64 - b.x as f64 * a.y as f64;
    }
    (s / 2.0) as f32
}

/// Even-odd point in polygon.
pub fn contains(poly: &[P], pt: P) -> bool {
    let mut inside = false;
    let mut j = poly.len() - 1;
    for i in 0..poly.len() {
        let a = poly[i];
        let b = poly[j];
        if (a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x {
            inside = !inside;
        }
        j = i;
    }
    inside
}

fn miter_offsets(poly: &[P]) -> Vec<P> {
    let n = poly.len();
    (0..n)
        .map(|k| {
            let p0 = poly[(k + n - 1) % n];
            let p1 = poly[k];
            let p2 = poly[(k + 1) % n];
            let e1 = normalize(P { x: p1.x - p0.x, y: p1.y - p0.y });
            let e2 = normalize(P { x: p2.x - p1.x, y: p2.y - p1.y });
            let n1 = P { x: e1.y, y: -e1.x };
            let n2 = P { x: e2.y, y: -e2.x };
            let m = normalize(P { x: n1.x + n2.x, y: n1.y + n2.y });
            let d = (m.x * n1.x + m.y * n1.y).max(0.5); // limits spikes at sharp corners
            P { x: m.x / d, y: m.y / d }
        })
        .collect()
}

// ------------------------------------------------------------------ triangulation

/// Ear clipping of `outer` with its `holes` (contour indices; outer has positive area, holes negative).
fn triangulate(polys: &[Vec<P>], outer: usize, holes: &[usize]) -> Vec<Ref> {
    let pos = |r: Ref| polys[r.0][r.1];
    let mut ring: Vec<Ref> = (0..polys[outer].len()).map(|i| (outer, i)).collect();

    // Bridge holes into the outline, rightmost hole first (stable sort, like Kotlin's sortedByDescending).
    let max_x = |hi: usize| polys[hi].iter().fold(f32::NEG_INFINITY, |m, p| m.max(p.x));
    let mut order: Vec<usize> = holes.to_vec();
    order.sort_by(|&a, &b| max_x(b).partial_cmp(&max_x(a)).unwrap_or(std::cmp::Ordering::Equal));
    for h in order {
        let hole = &polys[h];
        let mut m_idx = 0;
        for i in 1..hole.len() {
            if hole[i].x > hole[m_idx].x {
                m_idx = i;
            }
        }
        let m = hole[m_idx];
        let ring_pts: Vec<P> = ring.iter().map(|&r| pos(r)).collect();
        let Some(bridge) = find_bridge(&ring_pts, m) else { continue };
        let mut insertion: Vec<Ref> = (0..=hole.len()).map(|it| (h, (m_idx + it) % hole.len())).collect();
        insertion.push(ring[bridge]);
        let tail = ring.split_off(bridge + 1);
        ring.extend(insertion);
        ring.extend(tail);
    }

    let mut result: Vec<Ref> = Vec::new();
    let mut work = ring;
    let mut guard = (work.len() * work.len() + 16) as i64;
    while work.len() > 3 && guard > 0 {
        guard -= 1;
        let mut clipped = false;
        for i in 0..work.len() {
            let n = work.len();
            let (ip, inx) = ((i + n - 1) % n, (i + 1) % n);
            let a = pos(work[ip]);
            let b = pos(work[i]);
            let c = pos(work[inx]);
            let turn = cross(a, b, c);
            if turn.abs() < 1e-9 {
                work.remove(i);
                clipped = true;
                break;
            }
            if turn < 0.0 {
                continue;
            }
            let blocked = (0..n).any(|j| {
                let p = pos(work[j]);
                j != i && j != ip && j != inx && !same(p, a) && !same(p, b) && !same(p, c) && in_triangle(p, a, b, c)
            });
            if blocked {
                continue;
            }
            result.push(work[ip]);
            result.push(work[i]);
            result.push(work[inx]);
            work.remove(i);
            clipped = true;
            break;
        }
        if !clipped {
            // Self-touching input: clip the most convex vertex anyway so we always terminate.
            let n = work.len();
            let mut best = 0;
            let mut best_cross = f32::NEG_INFINITY;
            for j in 0..n {
                let cr = cross(pos(work[(j + n - 1) % n]), pos(work[j]), pos(work[(j + 1) % n]));
                if cr > best_cross {
                    best_cross = cr;
                    best = j;
                }
            }
            let i = best;
            result.push(work[(i + n - 1) % n]);
            result.push(work[i]);
            result.push(work[(i + 1) % n]);
            work.remove(i);
        }
    }
    if work.len() == 3 && cross(pos(work[0]), pos(work[1]), pos(work[2])) > 0.0 {
        result.extend(work);
    }
    result
}

/// Index of a vertex of `ring` visible from hole point `m` (David Eberly, "Triangulation by Ear Clipping").
fn find_bridge(ring: &[P], m: P) -> Option<usize> {
    let n = ring.len();
    let mut best_x = f32::INFINITY;
    let mut edge: Option<usize> = None;
    for i in 0..n {
        let a = ring[i];
        let b = ring[(i + 1) % n];
        if a.y == b.y {
            continue;
        }
        if m.y < a.y.min(b.y) || m.y > a.y.max(b.y) {
            continue;
        }
        let x = a.x + (m.y - a.y) * (b.x - a.x) / (b.y - a.y);
        if x >= m.x && x < best_x {
            best_x = x;
            edge = Some(i);
        }
    }
    let edge = edge?;
    let a = ring[edge];
    let b = ring[(edge + 1) % n];
    let p_idx = if a.x > b.x { edge } else { (edge + 1) % n };
    let p = ring[p_idx];
    let i = P { x: best_x, y: m.y };
    if same(i, p) {
        return Some(p_idx);
    }
    // A reflex vertex inside triangle (m, i, p) blocks the view: take the one closest in angle to the ray.
    let mut best = p_idx;
    let mut best_cos = -2.0f32;
    for k in 0..n {
        let r = ring[k];
        let prev = ring[(k + n - 1) % n];
        let next = ring[(k + 1) % n];
        if cross(prev, r, next) >= 0.0 {
            continue;
        }
        if !in_triangle(r, m, i, p) && !in_triangle(r, m, p, i) {
            continue;
        }
        let dx = r.x - m.x;
        let dy = r.y - m.y;
        let l = (dx * dx + dy * dy).sqrt();
        if l < 1e-9 {
            continue;
        }
        let c = dx / l;
        if c > best_cos {
            best_cos = c;
            best = k;
        }
    }
    Some(best)
}

fn cross(a: P, b: P, c: P) -> f32 {
    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
}

fn in_triangle(p: P, a: P, b: P, c: P) -> bool {
    let d1 = (p.x - b.x) * (a.y - b.y) - (a.x - b.x) * (p.y - b.y);
    let d2 = (p.x - c.x) * (b.y - c.y) - (b.x - c.x) * (p.y - c.y);
    let d3 = (p.x - a.x) * (c.y - a.y) - (c.x - a.x) * (p.y - a.y);
    let neg = d1 < 0.0 || d2 < 0.0 || d3 < 0.0;
    let pos = d1 > 0.0 || d2 > 0.0 || d3 > 0.0;
    !(neg && pos)
}

fn same(a: P, b: P) -> bool {
    (a.x - b.x).abs() < 1e-5 && (a.y - b.y).abs() < 1e-5
}

fn dist(a: P, b: P) -> f32 {
    let dx = a.x - b.x;
    let dy = a.y - b.y;
    (dx * dx + dy * dy).sqrt()
}

fn normalize(v: P) -> P {
    let l = (v.x * v.x + v.y * v.y).sqrt();
    if l < 1e-9 { P { x: 0.0, y: 0.0 } } else { P { x: v.x / l, y: v.y / l } }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rect(x0: f32, y0: f32, x1: f32, y1: f32) -> Vec<P> {
        vec![P { x: x0, y: y0 }, P { x: x1, y: y0 }, P { x: x1, y: y1 }, P { x: x0, y: y1 }]
    }

    fn circle(r: f32, n: usize) -> Vec<P> {
        (0..n).map(|i| { let a = 2.0 * PI * i as f64 / n as f64; P { x: (r as f64 * a.cos()) as f32, y: (r as f64 * a.sin()) as f32 } }).collect()
    }

    fn cap_area(m: &Mesh, nz: f32) -> f32 {
        let mut s = 0.0;
        for t in m.indices.chunks(3) {
            let v: Vec<usize> = t.iter().map(|&i| i as usize).collect();
            if v.iter().any(|&i| m.normals[i * 3 + 2] != nz) { continue; }
            let p = |i: usize| (m.positions[i * 3], m.positions[i * 3 + 1]);
            let (a, b, c) = (p(v[0]), p(v[1]), p(v[2]));
            s += ((b.0 - a.0) * (c.1 - a.1) - (b.1 - a.1) * (c.0 - a.0)).abs() / 2.0;
        }
        s
    }

    fn assert_consistent_winding(m: &Mesh) {
        for t in m.indices.chunks(3) {
            let p = |i: i32| m.pos(i);
            let f = p(t[1]).sub(p(t[0])).cross(p(t[2]).sub(p(t[0])));
            if f.dot(f).sqrt() < 1e-4 { continue; }
            let n = |i: i32| { let i = i as usize * 3; V3 { x: m.normals[i], y: m.normals[i + 1], z: m.normals[i + 2] } };
            let (a, b, c) = (n(t[0]), n(t[1]), n(t[2]));
            let sum = V3 { x: a.x + b.x + c.x, y: a.y + b.y + c.y, z: a.z + b.z + c.z };
            assert!(f.dot(sum) > 0.0, "triangle faces away from its normals");
        }
    }

    #[test]
    fn box_has_exact_bounds_and_caps() {
        let m = extrude(&[rect(0.0, 0.0, 100.0, 50.0)], 40.0, 0.0, 3);
        let zs: Vec<f32> = m.positions.chunks(3).map(|p| p[2]).collect();
        assert!(zs.iter().cloned().fold(f32::INFINITY, f32::min) == -20.0);
        assert!(zs.iter().cloned().fold(f32::NEG_INFINITY, f32::max) == 20.0);
        assert!((cap_area(&m, -1.0) - 5000.0).abs() < 0.5);
        assert!((cap_area(&m, 1.0) - 5000.0).abs() < 0.5);
        assert_consistent_winding(&m);
    }

    #[test]
    fn hole_is_left_open() {
        let m = extrude(&[circle(100.0, 48), circle(60.0, 48)], 30.0, 0.0, 3);
        let ring = 0.5 * 48.0 * (100.0f32.powi(2) - 60.0f32.powi(2)) * (2.0 * PI as f32 / 48.0).sin();
        assert!((cap_area(&m, -1.0) - ring).abs() < ring * 0.01, "{} vs {}", cap_area(&m, -1.0), ring);
        assert_consistent_winding(&m);
    }

    #[test]
    fn bevel_insets_the_front_face_and_is_clamped() {
        let m = extrude(&[rect(0.0, 0.0, 100.0, 100.0)], 40.0, 5.0, 3);
        assert!((cap_area(&m, -1.0) - 90.0 * 90.0).abs() < 0.5);
        assert_consistent_winding(&m);
        let c = extrude(&[rect(0.0, 0.0, 100.0, 100.0)], 10.0, 50.0, 3);
        let zs: Vec<f32> = c.positions.chunks(3).map(|p| p[2]).collect();
        let span = zs.iter().cloned().fold(f32::NEG_INFINITY, f32::max) - zs.iter().cloned().fold(f32::INFINITY, f32::min);
        assert!((span - 10.0).abs() < 1e-4);
    }

    #[test]
    fn empty_or_degenerate_input_gives_an_empty_mesh() {
        assert!(extrude(&[], 10.0, 0.0, 3).positions.is_empty());
        assert!(extrude(&[vec![P { x: 0.0, y: 0.0 }, P { x: 1.0, y: 0.0 }, P { x: 2.0, y: 0.0 }]], 10.0, 0.0, 3).positions.is_empty());
    }

    #[test]
    fn letter_e_keeps_its_whole_face() {
        let e: Vec<P> = [(0.0, 0.0), (50.0, 0.0), (100.0, 0.0), (100.0, 20.0), (25.0, 20.0), (25.0, 40.0), (80.0, 40.0), (80.0, 60.0), (25.0, 60.0), (25.0, 80.0), (25.0, 80.00001), (100.0, 80.0), (100.0, 100.0), (60.0, 100.0), (0.0, 100.0), (0.0, 70.0), (0.00002, 50.0), (0.0, 30.0)]
            .iter().map(|&(x, y)| P { x, y }).collect();
        let area = signed_area(&e).abs();
        let rev: Vec<P> = e.iter().rev().copied().collect();
        for c in [&e, &rev] {
            let m = extrude(&[c.clone()], 30.0, 0.0, 3);
            assert!((cap_area(&m, -1.0) - area).abs() < 0.5);
            assert_consistent_winding(&m);
        }
    }
}
