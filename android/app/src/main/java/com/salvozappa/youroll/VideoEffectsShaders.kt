// Port of YouRec/VideoEffects/VideoEffects.metal (the editor's per-pixel kernels)
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
//
// GLSL ES 3.00 fragment shaders, one program per pass (and, like Metal's
// function constant EFFECT_KIND, one specialised `ve_effect` per effect). All
// creative operations receive display-referred, linear BT.709 (sRGB transfer
// decoded) colours in RGBA16F textures. Spatial parameters scale from a 1080 px
// short edge, as in Metal.
//
// Coordinates follow Metal: `uv` and the pixel index `q` are top-down. GL
// textures store the picture bottom-up, so every image read goes through `tex`
// / `fetch`, which flip y, and every pass writes its top-down pixel q at the GL
// fragment (q.x, h-1-q.y).
//
// Not ported (TODO.md): the mask, depth, matte, keyer and Night fix kernels
// (ve_depth*, ve_bokeh*, ve_guided*, ve_personCoverage, ve_keyAlpha,
// ve_morphology, ve_bg*, ve_personHint, ve_screenReference, ve_subjectWeight,
// ve_mask*, ve_matte*, ve_isolationAlpha (Color Isolation computes the same
// per-frame hue weight inline), nf_*, recorderFocusPeaking), and the person,
// depth and key branches of ve_effect (kinds 8, 18, 19 and the masked paths of
// 4, 7 and 11).
package com.salvozappa.youroll

object VideoEffectsShaders {
    const val vertex = """#version 300 es
in vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
"""

    /** Shared helpers (Metal: the free functions before the kernels). */
    private const val prelude = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
out vec4 fragColor;
uniform vec2 outSize;
const float PI = 3.14159265358979;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// Top-down pixel index of this fragment, and its top-down normalised centre.
uvec2 pixel() { return uvec2(uint(gl_FragCoord.x), uint(outSize.y - gl_FragCoord.y)); }
vec2 fragUV() { return vec2(gl_FragCoord.x, outSize.y - gl_FragCoord.y) / outSize; }
vec4 tex(sampler2D s, vec2 uv) { return texture(s, vec2(uv.x, 1.0 - uv.y)); }
vec4 fetch(sampler2D s, uvec2 q) { ivec2 n = textureSize(s, 0); return texelFetch(s, ivec2(int(q.x), n.y - 1 - int(q.y)), 0); }
vec2 texSize(sampler2D s) { return vec2(textureSize(s, 0)); }
float luma(vec3 c) { return dot(c, LUMA); }
vec3 srgb(vec3 c) { return mix(1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, c * 12.92, lessThanEqual(c, vec3(0.0031308))); }
vec3 lin(vec3 c) { return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThanEqual(c, vec3(0.04045))); }
vec3 lab(vec3 c) {
    vec3 v = pow(max(vec3(dot(c, vec3(.4122214708, .5363325363, .0514459929)), dot(c, vec3(.2119034982, .6806995451, .1073969566)), dot(c, vec3(.0883024619, .2817188376, .6299787005))), vec3(0.0)), vec3(1.0 / 3.0));
    return vec3(dot(v, vec3(.2104542553, .793617785, -.0040720468)), dot(v, vec3(1.9779984951, -2.428592205, .4505937099)), dot(v, vec3(.0259040371, .7827717662, -.808675766)));
}
vec3 unlab(vec3 c) {
    vec3 v = vec3(c.x + .3963377774 * c.y + .2158037573 * c.z, c.x - .1055613458 * c.y - .0638541728 * c.z, c.x - .0894841775 * c.y - 1.291485548 * c.z); v = v * v * v;
    return vec3(dot(v, vec3(4.0767416621, -3.3077115913, .2309699292)), dot(v, vec3(-1.2684380046, 2.6097574011, -.3413193965)), dot(v, vec3(-.0041960863, -.7034186147, 1.707614701)));
}
vec3 hsv(vec3 c) {
    float hi = max(c.r, max(c.g, c.b)), lo = min(c.r, min(c.g, c.b)), d = hi - lo, h = 0.0;
    if (d > 1e-6) { if (hi == c.r) h = mod((c.g - c.b) / d + 6.0, 6.0); else if (hi == c.g) h = (c.b - c.r) / d + 2.0; else h = (c.r - c.g) / d + 4.0; }
    return vec3(h / 6.0, hi > 1e-6 ? d / hi : 0.0, hi);
}
float hueDistance(float a, float b) { float d = abs(a - b); return min(d, 1.0 - d); }
uint hash32(uint n) { n ^= n >> 16; n *= 0x7feb352du; n ^= n >> 15; n *= 0x846ca68bu; n ^= n >> 16; return n; }
float random01(uvec3 v) { return float(hash32(v.x ^ hash32(v.y + 0x9e3779b9u) ^ hash32(v.z + 0x85ebca6bu)) >> 8) / 16777216.0; }
float noise(vec2 pixelPos, uint frame, uint seed) {
    ivec2 cell = ivec2(floor(pixelPos)); vec2 f = fract(pixelPos); f = f * f * (3.0 - 2.0 * f);
    float a = random01(uvec3(uvec2(cell), frame + seed)), b = random01(uvec3(uvec2(cell + ivec2(1, 0)), frame + seed));
    float c = random01(uvec3(uvec2(cell + ivec2(0, 1)), frame + seed)), d = random01(uvec3(uvec2(cell + ivec2(1, 1)), frame + seed));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) * 2.0 - 1.0;
}
vec3 sampleImage(sampler2D image, vec2 uv) { return tex(image, uv).rgb; }
"""

    /** Metal `EffectUniforms` and the effect-only helpers. */
    private const val effectPrelude = """
