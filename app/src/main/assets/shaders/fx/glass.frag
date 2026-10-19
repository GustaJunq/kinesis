#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform sampler2D u_backdrop;  // composite of the layers below (premultiplied)
uniform sampler2D u_height;    // blurred copy of the layer: alpha bevel and optional luminance height
uniform float u_refraction;    // index of refraction
uniform float u_dispersion;    // 0 = no chromatic aberration
uniform float u_edgeGlow;
uniform float u_edgeGlowWidth; // px
uniform vec4 u_edgeGlowColor;
uniform float u_bevel;         // px
uniform float u_useLuminanceAsHeight;
uniform float u_heightScale;   // px

// Height of the glass surface in pixels.
float heightAt(vec2 uv) {
    vec4 h = texture(u_height, uv);
    float hgt = smoothstep(0.0, 1.0, h.a) * u_bevel;
    if (u_useLuminanceAsHeight > 0.5) hgt += luma(unpremul(h)) * h.a * u_heightScale;
    return hgt;
}

// Seven bands of the visible spectrum (violet .. red) and their contribution to R, G and B.
const vec3 BAND_SUM = vec3(3.3, 3.25, 2.9);

// One band: shorter wavelengths bend more, so each gets a slightly different index (k = 3 .. -3).
void addBand(vec3 band, float k, vec3 n, float thickness, inout vec3 refr, inout float alpha) {
    float ior = u_refraction * (1.0 + u_dispersion * 0.08 * k / 3.0);
    vec3 r = refract(vec3(0.0, 0.0, -1.0), n, 1.0 / max(ior, 1.0));
    vec2 offset = r.xy / max(-r.z, 0.2) * thickness;
    vec4 s = texture(u_backdrop, v_uv + toUv(offset));
    refr += s.rgb * band;
    alpha += s.a;
}

void main() {
    vec4 layer = texture(u_texture, v_uv);
    if (layer.a <= 0.001) { fragColor = vec4(0.0); return; }
    vec2 e = 1.5 / u_resolution;
    vec2 grad = vec2(heightAt(v_uv + vec2(e.x, 0.0)) - heightAt(v_uv - vec2(e.x, 0.0)),
                     heightAt(v_uv + vec2(0.0, e.y)) - heightAt(v_uv - vec2(0.0, e.y))) / 3.0;
    vec3 n = normalize(vec3(-grad, 1.0));
    float thickness = u_bevel + u_heightScale * u_useLuminanceAsHeight + 1.0;
    vec3 refr = vec3(0.0);
    float alpha = 0.0;
    addBand(vec3(0.5, 0.0, 1.0), 3.0, n, thickness, refr, alpha);
    addBand(vec3(0.0, 0.1, 1.0), 2.0, n, thickness, refr, alpha);
    addBand(vec3(0.0, 0.8, 0.8), 1.0, n, thickness, refr, alpha);
    addBand(vec3(0.0, 1.0, 0.1), 0.0, n, thickness, refr, alpha);
    addBand(vec3(0.8, 0.9, 0.0), -1.0, n, thickness, refr, alpha);
    addBand(vec3(1.0, 0.45, 0.0), -2.0, n, thickness, refr, alpha);
    addBand(vec3(1.0, 0.0, 0.0), -3.0, n, thickness, refr, alpha);
    refr /= BAND_SUM;
    alpha /= 7.0;
    // Bright rim: strongest within edgeGlowWidth of the edge, plus on steep slopes.
    float depth = (texture(u_height, v_uv).a - 0.5) * 2.0 * u_bevel;
    float rim = 1.0 - smoothstep(0.0, max(u_edgeGlowWidth, 1.0), depth);
    float slope = clamp(length(grad) * 0.5, 0.0, 1.0);
    vec3 glow = u_edgeGlowColor.rgb * u_edgeGlowColor.a * u_edgeGlow * clamp(rim * 0.8 + slope * 0.4, 0.0, 1.0);
    float a = layer.a * max(alpha, 0.0001);
    vec3 rgb = refr / max(alpha, 0.0001) * a + glow * layer.a;
    float outA = clamp(max(a, luma(glow) * layer.a), 0.0, 1.0);
    fragColor = vec4(min(rgb, vec3(outA)), outA);
}
