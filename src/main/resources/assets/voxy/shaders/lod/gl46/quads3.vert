#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif


#ifdef USE_NV_JANK
#extension GL_NV_gpu_shader5 : enable
#endif

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#ifdef VOXY_VULKAN
#define LIGHTING_SAMPLER_BINDING 9
#define VERT_ID gl_VertexIndex
#define BASE_INSTANCE gl_InstanceIndex
#else
#define LIGHTING_SAMPLER_BINDING 1
#define VERT_ID gl_VertexID
#define BASE_INSTANCE gl_BaseInstance
#endif

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

#ifdef USE_NV_JANK
#ifdef GL_NV_gpu_shader5
out gl_PerVertex {
    f16vec4 gl_Position;
};
#endif
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

#ifdef VOXY_SMOOTH_LIGHT
//Shaderpack LOD programs: where on its face plane the fragment is, so the light can be blended
// between neighbouring blocks (see voxySmoothLight in quads.frag)
layout(location = 2) out vec3 voxyPos;
layout(location = 3) out flat vec2 voxyPlane;//lod scale, the face plane's offset along its axis
#endif

vec2 taaShift();

//The translucent quads of a section are one draw with no face culling, emitted in the order the mesher
// finishes merging them. A translucent block's rear faces then showed through its front ones wherever
// the rear quad came first (ice over water: the ice's underside under its top, a second layer of ice,
// in rectangles the shape of the merged quads), which the game itself culls. Fluids keep theirs (the
// game draws a water surface from below too), and so do the models that need both sides (crossed ones)
bool isRearFacingTranslucent(const in QuadData quad, uint modelId) {
    BlockModel model = modelData[modelId];
    if (!modelIsTranslucent(model) || modelIsFluid(model) || (model.flagsA & 32u) != 0u) {
        return false;
    }
    float plane = quad.axis == 0u ? quad.basePoint.y : (quad.axis == 1u ? quad.basePoint.z : quad.basePoint.x);
    float camera = quad.axis == 0u ? cameraSubPos.y : (quad.axis == 1u ? cameraSubPos.z : cameraSubPos.x);
    return (quad.face & 1u) == 1u ? camera < plane : camera > plane;
}

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    QuadData quad;
    uvec2 pos = positionBuffer[BASE_INSTANCE];
    Quad rawQuad = quadData[uint(VERT_ID)>>2];
    setupQuad(quad, rawQuad, pos, (VERT_ID&3) == 1);

    uint cornerId = VERT_ID&3;

    vec4 cornerPos = getQuadCornerPos(quad, cornerId);
    if (isRearFacingTranslucent(quad, extractStateId(rawQuad))) {
        cornerPos = vec4(2.0, 2.0, 2.0, 1.0);//every corner on one point outside the view: nothing drawn
    }

    gl_Position =
    #ifdef USE_NV_JANK
    #ifdef GL_NV_gpu_shader5
    f16vec4
    #endif
    #endif
    (cornerPos);


    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    #ifdef VOXY_SMOOTH_LIGHT
    voxyPos = quadCornerPoint;
    voxyPlane = vec2(quad.lodScale, quad.axis == 0u ? quadCornerPoint.y : (quad.axis == 1u ? quadCornerPoint.z : quadCornerPoint.x));
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;


    #ifdef DEBUG_RENDER
    //quadDebug = uint(extractDetail(pos));
    quadDebug = uint(VERT_ID)>>2;
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif