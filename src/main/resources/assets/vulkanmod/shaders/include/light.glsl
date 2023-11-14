//light.glsl
//#pragma once
const float MINECRAFT_LIGHT_POWER  = (0.6);
const float MINECRAFT_AMBIENT_LIGHT = (0.4);

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    float light0 = max(0.0, dot(normalize(lightDir0), normal));
    float light1 = max(0.0, dot(normalize(lightDir1), normal));
    color.rgb *= fma(light0 + light1, MINECRAFT_LIGHT_POWER, MINECRAFT_AMBIENT_LIGHT); //lightAccum
    return color;
}

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, bitfieldExtract(uv, 4, 8), 0); //return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, bitfieldExtract(uv, 4, 8), 0);
}

vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    return (vertexDistance <= fogStart) ? inColor : mix(inColor, fogColor, min(smoothstep(fogStart, fogEnd, vertexDistance), 1.0) * fogColor.a);
}