package dev.briefestboxer.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Reconstructs where the server's Sulfur Cube is when an attack would land.
 *
 * <p>A remote cube on the client is drawn at an interpolated position a few ticks
 * behind the server, and its velocity is only refreshed by sync packets. Between
 * packets the client also decays that velocity by 0.98 per tick. Starting a hit
 * forecast from those values places the launch in the wrong spot with the wrong
 * pre-hit motion whenever the cube is already moving. Instead, record the raw
 * synced state as packets arrive (before the client tick mutates it) and let the
 * forecast simulate the elapsed ticks with native physics before applying the hit.
 *
 * <p>Buoyant cubes also bob with {@code sin(tickCount)}, and the server never
 * syncs {@code tickCount}. In singleplayer it is read from the integrated server;
 * in multiplayer it is fitted from consecutive syncs of a floating cube.
 */
final class SulfurSyncedState {
    /**
     * A sync describes the server one tick before the attack that the next client
     * tick would send can land; each client tick started since adds one more.
     */
    private static final int PACKET_DELAY_TICKS = 1;
    /** Only resting cubes go this long without a sync; they need no further catch-up. */
    static final int MAX_CATCH_UP_TICKS = 40;
    /**
     * Bobbing phases searched when fitting a multiplayer cube's tickCount. 157
     * ticks span ten periods of sin(0.4 t), so some candidate lands within about
     * 0.02 rad of any true phase.
     */
    private static final int PHASE_CANDIDATES = 157;
    /** Accept a fitted phase only if it reproduces the next sync this closely. */
    private static final double PHASE_FIT_TOLERANCE = 2.0E-3;
    private static final int MAX_PHASE_FIT_TICKS = 12;
    /** Test hook: ignore the integrated server, as on a multiplayer connection. */
    static boolean useIntegratedServer = true;

    /**
     * Server state at the last sync, the ticks needed to bring it to attack time,
     * and the server's estimated {@code tickCount} when the attack lands.
     */
    record Start(AABB bounds, Vec3 velocity, boolean onGround, int catchUpTicks, int tickCount) {
        static Start current(SulfurCube cube, AABB bounds) {
            return new Start(bounds, cube.getDeltaMovement(), cube.onGround(), 0, cube.tickCount);
        }
    }

    record Snapshot(Vec3 position, Vec3 velocity, boolean onGround) {
        static Snapshot of(SulfurCube cube) {
            return new Snapshot(cube.getInterpolation().position(), cube.getDeltaMovement(), cube.onGround());
        }
    }

    /** {@code ticksBefore} counts the client ticks that had started when the sync arrived. */
    private record Synced(Snapshot state, long ticksBefore) {}

    private final Map<SulfurCube, Snapshot> afterLastTick = new WeakHashMap<>();
    private final Map<SulfurCube, Synced> synced = new WeakHashMap<>();
    /** A synced state seen at {@code ticksBefore} has server tickCount {@code ticksBefore + phase}. */
    private final Map<SulfurCube, Long> phase = new WeakHashMap<>();
    private long tick;

