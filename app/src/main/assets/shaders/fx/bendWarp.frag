#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_side;          // 0 left, 1 right, 2 top, 3 bottom: the side that stays fixed
uniform float u_angle;       // degrees: how much the bar turns from the start point to the far end (positive = counter-clockwise)
uniform float u_start;       // 0..1: the start point, as a fraction of the size from the fixed side
uniform int u_before;        // 0 freeze, 1 bend the same way, 2 bend the opposite way (the part before the start point)
uniform float u_size;        // px: size of the layer along the bend axis
uniform float u_anchor;      // where the layer origin falls along the axis, as a fraction of the size from the fixed side
uniform float u_lateral;     // px: how far the layer origin is from the layer's centre line, across the axis
uniform float u_highLimit;   // the bend fades out when the start point goes past this
uniform float u_lowLimit;    // the part before the start point fades in below this

// The layer bends like a bar held at one side. Each output pixel is mapped back to the flat layer: its distance along
// the axis (x, from the start point) and across it (y) give polar coordinates around the centre of the arc, whose
// angle times the radius is where it came from along the bar and whose distance to the centre is where it came from across.
void main() {
    vec2 px = toPx(v_uv);
    vec2 d = u_side == 0 ? dirOf(0.0) : u_side == 1 ? dirOf(180.0) : u_side == 2 ? dirOf(90.0) : dirOf(270.0);
    vec2 n = vec2(-d.y, d.x);
    vec2 o = toPx(u_origin);
    float size = max(u_size, 1.0);
    float adj = u_start * 0.99;
    float sa = adj * size;           // start point, from the fixed side
    float a0 = u_anchor * size;      // layer origin, from the fixed side
    vec2 rel = px - o;
    float x = dot(rel, d) + a0 - sa;
    float y = dot(rel, n) + u_lateral;
    float ang = -radians(u_angle);
    float radius;
    if (x >= 0.0) {
        float a = ang;
        if (adj >= u_highLimit * 0.99) a *= 1.0 - (adj - u_highLimit * 0.99) / max(0.99 - u_highLimit * 0.99, 0.0001);
        if (abs(a) < 0.001) { fragColor = texture(u_texture, v_uv); return; }
        radius = size * (1.0 - adj) / a;
    } else {
        if (u_before == 0 || adj <= 0.0) { fragColor = texture(u_texture, v_uv); return; }
        float a = ang * (adj < u_lowLimit ? adj / max(u_lowLimit, 0.0001) : 1.0);
        if (u_before == 2) a = -a;
        if (abs(a) < 0.001) { fragColor = texture(u_texture, v_uv); return; }
        radius = sa / a;
    }
    float sg = sign(radius);
    vec2 w = vec2(x, y + radius);
    float phi = atan(sg * w.x, sg * w.y);
    float along = sa + radius * phi;                 // where it came from along the bar, from the fixed side
    float across = sg * length(w) - radius;          // where it came from across it
    fragColor = samplePx(o + d * (along - a0) + n * (across - u_lateral));
}