uniform uint uKind, uFrame, uFlags, uCount;
uniform float uTime, uIntensity, uDuration, uFps;
uniform vec4 p[40];
uniform vec4 palette[16];
uniform vec4 thresholds[4];
uniform sampler2D input0, aux, mask, background, extra, wrapImage, noiseLattice;
// The random lattice is shared within a frame. Interpolating the already
// correlated channels is equivalent to interpolating four separate hashes.
vec3 grain(vec3 c, vec2 pixelPos, uint frame, float strength, float size, bool mono) {
    float y = clamp(luma(c), 0.0, 1.0), a = strength * .12 * pow(max(0.0, 4.0 * y * (1.0 - y)), .7);
    vec2 point = pixelPos / size, cell = floor(point), f = fract(point); f = f * f * (3.0 - 2.0 * f);
    vec4 value = tex(noiseLattice, (cell + .5 + f) / texSize(noiseLattice)) * 2.0 - 1.0;
    return clamp(c * exp((mono ? vec3(value.a) : value.rgb) * a * 2.0), 0.0, 1.0);
}
float sCurve(float y, float contrast) { y = clamp(y, 0.0, 1.0); float a = pow(y, contrast), b = pow(1.0 - y, contrast); return a / max(a + b, 1e-6); }
// Controls operate on perceptual tone values; image math and compositing stay
// linear. A 0.5 linear pivot crushed normal skin at the default contrast.
float curve(float y, float contrast) { return lin(vec3(clamp((srgb(vec3(y)).x - .5) * contrast + .5, 0.0, 1.0))).x; }
float gate(vec2 uv, float radius) {
    vec2 d = abs(uv - .5) - vec2(.5 - radius);
    return 1.0 - smoothstep(radius - .004, radius + .004, length(max(d, 0.0)) + min(max(d.x, d.y), 0.0));
}
float bayer(uvec2 q, uint size) {
    uint value = 0u;
    for (uint bit = 0u; (1u << bit) < size; bit++) { uint x = (q.x >> bit) & 1u, y = (q.y >> bit) & 1u; value = (value << 2) | ((x ^ y) * 2u + y); }
    return (float(value) + .5) / float(size * size);
}
// Primary grading is original code, not a LUT or a camera white-balance setting.
vec3 gradeGamut(vec3 c) {
    float y = clamp(luma(c), 0.0, 1.0), amount = 1.0; vec3 delta = c - y;
    for (int i = 0; i < 3; i++) {
        if (delta[i] > 0.0) amount = min(amount, (1.0 - y) / delta[i]);
        else if (delta[i] < 0.0) amount = min(amount, -y / delta[i]);
    }
    return y + delta * max(0.0, amount);
}
// C1 rational shoulder fits a known maximum to white, preserving every level
// and a unit slope at the knee. Mirroring it gives a toe for negative Blacks.
float gradeShoulder(float value, float maximum) {
    if (maximum <= 1.0 || value <= .8) return value;
    float a = (maximum - .8) / .2, t = (value - .8) / (maximum - .8);
    return .8 + .2 * a * t / (1.0 + (a - 1.0) * t);
}
vec3 colorCorrection(vec3 c) {
    bool neutral = p[0].x == 0.0 && p[1].x == 1.0 && p[2].x == 0.0 && p[3].x == 0.0 && p[4].x == 0.0 && p[5].x == 0.0 && p[7].x == 0.0 && p[8].x == 0.0 && p[9].x == 1.0 && p[10].x == 0.0 && p[11].x == 0.0;
    if (neutral) return c; // Pivot alone is inert.
    float temperature = p[7].x, tint = p[8].x;
    vec3 gains = exp2(vec3(.45 * temperature + .2 * tint, -.2 * tint, -.45 * temperature + .2 * tint));
    c *= gains / dot(gains, LUMA) * exp2(p[0].x);
    float before = luma(c), v = srgb(vec3(max(before, 0.0))).x;
    v = gradeShoulder(v, srgb(vec3(exp2(p[0].x))).x);
    float contrast = p[1].x, pivot = p[6].x;
    if (contrast != 1.0 && v > 0.0 && v < 1.0) {
        float odds = pivot / (1.0 - pivot);
        v = 1.0 / (1.0 + pow(odds, contrast - 1.0) * pow((1.0 - v) / v, contrast));
    }
    v += p[2].x * v * (1.0 - v) * (1.0 - v);
    v += p[3].x * v * v * (1.0 - v);
    v += .12 * p[4].x * pow(1.0 - v, 4.0) + .12 * p[5].x * pow(v, 4.0);
    v = gradeShoulder(v, 1.0 + .12 * p[5].x);
    v = 1.0 - gradeShoulder(1.0 - v, 1.0 - .12 * p[4].x);
    float target = lin(vec3(v)).x;
    c = before > 1e-7 ? c * (target / before) : vec3(target);
    vec3 perceptual = lab(max(c, 0.0));
    float chroma = length(perceptual.yz);
    // Vibrance mostly affects muted colors; protect common skin hues from boosts.
    vec3 h = hsv(srgb(max(c, 0.0)));
    float skin = smoothstep(.025, .055, h.x) * (1.0 - smoothstep(.12, .16, h.x)) * smoothstep(.08, .2, h.y);
    float vibrance = p[10].x * (1.0 - clamp(chroma / .25, 0.0, 1.0));
    if (vibrance > 0.0) vibrance *= 1.0 - .6 * skin;
    if (p[11].x != 0.0 && chroma > 1e-5) {
        float angle = p[11].x * PI / 180.0, cs = cos(angle), sn = sin(angle);
        perceptual.yz = vec2(cs * perceptual.y - sn * perceptual.z, sn * perceptual.y + cs * perceptual.z);
        c = unlab(perceptual); c *= target / max(luma(c), 1e-7);
    }
    c = gradeGamut(c);
    float gain = p[9].x * (1.0 + vibrance);
    if (gain > 1.0) {
        vec3 delta = c - target; float ratio = 0.0;
        for (int i = 0; i < 3; i++) ratio = max(ratio, delta[i] > 0.0 ? delta[i] / max(1.0 - target, 1e-7) : -delta[i] / max(target, 1e-7));
        // A monotone saturation shoulder: full gain on muted colors.
        gain /= sqrt(1.0 + (gain * gain - 1.0) * ratio * ratio);
    }
    c = target + (c - target) * gain;
    // Compress chroma toward neutral at the same luma instead of clipping RGB.
    return gradeGamut(c);
}
// A single sampling map drives the picture. Coordinates are normalised and top-down.
vec2 effectPoint(vec2 uv, vec2 size) {
    float pixelScale = min(size.x, size.y) / 1080.0;
#if EFFECT_KIND == 22
    {
        // Polar distances are in pixels, so a 120-degree wedge stays 120
        // degrees in either orientation. The first clockwise sector is kept.
        uint count = uint(clamp(round(p[0].x), 2.0, 9.0));
        vec2 center = vec2(p[1].x, p[2].x);
        vec2 d = (uv - center) * size;
        vec2 point = uv;
        if (dot(d, d) >= 1e-8) {
            float axis = p[3].x * (PI / 180.0) - PI / 2.0;
            float turn = 2.0 * PI;
            float span = turn / float(count);
            float angle = fract((atan(d.y, d.x) - axis) / turn) * turn;
            uint sector = min(uint(angle / span), count - 1u);
            if (sector != 0u) {
                float local = angle - float(sector) * span;
                // Odd rings repeat the entire wedge by rotation. Even rings alternate reflections.
                if ((count & 1u) == 0u && (sector & 1u) != 0u) local = span - local;
                float mapped = axis + local;
                point = center + length(d) * vec2(cos(mapped), sin(mapped)) / size;
            }
        }
        // Inverse-map the source rotation in picture pixels.
        if (p[6].x != 0.0) {
            float a = -p[6].x * (PI / 180.0);
            vec2 offset = (point - center) * size;
            float c = cos(a), s = sin(a);
            point = center + vec2(c * offset.x - s * offset.y, s * offset.x + c * offset.y) / size;
        }
        point -= vec2(p[4].x, p[5].x);
        if (all(greaterThanEqual(point, vec2(0.0))) && all(lessThanEqual(point, vec2(1.0)))) return point;
        return 1.0 - abs(2.0 * fract(point * 0.5) - 1.0);
    }
#elif EFFECT_KIND == 9
    {
        float t = uTime, speed = max(.001, p[1].x), z = 1.0;
        float release = (uFlags & 4u) != 0u ? p[39].x : speed + .08 + p[2].x;
        if (t < speed) z = mix(1.0, p[0].x, pow(clamp(t / speed, 0.0, 1.0), 5.0));
        else if (t < speed + .08) z = p[0].x * (1.0 + .03 * sin((t - speed) / .08 * PI));
        else if ((p[3].x > .5 || (uFlags & 4u) != 0u) && t > release) { float f = clamp((t - release) / speed, 0.0, 1.0); z = mix(p[0].x, 1.0, f * f * (3.0 - 2.0 * f)); }
        else z = p[0].x;
        vec2 target = vec2(p[5].x, p[6].x); return (uv - target) / z + target;
    }
#elif EFFECT_KIND == 12
    return (uv - .5) / (1.0 + p[1].x * clamp(uTime / max(.001, p[0].x), 0.0, 1.0)) + .5;
#elif EFFECT_KIND == 14
    {
        uint frame = uint(floor(uTime * p[0].x)); vec2 move = vec2(noise(vec2(float(frame) * .18, 3.0), 0u, 7u) * 2.0, noise(vec2(float(frame) * .18, 8.0), 0u, 11u) * 3.0) * p[3].x * pixelScale;
        return (uv - .5) / 1.01 + .5 + move / size;
    }
#elif EFFECT_KIND == 16
    {
        vec2 move = vec2(noise(vec2(float(uFrame) * .13, 2.0), 0u, 7u), noise(vec2(float(uFrame) * .13, 9.0), 0u, 3u)) * p[3].x * pixelScale * 2.0;
        return (uv - .5) / 1.006 + .5 + move / size;
    }
#elif EFFECT_KIND == 17
    {
        vec2 move = vec2(noise(vec2(float(uFrame) * .16, 2.0), 0u, 5u) * 2.0, noise(vec2(float(uFrame) * .16, 9.0), 0u, 17u) * 3.0) * p[2].x * pixelScale;
        return (uv - .5) / 1.012 + .5 + move / size;
    }
#else
    return uv;
#endif
}
"""

    /** Metal `ve_effect`, specialised by EFFECT_KIND. */
    private const val effect = """
