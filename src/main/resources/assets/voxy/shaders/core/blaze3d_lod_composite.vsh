#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
//? endif

//? if MC_26_3
layout(location = 0) in vec3 Position;
//? else
in vec3 Position;
//? endif

//? if MC_26_3
layout(location = 0) out vec2 texCoord;
//? else
out vec2 texCoord;
//? endif

void main() {
    gl_Position = vec4(Position.xy, 1.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
