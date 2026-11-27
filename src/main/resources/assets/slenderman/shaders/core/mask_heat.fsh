#version 150

// Distortion of the original, as seen on the screenshots of the game:
//  1. the picture is bent: wavy columns (trees), big blocks (slabs) that are pushed aside with hard edges, a slow bend of the whole picture
//  2. the bent picture lies over the normal one (a ghost: the sign "PARK" twice)
//  3. the picture is brighter and much harder: bright parts burn out to white (no colour split: the channels are NOT moved apart)
uniform sampler2D Sampler0;   // copy of the frame
uniform sampler2D Sampler1;   // noise (RG)
uniform vec2 ScreenSize;
uniform vec4 Params;          // x = time (s), y = seed of this flash, z = test mode, w = how strong the bent copy lies over the picture (0..1)
uniform vec4 Gain;            // rgb = brightness of the picture, w = contrast
uniform vec4 Warp;            // x = wavy columns, y = slabs, z = slow bend (all in screen widths), w = soft edge of the rectangle (0..0.5)

in vec2 localUv;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    if (Params.z > 0.5) { fragColor = vec4(1.0, 0.0, 0.0, 0.40); return; }

    float edge = min(min(localUv.x, 1.0 - localUv.x), min(localUv.y, 1.0 - localUv.y));
    float soft = smoothstep(0.0, max(Warp.w, 0.0001), edge);
    if (soft <= 0.002) discard;

    float t = Params.x;
    float seed = Params.y;

    // 1a. the slow bend of the original formula (two noises that run against each other)
    vec2 n1 = texture(Sampler1, localUv * vec2(3.0, 2.0) + vec2(t * 0.05, t * 2.0) + seed).rg;
    vec2 n2 = texture(Sampler1, localUv * vec2(3.0, 2.0) - vec2(t, t * 0.05)).rg;
    vec2 bend = (n1 + n2 - 1.0) * Warp.z;

    // 1b. wavy columns: the sideways push depends mostly on x, so the trunks bend and the picture streaks up and down
    float wave = texture(Sampler1, vec2(localUv.x * 11.0 + seed * 3.1, localUv.y * 0.6 + t * 1.3)).r - 0.5;
    float wave2 = texture(Sampler1, vec2(localUv.x * 27.0 - seed, localUv.y * 0.3 - t * 0.9)).g - 0.5;
    vec2 columns = vec2((wave + 0.5 * wave2) * Warp.x, 0.35 * wave2 * Warp.x);

    // 1c. slabs: whole rectangles of the picture are pushed (up/down more than sideways), hard edges
    vec2 cell = floor(localUv * vec2(9.0, 7.0));
    float r1 = hash(cell + seed);
    float r2 = hash(cell * 1.7 + seed + 4.0);
    float slabOn = step(0.58, r1);
    vec2 slab = slabOn * vec2((r2 - 0.5) * 0.6, (r1 - 0.79)) * Warp.y;

    vec2 uv = clamp(gl_FragCoord.xy / ScreenSize + bend + columns + slab, vec2(0.001), vec2(0.999));
    vec3 c = texture(Sampler0, uv).rgb * Gain.rgb;

    // some slabs burn out completely (the white blocks)
    c *= 1.0 + 0.8 * slabOn * step(0.78, r2);

    // 3. harder picture: dark stays dark, light burns out
    c = clamp((c - 0.30) * Gain.w + 0.30, 0.0, 1.0);

    fragColor = vec4(c, Params.w * soft);
}