void main() {
    vec2 size = outSize, uv = fragUV(); uvec2 q = pixel();
#if EFFECT_KIND == 22
    vec4 reflected = tex(input0, effectPoint(uv, size));
    fragColor = uIntensity >= 1.0 ? reflected : mix(tex(input0, uv), reflected, uIntensity);
    return;
#else
#if EFFECT_KIND == 7
    if (uCount < 2u) { fragColor = tex(input0, uv); return; }
#endif
    // Quantization reads the smoothed auxiliary image at full intensity.
    vec3 original = (EFFECT_KIND == 7 && p[3].x > 0.0 && uIntensity >= 1.0) ? vec3(0.0) : sampleImage(input0, uv);
    vec3 c = original, outc = c; float y = luma(c), pixelScale = min(size.x, size.y) / 1080.0;
#if EFFECT_KIND == 23
    {
        vec2 reach = 0.12 * min(size.x, size.y) / size;
        vec2 red = vec2(p[0].x, p[1].x), green = vec2(p[2].x, p[3].x), blue = vec2(p[4].x, p[5].x);
        outc = vec3(sampleImage(input0, uv - red * reach).r, sampleImage(input0, uv - green * reach).g, sampleImage(input0, uv - blue * reach).b);
    }
#elif EFFECT_KIND == 24
    // A photographic negative complements display values, so mid-gray stays mid-gray.
    outc = lin(1.0 - srgb(clamp(c, 0.0, 1.0)));
#elif EFFECT_KIND == 20
    outc = colorCorrection(c);
#elif EFFECT_KIND == 0
    { // Silent Tint: contrast and dye in linear light.
        const vec3 tints[5] = vec3[5](vec3(.243137, .435294, .658824), vec3(.85098, .643137, .254902), vec3(.721569, .262745, .184314), vec3(.431373, .560784, .305882), vec3(.541176, .415686, .270588));
        vec3 tint = lin(tints[int(p[0].x)]); float l = curve(y, p[2].x);
        outc = p[1].x < .5 ? l * tint : mix(tint * .15, vec3(1.0), smoothstep(0.0, 1.0, l));
    }
#elif EFFECT_KIND == 1
    { // Three-Strip; protect skin hue from the red-separation push.
        vec3 enc = srgb(c), h = hsv(enc); float skin = smoothstep(.03, .055, h.x) * (1.0 - smoothstep(.115, .15, h.x)) * smoothstep(.12, .3, h.y);
        vec3 separation = c - (c.yzx + c.zxy) * .5;
        outc = c + separation * (p[0].x - 1.0) * (1.0 - .65 * skin);
        float red = 1.0 - smoothstep(15.0 / 360.0, 25.0 / 360.0, hueDistance(h.x, 0.0)); outc.gb -= red * p[1].x * .15 * (1.0 - skin);
        float l = luma(outc), s = l * l * (3.0 - 2.0 * l); outc += (s - l) * .14;
        outc *= vec3(1.0 + p[2].x * .06, 1.0, 1.0 - p[2].x * .06);
        float high = max(outc.r, max(outc.g, outc.b)); if (high > .9) outc = .9 + (outc - .9) / (1.0 + max(0.0, high - .9)); outc = mix(outc, c, skin * .35);
    }
#elif EFFECT_KIND == 2
    { vec3 overlay = y < .5 ? 2.0 * c * y : 1.0 - 2.0 * (1.0 - c) * (1.0 - y); vec3 bleached = mix(vec3(y), overlay, .5); bleached = mix(vec3(luma(bleached)), bleached, 1.0 - .3 * p[0].x); outc = mix(c, bleached, p[0].x); }
#elif EFFECT_KIND == 3
    { vec3 h = hsv(srgb(c)); float darkness = p[0].x; outc = mix(vec3(y), c, .4) * exp2(-3.0 * darkness); outc *= mix(vec3(1.0), vec3(.55, .7, 1.0), p[1].x);
      float sky = (1.0 - smoothstep(.25, .38, uv.y)) * smoothstep(.5, .85, y) * (1.0 - smoothstep(.08, .18, hueDistance(h.x, .61))); outc *= 1.0 - .5 * sky;
      if (p[2].x > .5) outc = mix(outc, vec3(y * exp2(-.7 * darkness)), smoothstep(.94, .99, y) * .7);
      outc *= 1.0 - .15 * smoothstep(.2, .75, length(uv - .5)); }
#elif EFFECT_KIND == 4
    { float w;
      if (p[0].x > .5 || (uFlags & 8u) != 0u) w = tex(mask, uv).r;
      else { vec3 h = hsv(srgb(c)), target = hsv(srgb(p[1].xyz)); float r = p[2].x / 360.0, soft = max(.001, p[3].x * .1); w = (1.0 - smoothstep(r, r + soft, hueDistance(h.x, target.x))) * smoothstep(.08, .2, h.y); }
      outc = mix(vec3(y), c, w); }
#elif EFFECT_KIND == 5
    { float l = sCurve(srgb(vec3(dot(c, vec3(.4, .5, .1)))).x, p[0].x); l = lin(vec3(l * (1.0 - 2.0 * p[1].x * (1.0 - l)))).x; outc = vec3(l); if (p[3].x > .5) outc *= mix(vec3(.94, .97, 1.0), vec3(1.0), l); if (p[2].x > .5) outc = grain(outc, vec2(q) / pixelScale, uFrame, .25, 1.0, true); }
#elif EFFECT_KIND == 6
    { float perceptual = srgb(vec3(y)).x; float l = sCurve(perceptual, p[0].x); l = lin(vec3(mix(l, smoothstep(.35, .65, perceptual), .4))).x; vec2 d = (uv - .5) * 2.0; if (p[2].x > .5) d.x *= size.x / size.y;
      float v = clamp(1.0 - pow(clamp(length(d) / 1.25, 0.0, 1.0), 2.5), 0.0, 1.0); l *= mix(1.0, v, p[1].x); outc = vec3(l); if (p[3].x > .5) outc *= lin(vec3(.82, .84, .52)); }
#elif EFFECT_KIND == 7
    { // Palette lookup: centroids are Oklab, thresholds linear luma.
      if (p[3].x > 0.0) c = sampleImage(aux, uv); y = luma(c); c += .2 * (y * y * (3.0 - 2.0 * y) - y);
      vec3 v = lab(clamp(c, 0.0, 1.0));
      if (p[4].x > .5) { float spread = 0.0; for (uint i = 1u; i < uCount; i++) spread += distance(palette[i - 1u].xyz, palette[i].xyz); spread /= float(max(1u, uCount - 1u)); v.x += (bayer(q, uint(p[4].x) < 2u ? 4u : 8u) - .5) * spread; }
      uint best = 0u;
      if (p[1].x > .5 || (p[2].x >= 1.0 && p[2].x <= 3.0)) { float l = luma(clamp(unlab(v), 0.0, 1.0)); for (uint i = 0u; i + 1u < uCount; i++) if (l >= thresholds[i / 4u][i % 4u]) best++; outc = p[1].x > .5 ? lin(vec3(float(best) / float(max(1u, uCount - 1u)))) : unlab(palette[best].xyz); }
      else { float closest = 1e10; for (uint i = 0u; i < uCount; i++) { vec3 e = v - palette[i].xyz; float d = dot(e, e); if (d < closest) { closest = d; best = i; } } outc = unlab(palette[best].xyz); } }
#elif EFFECT_KIND == 9
    { float t = uTime, speed = max(.001, p[1].x), z = 1.0, velocity = 0.0; float release = (uFlags & 4u) != 0u ? p[39].x : speed + .08 + p[2].x;
      if (t < speed) { float f = clamp(t / speed, 0.0, 1.0); z = mix(1.0, p[0].x, pow(f, 5.0)); velocity = 5.0 * pow(f, 4.0) / speed; }
      else if (t < speed + .08) { float f = (t - speed) / .08; z = p[0].x * (1.0 + .03 * sin(f * PI)); velocity = .03 * PI / .08 * cos(f * PI); }
      else if ((p[3].x > .5 || (uFlags & 4u) != 0u) && t > release) { float f = clamp((t - release) / speed, 0.0, 1.0); z = mix(p[0].x, 1.0, f * f * (3.0 - 2.0 * f)); velocity = -6.0 * f * (1.0 - f) / speed; }
      else z = p[0].x;
      vec2 target = vec2(p[5].x, p[6].x), point = effectPoint(uv, size); float blur = min(.12, abs(velocity) * .002 * p[4].x); outc = vec3(0.0);
      for (uint i = 0u; i < 8u; i++) outc += sampleImage(input0, point + (point - target) * blur * (float(i) / 7.0 - .5)) / 8.0; }
#elif EFFECT_KIND == 11
    { const float ratios[4] = float[4](2.39, 2.20, 1.85, 1.33); float ratio = ratios[int(p[0].x)], aspect = size.x / size.y;
      bool bar = aspect < ratio ? abs(uv.y - .5) > .5 * aspect / ratio : abs(uv.x - .5) > .5 * ratio / aspect;
      const vec3 colors[3] = vec3[3](vec3(.35, .6, 1.0), vec3(1.0, .6, .25), vec3(1.0));
      outc = c + sampleImage(aux, uv) * colors[int(p[3].x)] * p[2].x;
      if (bar) outc = p[1].x > .5 ? sampleImage(background, uv) * .6 : vec3(0.0); }
#elif EFFECT_KIND == 12
    { outc = sampleImage(input0, effectPoint(uv, size));
      if (p[2].x > .5) { outc = mix(vec3(luma(outc)), outc, .95); outc *= 1.0 - .12 * smoothstep(.25, .7, length(uv - .5)); outc = grain(outc, vec2(q) / pixelScale, uFrame, .3, 1.5, false); } }
#elif EFFECT_KIND == 13
    outc = mix(c, sampleImage(aux, uv), p[1].x);
#elif EFFECT_KIND == 14
    { uint frame = uint(floor(uTime * p[0].x)); outc = sampleImage(input0, effectPoint(uv, size)); outc *= 1.0 + (random01(uvec3(4u, 7u, frame)) * 2.0 - 1.0) * .08 * p[2].x; }
#elif EFFECT_KIND == 15
    { vec3 glow = sampleImage(aux, uv), halo = sampleImage(background, uv); const vec3 flashColors[3] = vec3[3](vec3(1.0), vec3(1.0, .8, .6), vec3(.65, .8, 1.0));
      outc = c + (glow * .65 + sampleImage(extra, uv) * .35) * p[0].x + halo.r * vec3(1.0, .25, .1) * p[1].x + flashColors[int(p[3].x)] * p[2].x * .08 * (1.0 - y); }
#elif EFFECT_KIND == 16
    { vec2 point = effectPoint(uv, size); outc = mix(sampleImage(input0, point), sampleImage(aux, point), p[2].x); outc = outc / (1.0 + max(outc - .85, 0.0) * .15);
      bool mono = p[5].x > .5; if (mono) outc = vec3(luma(outc)); const float sizes[3] = float[3](1.0, 1.5, 2.2); outc = grain(outc, vec2(q) / pixelScale, uFrame, p[0].x, sizes[int(p[1].x)], mono); if (p[4].x > .5) outc *= gate(uv, .035); }
#elif EFFECT_KIND == 17
    { vec2 point = effectPoint(uv, size), offset = (uv - .5) * pixelScale / size;
      outc = vec3(sampleImage(aux, point + offset).r, sampleImage(aux, point).g, sampleImage(aux, point - offset).b);
      outc = mix(outc, vec3(.06) + outc * .88, p[0].x); outc *= vec3(1.06, 1.0, .92); outc.r += outc.g * .025 * p[0].x; outc = mix(vec3(luma(outc)), outc, .8); outc = grain(outc, vec2(q) / pixelScale, uFrame, p[1].x, 2.2, false);
      float cycle = floor(uTime / 6.0), phase = uTime - cycle * 6.0, begin = random01(uvec3(uint(cycle), 12u, 3u)) * 4.5, len = .5 + random01(uvec3(uint(cycle), 4u, 6u));
      float pulse = phase >= begin && phase < begin + len ? sin((phase - begin) / len * PI) : 0.0; float edge = random01(uvec3(uint(cycle), 7u, 11u)) > .5 ? uv.x : 1.0 - uv.x;
      outc += vec3(1.0, .22, .035) * exp(-edge * 7.0) * pulse * p[4].x; if (p[5].x > .5) outc *= gate(uv, .055); }
#endif
    fragColor = vec4(uIntensity >= 1.0 ? clamp(outc, 0.0, 1.0) : mix(original, clamp(outc, 0.0, 1.0), uIntensity), 1.0);
#endif
}
"""

    /** Metal `ve_blur`: exact Gaussian coefficients normalised once on the CPU; adjacent taps share one bilinear sample. */
    private const val blur = """
