#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_intensity;
uniform float u_blockSize;       // px
uniform float u_blockDensity;    // share of blocks that move
uniform float u_rgbSplit;        // px
uniform float u_dropoutChance;   // share of blocks that turn transparent
uniform float u_speed;           // time multiplier; 0 freezes the glitch on one frame
uniform float u_seed;

// Offset (x, y) and RGB split of the block of the given size that holds px; zero when the block is not affected.
vec3 blockMove(vec2 px, vec2 size, float salt, float tick) {
    vec2 cell = floor(px / size);
    float r = hash3(cell.x, cell.y + tick * 977.0, u_seed + salt);
    if (r >= u_blockDensity) return vec3(0.0);
    float dx = (hash3(cell.y, tick, u_seed + salt + 3.0) - 0.5) * size.y * 4.0 * u_intensity;
    float jump = step(0.8, hash3(cell.x, cell.y - tick * 313.0, u_seed + salt + 5.0));
    float dy = (hash3(cell.x, tick, u_seed + salt + 7.0) - 0.5) * size.y * 0.5 * u_intensity * jump;
    float split = u_rgbSplit * u_intensity * (0.5 + hash3(cell.x, cell.y, u_seed + salt + 11.0));
    return vec3(dx, dy, split);
}

void main() {
    vec2 px = toPx(v_uv);
    float tick = floor(u_time * u_speed * 8.0);   // re-rolled 8 times per second at speed 1
    float bs = max(u_blockSize, 4.0);

    // Blocks of two shapes (wide and tall) can overlap, so the damage comes in several sizes.
    vec3 m = blockMove(px, vec2(bs * 2.0, bs), 0.0, tick) + blockMove(px, vec2(bs * 0.5, bs * 3.0), 100.0, tick);

    // Dropout: whole blocks vanish (transparent), also on a grid of their own.
    vec2 dropCell = floor(px / vec2(bs * 1.5, bs));
    if (hash3(dropCell.x + 31.0, dropCell.y - tick * 541.0, u_seed + 7.0) < u_dropoutChance) {
        fragColor = vec4(0.0);
        return;
    }

    vec2 at = px + m.xy;
    vec4 sr = samplePx(at + vec2(m.z, 0.0));
    vec4 sg = samplePx(at);
    vec4 sb = samplePx(at - vec2(m.z, 0.0));
    fragColor = vec4(sr.r, sg.g, sb.b, max(sr.a, max(sg.a, sb.a)));
}
