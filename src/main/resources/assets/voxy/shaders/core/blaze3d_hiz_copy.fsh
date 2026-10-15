#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
//? endif
uniform sampler2D Sampler0;
//? if MC_26_3
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
//? else
in vec2 texCoord;
out vec4 fragColor;
//? endif
void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    float depth = 1.0;
    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
        ivec2 p = base + ivec2(x, y);
        // Padding and uncovered source pixels cannot prove occlusion.
        depth = min(depth, any(greaterThanEqual(p, size)) ? 0.0 : texelFetch(Sampler0, p, 0).r);
    }
    fragColor = vec4(depth);
}