uniform sampler2D input0;
uniform vec4 header;
uniform vec4 pairs[24];
void main() {
    vec2 uv = fragUV(), direction = header.zw / outSize;
    vec4 result = tex(input0, uv) * header.x;
    for (int i = 0; i < int(header.y); i++) {
        vec2 offset = direction * pairs[i].x;
        result += (tex(input0, uv + offset) + tex(input0, uv - offset)) * pairs[i].y;
    }
    fragColor = result;
}
"""

    /** Metal `ve_bilateral`. */
    private const val bilateral = """
uniform sampler2D input0;
uniform vec4 values;
void main() {
    vec2 uv = fragUV(); vec3 center = sampleImage(input0, uv), result = vec3(0.0); float total = 0.0, s = max(.5, values.x); int radius = min(10, int(ceil(s * 2.0)));
    for (int i = -radius; i <= radius; i++) { vec3 c = sampleImage(input0, uv + vec2(values.y, values.z) * float(i) / outSize); vec3 e = srgb(c) - srgb(center); float w = exp(-float(i * i) / (2.0 * s * s) - dot(e, e) / .02); result += w * c; total += w; }
    fragColor = vec4(result / max(total, 1e-6), 1.0);
}
"""

    /** Metal `ve_highlights`: threshold individual source samples before the area average. */
    private const val highlights = """
