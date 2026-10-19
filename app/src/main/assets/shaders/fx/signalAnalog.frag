#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;
uniform float u_lineJitter;
uniform float u_rgbSplit;           // px
uniform float u_scanlineIntensity;
uniform float u_scanlineDensity;    // 1 = a line pair every 4 composition px
uniform float u_noise;
uniform float u_speed;              // time multiplier (0 = frozen)
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    float t = u_time * u_speed;                  // layer time only: the preview fps never matters
    float rowH = max(u_pxScale * 2.0, 1.0);      // two composition pixels per line
    float row = floor(px.y / rowH);
    float tick = floor(t * 12.0);
    float base = 24.0 * u_pxScale;

    // Every line moves a little (jitter, re-rolled 12 times per second), the picture drifts slowly, and now and then a band tears.
    float drift = (vnoise(vec2(row * 0.04, t * 1.5), u_seed) - 0.5) * 2.0;
    float jitter = (hash3(row, tick, u_seed) - 0.5) * 2.0;
    float band = floor(row / 12.0);
    float tear = step(1.0 - 0.06 * u_amount, hash3(band, tick, u_seed + 9.0)) * (hash3(band, tick, u_seed + 21.0) - 0.5) * 8.0;
    float shift = (drift * 0.6 + jitter * u_lineJitter + tear) * u_amount * base;
    float split = u_rgbSplit * (1.0 + 0.5 * jitter * u_lineJitter);

    vec4 sr = samplePx(px + vec2(shift + split, 0.0));
    vec4 sg = samplePx(px + vec2(shift, 0.0));
    vec4 sb = samplePx(px + vec2(shift - split, 0.0));
    float a = max(sr.a, max(sg.a, sb.a));
    vec3 rgb = vec3(sr.r, sg.g, sb.b);

    float period = max(4.0 * u_pxScale / max(u_scanlineDensity, 0.05), 1.0);
    float line = 0.5 + 0.5 * cos(6.2831853 * px.y / period);
    rgb *= 1.0 - u_scanlineIntensity * line;

    float n = hash3(floor(px.x), floor(px.y) + 7919.0 * floor(t * 24.0), u_seed + 5.0);
    rgb = clamp(rgb + (n - 0.5) * u_noise * 0.6 * a, 0.0, a);
    fragColor = vec4(rgb, a);                    // premultiplied; the picture's alpha moves with the lines
}
