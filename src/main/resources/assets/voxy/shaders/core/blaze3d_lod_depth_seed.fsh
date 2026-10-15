#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
#include <voxy:blaze3d_fragment_discard.glsl>
//? else
#moj_import <voxy:blaze3d_fragment_discard.glsl>
//? endif
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
//? if MC_26_3
    mat4 TextureMat;
//? endif
    vec4 ColorModulator;
    vec3 ModelOffset;
//? if MC_26_2
    mat4 TextureMat;
//? endif
};
uniform sampler2D Sampler0;
//? if MC_26_3
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
//? else
in vec2 texCoord;
out vec4 fragColor;
//? endif
void main() {
    VOXY_INIT_FRAGMENT();
    float depth = texture(Sampler0, texCoord).r;
    if (depth <= 0.0) VOXY_DISCARD_FRAGMENT();
    float ndcDepth = ModelOffset.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 view = ModelViewMat * vec4(texCoord * 2.0 - 1.0, ndcDepth, 1.0);
    vec4 clip = TextureMat * view;
    float lodDepth = clip.z / clip.w;
    if (ModelOffset.x <= 0.5) lodDepth = lodDepth * 0.5 + 0.5;
    // Sodium surfaces closer than Voxy's near plane still occlude distant geometry.
    gl_FragDepth = clamp(lodDepth, 0.0, 1.0);
    fragColor = vec4(0.0);
}
