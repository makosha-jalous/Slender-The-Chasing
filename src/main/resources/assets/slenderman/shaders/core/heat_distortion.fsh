#version 150

uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform vec4 DistortParams; // x = strength, y = transparency, z = time, w = noiseTiling

in vec2 noiseUv;
in float amount;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Бесшовный периодический 2D RG-шум (заменяет _NoiseTex)
vec2 sampleNoiseRG(vec2 uv) {
    const float PERIOD = 16.0;
    vec2 p = fract(uv) * PERIOD;
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    vec2 i00 = mod(i, PERIOD);
    vec2 i10 = mod(i + vec2(1.0, 0.0), PERIOD);
    vec2 i01 = mod(i + vec2(0.0, 1.0), PERIOD);
    vec2 i11 = mod(i + vec2(1.0, 1.0), PERIOD);

    vec2 n00 = vec2(hash12(i00), hash12(i00 + 37.0));
    vec2 n10 = vec2(hash12(i10), hash12(i10 + 37.0));
    vec2 n01 = vec2(hash12(i01), hash12(i01 + 37.0));
    vec2 n11 = vec2(hash12(i11), hash12(i11 + 37.0));

    return mix(mix(n00, n10, f.x), mix(n01, n11, f.x), f.y);
}

void main() {
    float alpha = clamp(amount / max(DistortParams.y, 0.0001), 0.0, 1.0);
    if (alpha <= 0.001) {
        discard;
    }

    float t = DistortParams.z;
    vec2 n1 = sampleNoiseRG(noiseUv + vec2(t * 0.05, t * 2.0));
    vec2 n2 = sampleNoiseRG(noiseUv - vec2(t, t * 0.05));

    vec2 offset = amount * (n1 + n2 - 1.0);
    vec2 grabUv = clamp(gl_FragCoord.xy / ScreenSize + offset, vec2(0.001), vec2(0.999));

    fragColor = vec4(texture(Sampler0, grabUv).rgb, alpha);
}