    /** Runs at the start of a client tick, after pending packets are handled. */
    void beforeTick(ClientLevel level) {
        tick++;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof SulfurCube cube)) continue;
            Snapshot now = Snapshot.of(cube);
            // Any difference from the end of the previous tick can only come from
            // a packet, because nothing else runs between client ticks.
            if (!now.equals(afterLastTick.get(cube)) || !synced.containsKey(cube)) {
                Synced previous = synced.get(cube);
                Synced current = new Synced(now, tick - 1);
                synced.put(cube, current);
                if (previous != null && !integrated()) learnPhase(cube, previous, current);
            }
        }
    }

    /** Runs after entities tick, so client-only decay is never mistaken for a sync. */
    void afterTick(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof SulfurCube cube) afterLastTick.put(cube, Snapshot.of(cube));
        }
    }

    Start start(SulfurCube cube) {
        Snapshot live = Snapshot.of(cube);
        Synced last = synced.get(cube);
        // Frames can render after packets are handled but before the next tick
        // starts; such a sync is newer than anything recorded at tick start.
        if (!live.equals(afterLastTick.get(cube))) last = new Synced(live, tick);
        if (last == null) return Start.current(cube, cube.getBoundingBox());
        AABB bounds = cube.getBoundingBox().move(last.state().position().subtract(cube.position()));
        int elapsed = (int) Math.min(MAX_CATCH_UP_TICKS,
                tick - last.ticksBefore() + PACKET_DELAY_TICKS + pingTicks());
        return new Start(bounds, last.state().velocity(), last.state().onGround(), elapsed,
                hitTickCount(cube, last.ticksBefore() + elapsed));
    }

    /** Estimated server tickCount when the attack lands, {@code hitTick} in sync-tick terms. */
    private int hitTickCount(SulfurCube cube, long hitTick) {
        Integer integrated = integratedTickCount(cube);
        if (integrated != null) return integrated;
        Long fitted = phase.get(cube);
        // Without a fit the phase is unknown; the client count is as good as any.
        return fitted != null ? (int) (hitTick + fitted) : cube.tickCount;
    }

    private static boolean integrated() {
        return useIntegratedServer && Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /**
     * Singleplayer: read the integrated server's cube. Entity IDs are shared and an
     * int read is atomic; a lookup that races the server thread just falls back.
     */
    private static Integer integratedTickCount(SulfurCube cube) {
        if (!integrated()) return null;
        try {
            var serverLevel = Minecraft.getInstance().getSingleplayerServer().getLevel(cube.level().dimension());
            Entity serverCube = serverLevel == null ? null : serverLevel.getEntity(cube.getId());
            return serverCube instanceof SulfurCube && serverCube.getUUID().equals(cube.getUUID())
                    ? serverCube.tickCount : null;
        } catch (RuntimeException concurrentLookup) {
            return null;
        }
    }

    /**
     * The sync and the attack each cross the network once, so a remote server
     * resolves the hit about one round trip of ticks later than a local one.
     */
    private static int pingTicks() {
        var client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null || client.getConnection() == null || client.player == null) return 0;
        var info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? 0 : Math.max(0, Math.round(info.getLatency() / 50.0F));
    }

    /**
     * Fits the bobbing phase by replaying the interval between two syncs of a
     * floating buoyant cube with each candidate phase. A known phase is kept while
     * it still reproduces each new sync, so a settled cube costs one replay per sync.
     */
    private void learnPhase(SulfurCube cube, Synced from, Synced to) {
        long ticks = to.ticksBefore() - from.ticksBefore();
        if (ticks < 1 || ticks > MAX_PHASE_FIT_TICKS || !cube.hasBodyItem()
                || !(cube.isInWater() || cube.isInLava())) return;
        Long known = phase.get(cube);
        if (known != null && replayError(cube, from, to, (int) ticks, known) <= PHASE_FIT_TOLERANCE) return;
        Long best = null;
        double bestError = PHASE_FIT_TOLERANCE;
        for (long candidate = 0; candidate < PHASE_CANDIDATES; candidate++) {
            double error = replayError(cube, from, to, (int) ticks, candidate);
            if (error <= bestError) {
                bestError = error;
                best = candidate;
            }
        }
        if (best != null) phase.put(cube, best);
    }

    private static double replayError(SulfurCube cube, Synced from, Synced to, int ticks, long phase) {
        AABB bounds = cube.getBoundingBox().move(from.state().position().subtract(cube.position()));
        var start = new Start(bounds, from.state().velocity(), from.state().onGround(), 0,
                (int) (from.ticksBefore() + phase));
        Snapshot replayed = SulfurMovementForecast.advance(cube, cube.getItemBySlot(EquipmentSlot.BODY), start, ticks);
        return replayed.position().distanceTo(to.state().position())
                + replayed.velocity().distanceTo(to.state().velocity());
    }

    /** Test hook: the fitted phase, or null if none has been fitted. */
    Long fittedPhase(SulfurCube cube) {
        return phase.get(cube);
    }

    void clear() {
        afterLastTick.clear();
        synced.clear();
        phase.clear();
        tick = 0;
    }
}
