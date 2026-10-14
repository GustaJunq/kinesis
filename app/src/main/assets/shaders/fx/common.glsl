// Shared declarations and helpers for effect passes (included by every shaders/fx/*.frag).
// Conventions: textures hold premultiplied alpha; v_uv is the GL texture coordinate (y up);
// lengths arrive in output pixels; points arrive in uv; angles in degrees, clockwise on screen.
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec2 u_resolution;   // output size in pixels
uniform float u_pxScale;     // output pixels per composition pixel
uniform float u_time;        // layer-local time in seconds
uniform vec2 u_origin;       // layer origin (uv)
uniform int u_samples;       // sample budget (lower in the preview while playing)
out vec4 fragColor;

const float PI = 3.14159265359;

vec3 unpremul(vec4 p) { return p.a > 0.0001 ? p.rgb / p.a : vec3(0.0); }
vec4 premul(vec3 c, float a) { return vec4(clamp(c, 0.0, 1.0) * a, a); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec2 toPx(vec2 uv) { return uv * u_resolution; }
vec2 toUv(vec2 px) { return px / u_resolution; }
vec4 samplePx(vec2 px) { return texture(u_texture, px / u_resolution); }

// Screen angle (clockwise, y down) as a direction in pixel space with y up.
vec2 dirOf(float degrees) { float a = radians(degrees); return vec2(cos(a), -sin(a)); }
// Rotates v clockwise on screen by `degrees`.
vec2 rotate2(vec2 v, float degrees) {
    float a = -radians(degrees);
    float c = cos(a), s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// Integer hashing: identical results on every GPU, so a seed always gives the same frame.
uint hashU(uint x) {
    x ^= x >> 16u; x *= 0x7feb352du;
    x ^= x >> 15u; x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}
float hash3(float a, float b, float c) {
    uint h = hashU(uint(int(a) + 65536) * 0x9E3779B1u ^ hashU(uint(int(b) + 65536) * 0x85EBCA77u ^ hashU(uint(int(c) + 65536))));
    return float(h >> 8u) / 16777216.0;
}
float hash2(vec2 p, float seed) { return hash3(p.x, p.y, seed); }

// Smooth 2D value noise in 0..1.
float vnoise(vec2 p, float seed) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash2(i, seed), b = hash2(i + vec2(1.0, 0.0), seed);
    float c = hash2(i + vec2(0.0, 1.0), seed), d = hash2(i + vec2(1.0, 1.0), seed);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Fractal noise in 0..1; `octaves` up to 8.
float fbm(vec2 p, int octaves, float seed) {
    float sum = 0.0, amp = 0.5, norm = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= octaves) break;
        sum += vnoise(p, seed + float(i) * 17.0) * amp;
        norm += amp;
        p = p * 2.03 + vec2(13.1, 7.7);
        amp *= 0.5;
    }
    return sum / max(norm, 0.0001);
}

// Procedural generators combine their pattern with the layer: 0 replace, 1 add, 2 multiply, 3 screen, 4 overlay.
uniform int u_mode;
uniform float u_mix;
vec4 combine(vec4 layer, vec4 gen) {
    vec3 base = unpremul(layer);
    vec3 g = gen.rgb;
    vec3 o = g;
    if (u_mode == 1) o = base + g;
    else if (u_mode == 2) o = base * g;
    else if (u_mode == 3) o = 1.0 - (1.0 - base) * (1.0 - g);
    else if (u_mode == 4) o = mix(2.0 * base * g, 1.0 - 2.0 * (1.0 - base) * (1.0 - g), step(0.5, base));
    return premul(mix(base, o, gen.a * u_mix), layer.a);
}
