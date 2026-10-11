package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** Sampled public-API timestamps. Unavailable results never make the render thread wait. */
final class Blaze3dGpuProfiler implements AutoCloseable {
    enum Stage { SODIUM_DEPTH, OCCLUDERS, HIZ, OPAQUE, OPAQUE_COMPOSITE, WATER, WATER_COMPOSITE, FOG }
    private static final int SLOTS = 8, QUERIES = Stage.values().length * 2;
    private final long[] frames = new long[SLOTS];
    private final int[] written = new int[SLOTS];
    private GpuQueryPool pool;
    private float period;
    private int current = -1;
    private boolean unavailable;
    private Consumer<String> sink = detail -> {};

    void beginFrame(long frame, boolean enabled, Consumer<String> sink) {
        this.current = -1;
        this.sink = sink;
        if (this.unavailable) return;
        try {
            if (this.pool != null) poll(sink);
            if (!enabled || (frame - 1) % 30 != 0) return;
            if (this.pool == null) {
                this.period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
                if (!(this.period > 0) || !Float.isFinite(this.period)) {
                    this.unavailable = true;
                    sink.accept("available=false reason=invalidTimestampPeriod");
                    return;
                }
                this.pool = RenderSystem.getDevice().createTimestampQueryPool(SLOTS * QUERIES);
                sink.accept("available=true sampleEveryFrames=30 timestampPeriodNs=" + this.period);
            }
            for (int slot = 0; slot < SLOTS; slot++) {
                if (this.written[slot] == 0) {
                    this.frames[slot] = frame;
                    this.current = slot;
                    return;
                }
            }
            sink.accept("frame=" + frame + " skipped=true reason=queriesPending");
        } catch (RuntimeException exception) {
            this.unavailable = true;
            sink.accept("available=false reason=" + exception);
        }
    }

    void begin(CommandEncoder encoder, Stage stage) { mark(encoder, stage.ordinal() * 2); }
    void end(CommandEncoder encoder, Stage stage) { mark(encoder, stage.ordinal() * 2 + 1); }
    private void mark(CommandEncoder encoder, int query) {
        if (this.current < 0 || this.unavailable) return;
        int bit = 1 << query;
        if ((this.written[this.current] & bit) != 0) return;
        try {
            encoder.writeTimestamp(this.pool, this.current * QUERIES + query);
            this.written[this.current] |= bit;
        } catch (RuntimeException exception) {
            this.unavailable = true;
            this.sink.accept("available=false reason=" + exception);
        }
    }

    private void poll(Consumer<String> sink) {
        for (int slot = 0; slot < SLOTS; slot++) {
            int mask = this.written[slot];
            if (mask == 0) continue;
            long[] ticks = new long[QUERIES];
            boolean ready = true;
            for (int query = 0; query < QUERIES; query++) {
                if ((mask & (1 << query)) == 0) continue; // Never read an unwritten query.
                OptionalLong result = this.pool.getValue(slot * QUERIES + query);
                if (result.isEmpty()) { ready = false; break; }
                ticks[query] = result.getAsLong();
            }
            if (!ready) continue;
            StringBuilder record = new StringBuilder("frame=").append(this.frames[slot]);
            for (Stage stage : Stage.values()) {
                int first = stage.ordinal() * 2;
                if ((mask & (3 << first)) != (3 << first)) continue;
                long delta = ticks[first + 1] - ticks[first];
                if (delta >= 0) record.append(' ').append(stage).append("Ns=").append(Math.round(delta * (double) this.period));
            }
            sink.accept(record.toString());
            this.written[slot] = 0;
        }
    }

    @Override public void close() {
        if (this.pool != null) this.pool.close();
        this.pool = null; this.current = -1; this.unavailable = false;
        this.sink = detail -> {};
        Arrays.fill(this.written, 0);
    }
}
