#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
#include <voxy:blaze3d_fragment_discard.glsl>
//? else
#moj_import <voxy:blaze3d_fragment_discard.glsl>
//? endif

layout(std140) uniform DynamicTransforms {
    // The Java side stores the inverse Voxy projection here for reconstruction.
    mat4 ModelViewMat;
//? if MC_26_3
    // Minecraft/Sodium projection used to produce the destination depth.
    mat4 TextureMat;
//? endif
    // xyz: world up expressed in view space; w: opacity multiplier.
    vec4 ColorModulator;
    // x is 1 when NDC depth already uses Vulkan's zero-to-one range; yz contain
    // the start/end distances of the Sodium-to-Voxy transition in blocks.
    vec3 ModelOffset;
//? if MC_26_2
    // Minecraft/Sodium projection used to produce the destination depth.
    mat4 TextureMat;
//? endif
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// Snapshot of Sodium's solid/cutout depth, taken before Voxy composites into the target.
uniform sampler2D Sampler2;

//? if MC_26_3
layout(location = 0) in vec2 texCoord;
//? else
in vec2 texCoord;
//? endif

//? if MC_26_3
layout(location = 0) out vec4 fragColor;
//? else
out vec4 fragColor;
//? endif

void main() {
    VOXY_INIT_FRAGMENT();
    // The depth target is prefilled from Sodium, but only Voxy colour belongs in this composite.
    vec4 lodColor = texture(Sampler0, texCoord);
    if (lodColor.a <= 0.0) VOXY_DISCARD_FRAGMENT();
    float lodDepth = texture(Sampler1, texCoord).r;
    if (lodDepth <= 0.0) {
        VOXY_DISCARD_FRAGMENT();
    }

    float lodNdcDepth = ModelOffset.x > 0.5 ? lodDepth : lodDepth * 2.0 - 1.0;
    vec4 viewPosition = ModelViewMat * vec4(texCoord * 2.0 - 1.0, lodNdcDepth, 1.0);
    viewPosition /= viewPosition.w;

    vec4 minecraftClip = TextureMat * viewPosition;
    float minecraftDepth = minecraftClip.z / minecraftClip.w;
    if (ModelOffset.x <= 0.5) {
        minecraftDepth = minecraftDepth * 0.5 + 0.5;
    }

    float sodiumDepth = texture(Sampler2, texCoord).r;
    // Positive reverse-Z depth proves that Sodium drew this pixel. Require its surface to
    // be in front of or nearly coincident with Voxy; distant background terrain is not coverage.
    bool sodiumCoversSurface = sodiumDepth > 0.0 && minecraftDepth > 0.0
            && sodiumDepth >= minecraftDepth * 0.99;

    // View-space xz rotates when the player pitches the camera. Remove the world vertical
    // component instead so the transition stays horizontal when looking down.
    float verticalDistance = dot(viewPosition.xyz, normalize(ColorModulator.xyz));
    float fragmentDistance = sqrt(max(0.0, dot(viewPosition.xyz, viewPosition.xyz)
            - verticalDistance * verticalDistance));
    if (sodiumCoversSurface && fragmentDistance < ModelOffset.y) {
        VOXY_DISCARD_FRAGMENT();
    }
    if (sodiumCoversSurface && ModelOffset.z > ModelOffset.y && fragmentDistance < ModelOffset.z) {
        // Stable 4x4 Bayer ordered dithering. Voxy gains coverage gradually in the final
        // Sodium chunk ring without alpha-blending two nearly coincident terrain surfaces.
        int x = int(gl_FragCoord.x) & 3;
        int y = int(gl_FragCoord.y) & 3;
        int bayer = ((x & 1) << 1) | (y & 1);
        bayer = (bayer << 2) | (((x >> 1) & 1) << 1) | ((y >> 1) & 1);
        float threshold = (float(bayer) + 0.5) / 16.0;
        float coverage = smoothstep(ModelOffset.y, ModelOffset.z, fragmentDistance);
        if (coverage <= threshold) {
            VOXY_DISCARD_FRAGMENT();
        }
    }

    // Minecraft 26.2 uses reverse-Z. Geometry beyond its own far plane maps below zero.
    // Reserve a very small depth band above the clear value for that terrain. Scaling the
    // original LoD depth within the band preserves ordering between LoD ground, water and glass,
    // while every real positive Sodium depth remains in front of it.
    float distantDepth = lodDepth / 1048576.0 + 2.0 / 16777215.0;
    // Pull Voxy a tiny amount away from the camera. Exact and near-exact Sodium surfaces then
    // win deterministically instead of alternating because of reconstruction/rounding noise.
    float sodiumBiasedDepth = max(0.0, minecraftDepth - 4.0 / 16777215.0);
    gl_FragDepth = minecraftDepth > 0.0 ? max(sodiumBiasedDepth, distantDepth) : distantDepth;
    fragColor = lodColor * ColorModulator.w;
}
