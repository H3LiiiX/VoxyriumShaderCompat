#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
#ifdef VOXY_VULKAN
layout(binding = 1, std140) uniform CompositeParams {
    mat4 invProjMat;
    mat4 projMat;
    vec4 endParams;
    vec4 fogColour;
    vec4 fadeParams;
};
#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#endif
#else
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef HAS_FOG
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
#endif
#ifdef HAS_FADE
layout(location = 6) uniform vec4 fadeParams;
#endif
#endif
#endif

#import <voxy:util/depthutils.glsl>

#ifdef VOXY_VULKAN
layout(location = 0) out vec4 colour;
#else
out vec4 colour;
#endif
in vec2 UV;

//Shaderpack LOD programs: the pack's other opaque draw buffers (e.g. colortex6), written where the
// LOD colour is
#ifndef PACK_DATA_COUNT
#define PACK_DATA_COUNT 0
#endif
#if PACK_DATA_COUNT > 0
layout(binding = 4) uniform sampler2D packData0;
layout(location = 1) out vec4 packOut0;
#endif
#if PACK_DATA_COUNT > 1
layout(binding = 5) uniform sampler2D packData1;
layout(location = 2) out vec4 packOut1;
#endif
#if PACK_DATA_COUNT > 2
layout(binding = 6) uniform sampler2D packData2;
layout(location = 3) out vec4 packOut2;
#endif

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(SCREEN2NDC(clip),1.0f);
    return view.xyz/view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = projDepth(point);
    //TODO: HERE make an option/define to emit the output depth as something other then the input (i.e. if voxy is reverse z and vanilla isnt, transform and emit as not reverrse z)
    depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);
    depth = NDC2SCREEN_DEPTH(depth);

#ifndef VOXY_VULKAN
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;//TODO: dont think this is right at all so should fix this
#endif

    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #ifdef PACK_LOD
    //A translucent LOD with no opaque LOD behind it was blended over the cleared target, so its
    // colour is premultiplied; the blend below multiplies by alpha again
    if (colour.a < 1.0) {
        colour.rgb /= colour.a;
    }
    #endif
    #if PACK_DATA_COUNT > 0
    packOut0 = texture(packData0, UV.xy);
    #endif
    #if PACK_DATA_COUNT > 1
    packOut1 = texture(packData1, UV.xy);
    #endif
    #if PACK_DATA_COUNT > 2
    packOut2 = texture(packData2, UV.xy);
    #endif

    #ifdef HAS_FOG
    if (fogColour.a>0.0){
        #ifdef VOXY_SHADER_FOG
        float lPos = length(point.xyz);
        float fogRel = lPos * endParams.x;
        float fog4 = (fogRel * fogRel) * (fogRel * fogRel);
        float fogLerp = clamp(1.0 - exp(-endParams.y * fog4), 0.0, 1.0);
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp * fogColour.a);
        #else
        float fogLerp = clamp(fma(length(point.xyz),endParams.x,endParams.y),0,endParams.z);//512 is 32*16 which is the render distance in blocks
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp*fogColour.a);
        #endif
    }
    #endif
    #ifdef HAS_FADE
    //yes am aware how inefficent this is, this could all be packed into endParams.w
    if (fadeParams.x>0) {
        float len = fadeParams.x>1.5?length(point.xyz):length(point.xz);
        colour.a *= 1-clamp(fma(len, fadeParams.z, fadeParams.y), 0,1);
        if (colour.a <= 0.0) {
            discard;
        }
    }
    #endif
    #else
    colour = vec4(0);
    #endif

}