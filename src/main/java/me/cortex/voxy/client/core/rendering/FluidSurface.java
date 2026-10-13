package me.cortex.voxy.client.core.rendering;

import net.minecraft.client.Minecraft;

//The plane LOD fluid tops are pinned to (see setupQuad in quad_util.glsl): the level's sea level, the
// top of the highest sea block. Written into the terrain SceneUniform as fluidSurfaceY.
public final class FluidSurface {
    private FluidSurface() {}

    /** Sea level relative to the viewport's base section (in blocks), or NaN when there is no level. */
    public static float relativeSurfaceY(int baseSectionY) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return Float.NaN;
        }
        return level.getSeaLevel() - (float) (baseSectionY << 5);
    }
}
