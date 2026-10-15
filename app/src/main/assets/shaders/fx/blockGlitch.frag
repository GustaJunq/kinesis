#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_blockSize;  // px
uniform float u_intensity;
uniform float u_rate;       // changes per second (0 = frozen)
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    float step = floor(u_time * u_rate);
    float bs = max(u_blockSize, 4.0);
    vec2 block = floor(px / vec2(bs * 2.0, bs));
    float r = hash3(block.x, block.y + step * 977.0, u_seed);
    vec2 sp = px;
    bool swap = false;
    if (r < u_intensity * 0.5) {
        sp.x += (hash3(block.y, step, u_seed + 3.0) - 0.5) * bs * 4.0 * u_intensity;
        swap = r < u_intensity * 0.2;
    }
    vec4 s = samplePx(sp);
    fragColor = swap ? vec4(s.g, s.b, s.r, s.a) : s;
}
