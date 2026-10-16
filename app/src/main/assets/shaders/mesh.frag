#version 300 es
precision highp float;
// Metallic-roughness shading (GGX + Lambert) with up to 4 lights, one shadow-casting light, and an
// environment for reflections: a panorama (equirectangular image) or a procedural studio.
// Shading modes: 0 PBR, 1 flat (face normals), 2 toon, 3 wireframe. World axes: +y down, +z away from the viewer.

in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
in vec3 v_bary;

uniform vec4 u_baseColor;      // sRGB, straight alpha
uniform float u_metallic;
uniform float u_roughness;
uniform vec3 u_emissive;       // sRGB
uniform sampler2D u_baseTexture;
uniform float u_hasTexture;
uniform float u_opacity;

uniform vec3 u_cameraPos;
uniform vec3 u_ambient;        // linear radiance
uniform float u_envIntensity;
uniform int u_lightCount;
uniform int u_lightType[4];    // 1 directional, 2 point
uniform vec3 u_lightColor[4];  // linear radiance
uniform vec3 u_lightPos[4];
uniform vec3 u_lightDir[4];    // direction the light travels
uniform float u_lightRadius[4];

uniform int u_shading;
uniform float u_wireWidth;     // output pixels

uniform float u_envScale;      // composition environment intensity
uniform float u_envMapOn;
uniform sampler2D u_envMap;    // equirectangular, mipmapped
uniform float u_envMaxLod;
uniform float u_envRotation;   // turns (0..1)

uniform int u_shadowLight;     // index of the shadow-casting light, -1 for none
uniform highp sampler2DShadow u_shadowMap;
uniform mat4 u_shadowMatrix;   // world -> shadow map clip space
uniform vec2 u_shadowTexel;
uniform float u_shadowDarkness;
uniform float u_shadowSoftness;

out vec4 fragColor;

const float PI = 3.14159265;

vec3 toLinear(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }
vec3 toSrgb(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Soft studio environment: bright top, dark floor, a key softbox.
vec3 environment(vec3 dir, float roughness) {
    float up = -dir.y;
    vec3 sky = mix(vec3(0.18, 0.2, 0.24), vec3(0.95, 0.97, 1.0), smoothstep(-0.05, 0.9, up));
    vec3 floorColor = mix(vec3(0.05, 0.05, 0.06), vec3(0.22, 0.22, 0.24), smoothstep(-0.6, 0.0, up));
    vec3 c = up > 0.0 ? sky : floorColor;
    float box = max(dot(dir, normalize(vec3(-0.45, -0.75, -0.5))), 0.0);
    float sharp = mix(256.0, 4.0, roughness);
    c += vec3(3.0) * pow(box, sharp) * (1.0 - roughness * 0.7);
    vec3 average = vec3(0.32, 0.33, 0.36);
    return mix(c, average, roughness * roughness);
}

float distributionGGX(float NdotH, float a) {
    float a2 = a * a;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float geometrySmith(float NdotV, float NdotL, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 fresnel(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
}

vec3 envSample(vec3 dir, float lod) {
    // +y is down: "up" is -y.
    float u = atan(dir.z, dir.x) / (2.0 * PI) + 0.5 + u_envRotation;
    float v = acos(clamp(-dir.y, -1.0, 1.0)) / PI;
    return toLinear(textureLod(u_envMap, vec2(u, v), lod).rgb);
}

vec3 reflection(vec3 dir, float roughness) {
    if (u_envMapOn > 0.5) return envSample(dir, roughness * u_envMaxLod) * u_envScale;
    return environment(dir, roughness) * u_envIntensity * u_envScale;
}

// 1 = lit, 0 = in shadow (percentage-closer filtering, 3x3 taps spread by the softness).
float shadowVisibility(vec3 N, vec3 L) {
    vec4 c = u_shadowMatrix * vec4(v_worldPos, 1.0);
    vec3 p = c.xyz / c.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float bias = mix(0.0025, 0.0006, clamp(dot(N, L), 0.0, 1.0));
    if (u_shadowSoftness < 0.01) return texture(u_shadowMap, vec3(p.xy, p.z - bias));
    float sum = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 o = vec2(float(x), float(y)) * u_shadowTexel * u_shadowSoftness;
            sum += texture(u_shadowMap, vec3(p.xy + o, p.z - bias));
        }
    }
    return sum / 9.0;
}

// Analytic approximation of the split-sum environment BRDF (Karis).
vec3 envBrdf(vec3 f0, float roughness, float NdotV) {
    vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = roughness * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * NdotV)) * r.x + r.y;
    vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + ab.y;
}

void main() {
    vec4 base = u_baseColor;
    if (u_hasTexture > 0.5) base *= texture(u_baseTexture, v_uv);

    if (u_shading == 3) {
        // Wireframe: anti-aliased edges, unlit.
        vec3 w = fwidth(v_bary) * u_wireWidth;
        vec3 s = smoothstep(w * 0.5, w, v_bary);
        float edge = 1.0 - min(min(s.x, s.y), s.z);
        if (edge < 0.01) discard;
        vec3 wire = clamp(base.rgb + u_emissive, 0.0, 1.0);
        float wa = edge * base.a * u_opacity;
        fragColor = vec4(wire * wa, wa);
        return;
    }

    vec3 V = normalize(u_cameraPos - v_worldPos);
    vec3 N = normalize(v_normal);
    if (!gl_FrontFacing) N = -N;
    if (u_shading == 1) {
        N = normalize(cross(dFdx(v_worldPos), dFdy(v_worldPos)));
        if (dot(N, V) < 0.0) N = -N;
    }
    float NdotV = max(dot(N, V), 0.001);
    vec3 albedo = toLinear(base.rgb);
    float metallic = clamp(u_metallic, 0.0, 1.0);
    float roughness = clamp(u_roughness, 0.03, 1.0);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    float alpha = roughness * roughness;

    vec3 color = vec3(0.0);
    bool toon = u_shading == 2;
    for (int i = 0; i < 4; i++) {
        if (i >= u_lightCount) break;
        vec3 L;
        float attenuation = 1.0;
        if (u_lightType[i] == 2) {
            vec3 toLight = u_lightPos[i] - v_worldPos;
            float dist = length(toLight);
            L = toLight / max(dist, 0.0001);
            float x = clamp(dist / u_lightRadius[i], 0.0, 1.0);
            attenuation = (1.0 - x * x) * (1.0 - x * x);
        } else {
            L = -normalize(u_lightDir[i]);
        }
        float NdotL = max(dot(N, L), 0.0);
        if (NdotL <= 0.0) continue;
        if (i == u_shadowLight) attenuation *= mix(1.0, shadowVisibility(N, L), u_shadowDarkness);
        vec3 H = normalize(V + L);
        if (toon) {
            float band = NdotL > 0.55 ? 1.0 : (NdotL > 0.2 ? 0.55 : 0.25);
            float spec = step(0.94, max(dot(N, H), 0.0)) * (1.0 - roughness);
            color += (albedo * band + vec3(spec) * 0.6) * u_lightColor[i] * attenuation;
            continue;
        }
        float NdotH = max(dot(N, H), 0.0);
        vec3 F = fresnel(max(dot(H, V), 0.0), f0);
        float D = distributionGGX(NdotH, alpha);
        float G = geometrySmith(NdotV, NdotL, roughness);
        vec3 specular = D * G * F / (4.0 * NdotV * NdotL + 0.0001);
        vec3 kd = (1.0 - F) * (1.0 - metallic);
        // Light intensity 1 is calibrated so a white diffuse surface facing the light is ~white.
        vec3 radiance = u_lightColor[i] * attenuation * PI;
        color += (kd * albedo / PI + specular) * radiance * NdotL;
    }

    vec3 ambientDiffuse = u_ambient * albedo * (1.0 - metallic);
    if (u_envMapOn > 0.5) ambientDiffuse += envSample(N, max(u_envMaxLod - 1.0, 0.0)) * albedo * (1.0 - metallic) * u_envScale * 0.6;
    if (toon) {
        color += ambientDiffuse + albedo * 0.08;
        // Darken the silhouette like an ink line.
        color *= mix(1.0, 0.25, smoothstep(0.62, 0.72, 1.0 - NdotV));
    } else {
        vec3 R = reflect(-V, N);
        color += ambientDiffuse + reflection(R, roughness) * envBrdf(f0, roughness, NdotV);
    }
    color += toLinear(u_emissive) * 2.0;

    vec3 mapped = toSrgb(aces(color));
    float a = base.a * u_opacity;
    fragColor = vec4(mapped * a, a);
}
