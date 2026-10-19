#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_progress;     // 0 = whole picture, 1 = nothing left
uniform float u_blockSize;    // px
uniform float u_shakeAmount;  // px
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    float bs = max(u_blockSize, 4.0);
    vec2 cell = floor(px / bs);
    // Each block has its own moment to leave; it shakes and fades during the last stretch (W of the progress) before it is gone.
    const float W = 0.3;
    float leaves = hash3(cell.x, cell.y, u_seed);
    float local = (clamp(u_progress, 0.0, 1.0) * (1.0 + W) - leaves) / W;   // <= 0 untouched, >= 1 gone
    if (local >= 1.0) {
        fragColor = vec4(0.0);
        return;
    }
    vec2 offset = vec2(0.0);
    float fade = 1.0;
    if (local > 0.0) {
        float tick = floor(u_time * 30.0);   // the layer's own time: the preview fps never matters
        vec2 j = vec2(hash3(cell.x, cell.y + tick * 131.0, u_seed + 1.0), hash3(cell.y, cell.x - tick * 71.0, u_seed + 2.0)) - 0.5;
        offset = j * 2.0 * u_shakeAmount * local;
        fade = 1.0 - smoothstep(0.4, 1.0, local);
    }
    fragColor = samplePx(px + offset) * fade;   // premultiplied, so the layer's alpha scales with the colour
}
