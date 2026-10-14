#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform mat3 u_inverse;  // output uv -> input uv

void main() {
    vec2 uv = (u_inverse * vec3(v_uv, 1.0)).xy;
    fragColor = (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) ? vec4(0.0) : texture(u_texture, uv);
}
