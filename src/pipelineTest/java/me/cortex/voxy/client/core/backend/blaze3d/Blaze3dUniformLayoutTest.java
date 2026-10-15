package me.cortex.voxy.client.core.backend.blaze3d;

//? if MC_26_3
import net.minecraft.client.renderer.DynamicGpuData;
//? else
import net.minecraft.client.renderer.DynamicUniforms;
//? endif
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Compare the production shader ABI with bytes emitted by Minecraft's real uniform writer. */
public final class Blaze3dUniformLayoutTest {
    public static void verify() throws Exception {
        var model = new Matrix4f().set(new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        var texture = new Matrix4f().set(new float[]{33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48});
        var color = new Vector4f(71, 72, 73, 74);
        var offset = new Vector3f(91, 92, 93);
        var bytes = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
//? if MC_26_3
        new DynamicGpuData.Transform(model, color, offset, texture).write(bytes);
//? else
        new DynamicUniforms.Transform(model, color, offset, texture).write(bytes);
//? endif
        var expected = Map.of("ModelViewMat", model.get(new float[16]),
                "TextureMat", texture.get(new float[16]),
                "ColorModulator", new float[]{71, 72, 73, 74}, "ModelOffset", new float[]{91, 92, 93});
        var block = Pattern.compile("uniform DynamicTransforms\\s*\\{([^}]+)}", Pattern.DOTALL);
        var member = Pattern.compile("\\b(mat4|vec4|vec3)\\s+(\\w+)\\s*;");
        for (String shader : List.of("blaze3d_lod_terrain.vsh", "blaze3d_lod_terrain.fsh",
                "blaze3d_lod_composite.fsh", "blaze3d_lod_depth_seed.fsh", "blaze3d_hiz_visibility.fsh")) {
            String source;
            try (var input = Blaze3dUniformLayoutTest.class.getResourceAsStream("/assets/voxy/shaders/core/" + shader)) {
                if (input == null) throw new AssertionError("Missing production shader: " + shader);
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            var declaration = block.matcher(source);
            if (!declaration.find()) throw new AssertionError("Missing DynamicTransforms in " + shader);
            var fields = member.matcher(declaration.group(1));
            int position = 0, count = 0;
            while (fields.find()) {
                position = (position + 15) & ~15;
                float[] values = expected.get(fields.group(2));
                if (values == null) throw new AssertionError("Unknown uniform: " + fields.group(2));
                for (int i = 0; i < values.length; i++) {
                    float actual = bytes.getFloat(position + i * Float.BYTES);
                    if (actual != values[i]) throw new AssertionError(shader + ": " + fields.group(2)
                            + " at byte " + (position + i * Float.BYTES) + " reads " + actual + ", expected " + values[i]);
                }
                position += values.length * Float.BYTES;
                count++;
            }
            if (count != 4) throw new AssertionError("Incomplete transform block in " + shader);
        }
        System.out.println("PASS five production shader layouts match Minecraft's DynamicTransforms writer.");
    }
}
