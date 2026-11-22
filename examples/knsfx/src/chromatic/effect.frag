#version 300 es
precision highp float;

uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uTime;
uniform float uProgress;
uniform float intensity;
uniform float angle;
uniform bool radial;

in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    // Até 24 px de separação com intensidade 100%.
    vec2 px = 24.0 * intensity / uResolution;
    float a = radians(angle);
    vec2 dir = radial ? (vTexCoord - 0.5) * 2.0 : vec2(cos(a), sin(a));
    vec2 off = dir * px;
    vec4 c = texture(uTexture, vTexCoord);
    vec4 r = texture(uTexture, vTexCoord + off);
    vec4 b = texture(uTexture, vTexCoord - off);
    // Cores pré-multiplicadas: alfa do maior dos três, para a franja aparecer fora da borda.
    fragColor = vec4(r.r, c.g, b.b, max(c.a, max(r.a, b.a)));
}
