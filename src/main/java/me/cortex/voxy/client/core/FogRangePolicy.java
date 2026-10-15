package me.cortex.voxy.client.core;

/** Fog shared by Minecraft, Sodium and Voxy, independent of the graphics API. */
public final class FogRangePolicy {
    public static final float DISABLED_DISTANCE = 999999999.0F;

    public record Range(float environmentalStart, float environmentalEnd,
                        float renderStart, float renderEnd, float skyEnd, float cloudEnd) {}

    public static Range forVoxy(Range vanilla, boolean rendererActive, boolean removeEnvironmentalFog) {
        if (!rendererActive) return vanilla;
        // Keep underwater/lava/powder-snow fog even when optional distance fog is off.
        boolean disableEnvironment = removeEnvironmentalFog && vanilla.environmentalEnd >= 10.0F;
        return new Range(disableEnvironment ? DISABLED_DISTANCE : vanilla.environmentalStart,
                disableEnvironment ? DISABLED_DISTANCE : vanilla.environmentalEnd,
                DISABLED_DISTANCE, DISABLED_DISTANCE, vanilla.skyEnd, vanilla.cloudEnd);
    }

    private FogRangePolicy() {}
}
