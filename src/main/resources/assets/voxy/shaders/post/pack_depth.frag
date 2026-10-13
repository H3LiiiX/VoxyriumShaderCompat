#version 450 core

//Shaderpack LOD programs: the opaque depth of the whole scene in the window depth the pack reads it as,
// vxDepthTexOpaque. The pack converts it back to a distance with vxProjInv, the OpenGL form of Voxy's
// projection, so a = -1, b = 1 for reversed Z.
//
//Where the vanilla terrain covers a pixel Voxy's own depth holds only the setup pass's marker (NEAR),
// so Minecraft's depth is taken there instead and carried into Voxy's volume: the LOD water's
// reflections march through this image, and without the real terrain in it they found nothing of the
// real chunks to reflect where the real water beside them did.
layout(binding = 0) uniform sampler2D depthTex;
layout(binding = 1) uniform sampler2D vanillaDepthTex;
layout(binding = 2, std140) uniform PackDepthParams {
    mat4 vanillaInvProj;//inverse of the projection Minecraft drew its terrain with
    mat4 packProj;//Voxy's projection in the OpenGL form handed to the pack (vxProj)
};
layout(push_constant) uniform PushConstants { vec2 conversion; };

#import <voxy:util/depthutils.glsl>

layout(location = 0) out vec4 outDepth;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(depthTex, texel, 0).r;
    float converted;
    if (depth == NEAR) {
        float vanilla = texelFetch(vanillaDepthTex, texel, 0).r;
        vec2 uv = gl_FragCoord.xy / vec2(textureSize(depthTex, 0));
        vec4 view = vanillaInvProj * vec4(SCREEN2NDC(vec3(uv, vanilla)), 1.0);
        vec4 clip = packProj * vec4(view.xyz / view.w, 1.0);
        converted = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
    } else if (depth == FAR) {
        //Nothing drawn is the far plane in the pack's window, which is what Iris's vxDepthTexOpaque holds there
        converted = 1.0;
    } else {
        converted = clamp(fma(conversion.x, depth, conversion.y), 0.0, 1.0);
    }
    outDepth = vec4(converted, 0.0, 0.0, 1.0);
}

#import <voxy:util/depthutils.glsl>
