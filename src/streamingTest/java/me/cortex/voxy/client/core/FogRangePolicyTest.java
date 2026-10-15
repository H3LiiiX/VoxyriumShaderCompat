package me.cortex.voxy.client.core;

public final class FogRangePolicyTest {
    public static void main(String[] args) {
        var vanilla = new FogRangePolicy.Range(0, 1024, 230.4F, 256, 1024, 2048);
        check(FogRangePolicy.forVoxy(vanilla, false, false).equals(vanilla), "disabled or failed renderer restores all vanilla fog");
        check(FogRangePolicy.forVoxy(vanilla, false, true).equals(vanilla), "inactive Voxy cannot remove environmental fog");
        var active = FogRangePolicy.forVoxy(vanilla, true, false);
        check(active.environmentalStart() == 0 && active.environmentalEnd() == 1024, "terrain keeps environmental fog");
        check(active.skyEnd() == 1024 && active.cloudEnd() == 2048, "sky and cloud fog keep the horizon blend");
        check(active.renderStart() > 32768 && active.renderEnd() > 32768, "Voxy owns the terrain distance ramp");
        var off = FogRangePolicy.forVoxy(vanilla, true, true);
        check(off.environmentalStart() > 32768 && off.environmentalEnd() > 32768, "optional environmental fog can be disabled");
        check(off.skyEnd() == 1024 && off.cloudEnd() == 2048, "fog-off leaves atmospheric sky blending intact");
        for (float end : new float[]{1, 3, 5, 9.9F}) {
            var submerged = new FogRangePolicy.Range(-8, end, 230.4F, 256, 1024, 2048);
            var result = FogRangePolicy.forVoxy(submerged, true, true);
            check(result.environmentalStart() == -8 && result.environmentalEnd() == end, "dense environmental fog survives fog-off");
        }
        System.out.println("PASS fog ownership, fallback, horizon and dense environmental fog (11 checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
