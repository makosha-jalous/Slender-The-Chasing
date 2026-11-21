#version 150

// Fragment part of the original "HeatDistortion" shader:
//   uv1 = noiseUv + (_Time.x, _Time.z)  = + (t / 20, 2 t)
//   uv2 = noiseUv - (_Time.y, _Time.x)  = - (t, t / 20)
//   offset = amount * (noise1 + noise2 - 1)
//   colour = GrabTexture(screenUv + offset), alpha = amount / transparency
uniform sampler2D Sampler0;   // copy of the frame (GrabPass)
uniform sampler2D Sampler1;   // _NoiseTex (RG)
uniform vec2 ScreenSize;
uniform vec4 Params;          // x = strength, y = transparency, z = time

in vec2 noiseUv;
in float amount;

out vec4 fragColor;

void main() {
    float alpha = clamp(amount / max(Params.y, 0.0001), 0.0, 1.0);
    if (alpha <= 0.002) discard;

    float t = Params.z;
    vec2 n1 = texture(Sampler1, noiseUv + vec2(t * 0.05, t * 2.0)).rg;
    vec2 n2 = texture(Sampler1, noiseUv - vec2(t, t * 0.05)).rg;

    vec2 offset = amount * (n1 + n2 - 1.0);
    vec2 uv = clamp(gl_FragCoord.xy / ScreenSize + offset, vec2(0.001), vec2(0.999));
    fragColor = vec4(texture(Sampler0, uv).rgb, alpha);
}
