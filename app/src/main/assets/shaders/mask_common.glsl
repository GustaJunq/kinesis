// Shared by quad.frag and quad_oes.frag (prepended at load time).
// Masks are evaluated in layer-local pixels; u_maskMatrix maps v_local into that space.
uniform mat4 u_maskMatrix;
uniform int u_maskCount;
uniform vec4 u_maskRect[4];     // centre.xy, size.xy
uniform vec4 u_maskParams[4];   // shape (0 rect, 1 ellipse), mode (0 add, 1 subtract, 2 intersect), roundness, feather
uniform vec2 u_maskExtra[4];    // opacity, inverted (0/1)
uniform vec4 u_wipe;            // enabled, progress, anchoredRight, unused
uniform vec4 u_rectBounds;      // content rectangle for the wipe: x, y, w, h

float sdRoundBox(vec2 p, vec2 halfSize, float r) {
    r = min(r, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float maskCoverage(vec2 local) {
    if (u_maskCount == 0) return 1.0;
    vec2 p = (u_maskMatrix * vec4(local, 0.0, 1.0)).xy;
    float coverage = (u_maskParams[0].y < 0.5) ? 0.0 : 1.0;
    for (int i = 0; i < 4; i++) {
        if (i >= u_maskCount) break;
        vec2 c = u_maskRect[i].xy;
        vec2 halfSize = max(u_maskRect[i].zw * 0.5, vec2(0.0001));
        float d;
        if (u_maskParams[i].x < 0.5) {
            d = sdRoundBox(p - c, halfSize, u_maskParams[i].z);
        } else {
            vec2 q = (p - c) / halfSize;
            d = (length(q) - 1.0) * min(halfSize.x, halfSize.y);
        }
        float feather = max(u_maskParams[i].w, 1.0);
        float m = 1.0 - smoothstep(-feather * 0.5, feather * 0.5, d);
        if (u_maskExtra[i].y > 0.5) m = 1.0 - m;
        m *= u_maskExtra[i].x;
        float mode = u_maskParams[i].y;
        if (mode < 0.5) coverage = max(coverage, m);
        else if (mode < 1.5) coverage = min(coverage, 1.0 - m);
        else coverage = min(coverage, m);
    }
    return coverage;
}

float wipeCoverage(vec2 local) {
    if (u_wipe.x < 0.5) return 1.0;
    float w = max(u_rectBounds.z, 1.0);
    float x = (local.x - u_rectBounds.x) / w;   // 0 at the left edge, 1 at the right edge
    float edge = 2.0 / w;
    if (u_wipe.z > 0.5) return smoothstep(1.0 - u_wipe.y - edge, 1.0 - u_wipe.y + edge, x);
    return 1.0 - smoothstep(u_wipe.y - edge, u_wipe.y + edge, x);
}