uniform sampler2D input0;
uniform vec4 values;
void main() {
    uvec2 q = pixel(); vec2 inputSize = texSize(input0), ratio = inputSize / outSize;
    vec3 sum = vec3(0.0); float n = 0.0; uint sx = max(1u, uint(ceil(ratio.x))), sy = max(1u, uint(ceil(ratio.y)));
    bool integral = all(equal(ratio, floor(ratio)));
    for (uint y = 0u; y < sy; y++) for (uint x = 0u; x < sx; x++) {
        vec2 uv = (vec2(q) + (vec2(x, y) + .5) / vec2(sx, sy)) / outSize;
        vec3 c = integral ? fetch(input0, q * uvec2(sx, sy) + uvec2(x, y)).rgb : sampleImage(input0, uv);
        sum += c * smoothstep(values.x - values.y, values.x + values.y, luma(c)); n += 1.0;
    }
    fragColor = vec4(sum / max(n, 1.0), 1.0);
}
"""

    /** Metal `ve_reduce`: an area average (exact 2×2 fetches for integral even ratios). */
    private const val reduce = """
uniform sampler2D input0;
void main() {
    uvec2 q = pixel(); vec2 ratio = texSize(input0) / outSize;
    uvec2 count = uvec2(max(vec2(1.0), ceil(ratio))); vec4 total = vec4(0.0);
    if (all(equal(ratio, floor(ratio))) && all(equal(count % 2u, uvec2(0u)))) {
        for (uint y = 0u; y < count.y; y += 2u) for (uint x = 0u; x < count.x; x += 2u) total += tex(input0, (vec2(q) + (vec2(x, y) + 1.0) / vec2(count)) / outSize);
        total *= 4.0;
    } else {
        for (uint y = 0u; y < count.y; y++) for (uint x = 0u; x < count.x; x++) total += tex(input0, (vec2(q) + (vec2(x, y) + .5) / vec2(count)) / outSize);
    }
    fragColor = total / float(count.x * count.y);
}
"""

    /** Metal `ve_grainNoise`, stored as v·0.5+0.5 so an 8-bit texture can hold it too. */
    private const val grainNoise = """
