#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_angle;   // degrees: total bend over the curved part
uniform float u_axis;    // degrees (screen angle, clockwise): the line the layer bends along
uniform float u_length;  // px: length of the curved part, measured along the axis
uniform float u_offset;  // px: where the curved part is centred, along the axis from the layer origin

// Bends the content like a bar: the middle part (u_length) becomes an arc of u_angle degrees and the two ends carry on
// straight, tilted by half the angle each. Every output pixel is mapped back to the (s, t) of the flat layer:
// s along the axis, t across it.
void main() {
    vec2 px = toPx(v_uv);
    float th = radians(clamp(u_angle, -170.0, 170.0));
    float L = max(u_length, 1.0);
    if (abs(th) < 0.0005) { fragColor = texture(u_texture, v_uv); return; }
    vec2 d = dirOf(u_axis);
    vec2 n = vec2(-d.y, d.x);
    vec2 o = toPx(u_origin) + d * u_offset;
    vec2 rel = px - o;
    float u = dot(rel, d);
    float v = dot(rel, n);
    float kappa = th / L;
    float R = 1.0 / kappa;          // signed radius; the centre of the arc is at (0, R) in (u, v)
    float sg = sign(R);
    vec2 w = vec2(u, R - v);
    float phi = atan(sg * w.x, sg * w.y);
    float s;
    float t;
    if (abs(phi) <= abs(th) * 0.5) {
        s = phi / kappa;
        t = R - sg * length(w);
    } else {
        float side = sign(phi * kappa);   // which end of the arc the straight part continues from
        float fe = side * th * 0.5;
        vec2 e = vec2(R * sin(fe), R * (1.0 - cos(fe)));
        vec2 tg = vec2(cos(fe), sin(fe));
        vec2 nm = vec2(-sin(fe), cos(fe));
        vec2 r2 = vec2(u, v) - e;
        s = side * L * 0.5 + dot(r2, tg);
        t = dot(r2, nm);
    }
    fragColor = samplePx(o + d * s + n * t);
}