uniform uint uFrame;
void main() {
    uvec2 q = pixel();
    float base = random01(uvec3(q, uFrame + 13u)) * 2.0 - 1.0;
    vec3 other = vec3(random01(uvec3(q, uFrame + 29u)), random01(uvec3(q, uFrame + 59u)), random01(uvec3(q, uFrame + 101u))) * 2.0 - 1.0;
    fragColor = vec4(base * .7 + other * .3, base) * .5 + .5;
}
"""

    /**
     * Input: an SDR texture (sRGB-encoded display values) → the linear working
     * space (Metal: CIColorClamp + the linear CI context). [matrix] maps
     * top-down output pixels to top-down input uv, (u, v, 1) = M·(x, y, 1): the
     * identity scale for the export (its input is already framed), or the
     * preview's picture framing (Swift `preparedImage`: crop or fit, then
     * Mirror), black outside the picture.
     */
    private const val decode = """
uniform sampler2D input0;
uniform mat3 matrix;
void main() {
    vec3 s = matrix * vec3(gl_FragCoord.x, outSize.y - gl_FragCoord.y, 1.0);
    vec2 uv = s.xy;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    fragColor = vec4(lin(clamp(tex(input0, uv).rgb, 0.0, 1.0)), 1.0);
}
"""

    /** Output: the working space back to display values, written bottom-up for the destination framebuffer. */
    private const val encode = """
uniform sampler2D input0;
void main() { vec4 c = tex(input0, fragUV()); fragColor = vec4(srgb(clamp(c.rgb, 0.0, 1.0)), 1.0); }
"""

    /** Copies a texture (cached cadence holds). */
    private const val copy = """
uniform sampler2D input0;
void main() { fragColor = tex(input0, fragUV()); }
"""

    enum class Pass { effect, blur, bilateral, highlights, reduce, grainNoise, decode, encode, copy }

    fun fragment(pass: Pass, kind: Int = -1): String {
        val head = prelude.replaceFirst("#version 300 es\n", "#version 300 es\n#define EFFECT_KIND $kind\n")
        return when (pass) {
            Pass.effect -> head + effectPrelude + effect
            Pass.blur -> head + blur
            Pass.bilateral -> head + bilateral
            Pass.highlights -> head + highlights
            Pass.reduce -> head + reduce
            Pass.grainNoise -> head + grainNoise
            Pass.decode -> head + decode
            Pass.encode -> head + encode
            Pass.copy -> head + copy
        }
    }
}
