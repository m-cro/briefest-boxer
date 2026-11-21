package dev.briefestboxer.fabric;

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
 * <p>In singleplayer the integrated server ticks on its own thread, so packet
 * timing does not pin down which server tick a click lands on. There, the cube's
 * state is snapshotted on the server thread after each tick. A click is handled at
 * the start of the next client tick, and the attack lands on the newest server
 * state at that moment, so the snapshot is advanced by the server ticks expected
 * to finish before then.
 *
 * <p>Buoyant cubes also bob with {@code sin(tickCount)}, and the server never
 * syncs {@code tickCount}. In singleplayer the snapshot carries it; in
 * multiplayer it is fitted from consecutive syncs of a floating cube.
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
    /** Test hook: lockstep game tests step the server themselves, so its clock means nothing. */
    static boolean serverRunsOnWallClock = true;
    /** Test hook: replaces the server-reported round-trip latency, in milliseconds. */
    static Integer latencyOverrideMs;

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

    private record ServerState(Vec3 position, Vec3 velocity, boolean onGround, int tickCount) {}
    /**
     * Cube states plus how many started server ticks they already include.
     * Published as one immutable object by the integrated server thread.
     */
    private record IntegratedSnapshot(Map<java.util.UUID, ServerState> states, long ticksApplied) {}
    private static volatile IntegratedSnapshot integrated = new IntegratedSnapshot(Map.of(), 0);
    /** Server ticks started so far. */
    private static volatile long startedTicks;
    private static volatile long tickStartNanos, serverTickNanos = 50_000_000L;
    /** Smoothed server tick duration and its typical deviation, for a lagging server. */
    private static volatile double tickDurationNanos = 2_000_000.0, tickJitterNanos;
    /**
     * The server's own schedule for its next tick start, on the game's
     * {@code Util.getNanos()} clock. 26.2 ships unobfuscated, so the name is
     * stable; if it can't be read, the tick length is assumed.
     */
    private static final java.lang.reflect.Field NEXT_TICK_TIME = nextTickTimeField();
    private static final long CLIENT_TICK_NANOS = 50_000_000L;
    private static final double SNAPSHOT_RANGE = 64.0;

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

    /** Integrated server thread, before each tick. */
    static void markIntegratedTickStart(net.minecraft.server.MinecraftServer server) {
        if (!(server instanceof net.minecraft.client.server.IntegratedServer)) return;
        tickStartNanos = net.minecraft.util.Util.getNanos();
        startedTicks++;
        // Changes applied between ticks (another player's hit, a command) are
        // visible now, before this tick moves anything: snapshot them, counting
        // this tick as still to simulate.
        publish(server, startedTicks - 1);
    }

    /** Integrated server thread, after each tick: snapshot cubes near players. */
    static void captureIntegrated(net.minecraft.server.MinecraftServer server) {
        // A test's dedicated server can share this JVM; only read our own world.
        if (!(server instanceof net.minecraft.client.server.IntegratedServer)) return;
        serverTickNanos = Math.max(1L, server.tickRateManager().nanosecondsPerTick());
        publish(server, startedTicks);
        double duration = net.minecraft.util.Util.getNanos() - tickStartNanos;
        if (duration > 0 && duration < 5.0E9) {
            tickJitterNanos += (Math.abs(duration - tickDurationNanos) - tickJitterNanos) * 0.2;
            tickDurationNanos += (duration - tickDurationNanos) * 0.2;
        }
    }

    private static java.lang.reflect.Field nextTickTimeField() {
        try {
            var field = net.minecraft.server.MinecraftServer.class.getDeclaredField("nextTickTimeNanos");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static void publish(net.minecraft.server.MinecraftServer server, long ticksApplied) {
        var states = new java.util.HashMap<java.util.UUID, ServerState>();
        for (var player : server.getPlayerList().getPlayers()) {
            for (SulfurCube cube : player.level().getEntitiesOfClass(SulfurCube.class,
                    player.getBoundingBox().inflate(SNAPSHOT_RANGE))) {
                states.put(cube.getUUID(), new ServerState(cube.position(), cube.getDeltaMovement(),
                        cube.onGround(), cube.tickCount));
            }
        }
        integrated = new IntegratedSnapshot(states, ticksApplied);
    }


    /**
     * Server ticks the click will wait for: a tick already running when it
     * arrives, plus every tick the server schedules to start before the click is
     * handled at the next client tick. A server running late has its schedule in
     * the past and catches up back to back, which the schedule also captures.
     */
    /**
     * Clicks this close to a server tick start can land on either side of it:
     * when the two clocks line up, scheduling noise the client cannot observe
     * decides. Such clicks also get the neighbouring tick as an alternate.
     */
    static final long AMBIGUOUS_NANOS = 10_000_000L;

    /**
     * Ticks before the click lands, plausible neighbours (one or both sides when
     * the click is near a tick start), and the margin to the nearest tick start.
     */
    record ClickTiming(int ahead, java.util.List<Integer> alternates, long marginNanos) {
        int alternate() { return alternates.isEmpty() ? -1 : alternates.getFirst(); }
    }

    private ClickTiming integratedClickTiming(long ticksApplied) {
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        // A frozen tick rate (/tick freeze) runs no ticks on its own.
        if (!serverRunsOnWallClock || server == null || server.tickRateManager().isFrozen()) {
            return new ClickTiming(0, java.util.List.of(), Long.MAX_VALUE);
        }
        // Ticks started but not yet in the snapshot: one running, or just finished.
        long running = Math.max(0, startedTicks - ticksApplied);
        long nextStart = tickStartNanos + serverTickNanos;
        if (NEXT_TICK_TIME != null) {
            try {
                nextStart = NEXT_TICK_TIME.getLong(server);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Keep the assumed schedule.
            }
        }
        long now = net.minecraft.util.Util.getNanos();
        // The frame's partial tick says how far off the next client tick is,
        // including when the client is behind and runs ticks back to back.
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        long click = now + (long) ((1.0F - Math.min(1.0F, Math.max(0.0F, partial))) * CLIENT_TICK_NANOS);
        // Walk the server's upcoming tick starts. A tick starts at its scheduled
        // time, or as soon as the previous one ends when the server is running
        // late; a lagging server's ticks take their measured duration.
        long duration = (long) tickDurationNanos;
        long schedule = nextStart;
        long start = running > 0 ? Math.max(Math.max(tickStartNanos + duration, now), schedule)
                : Math.max(schedule, now);
        long previousStart = Long.MIN_VALUE;
        int ahead = (int) running;
        while (start <= click && ahead < MAX_CATCH_UP_TICKS) {
            ahead++;
            previousStart = start;
            schedule += serverTickNanos;
            start = Math.max(start + duration, schedule);
        }
        long untilNext = start - click;
        long sincePrevious = previousStart == Long.MIN_VALUE ? Long.MAX_VALUE : click - previousStart;
        // Irregular tick durations make each boundary less certain.
        long window = Math.max(AMBIGUOUS_NANOS, (long) (2.0 * tickJitterNanos));
        // Nearer boundary first; under heavy lag the window can reach both.
        var alternates = new java.util.ArrayList<Integer>(2);
        boolean later = untilNext < window && ahead < MAX_CATCH_UP_TICKS;
        boolean earlier = sincePrevious < window && ahead > 0;
        if (later && (!earlier || untilNext <= sincePrevious)) {
            alternates.add(ahead + 1);
            if (earlier) alternates.add(ahead - 1);
        } else if (earlier) {
            alternates.add(ahead - 1);
            if (later) alternates.add(ahead + 1);
        }
        return new ClickTiming(ahead, java.util.List.copyOf(alternates), Math.min(untilNext, sincePrevious));
    }

    private java.util.List<Start> lastAlternates = java.util.List.of();
    private ClickTiming lastTiming;

    /**
     * Starts for neighbouring server ticks the last {@link #start} click could
     * also land on (nearest first), or empty when the timing is clear.
     */
    java.util.List<Start> alternates() {
        return lastAlternates;
    }

    /** Test hook: the timing behind the last {@link #start}, or null outside singleplayer. */
    ClickTiming lastTiming() {
        return lastTiming;
    }

    Start start(SulfurCube cube) {
        lastAlternates = java.util.List.of();
        lastTiming = null;
        if (integrated()) {
            IntegratedSnapshot snapshot = integrated;
            ServerState server = snapshot.states().get(cube.getUUID());
            if (server != null) {
                AABB bounds = cube.getBoundingBox().move(server.position().subtract(cube.position()));
                ClickTiming timing = integratedClickTiming(snapshot.ticksApplied());
                lastTiming = timing;
                var alternates = new java.util.ArrayList<Start>(2);
                for (int ticks : timing.alternates()) {
                    alternates.add(new Start(bounds, server.velocity(), server.onGround(), ticks,
                            server.tickCount() + ticks));
                }
                lastAlternates = java.util.List.copyOf(alternates);
                return new Start(bounds, server.velocity(), server.onGround(), timing.ahead(),
                        server.tickCount() + timing.ahead());
            }
        }
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
        Long fitted = phase.get(cube);
        // Without a fit the phase is unknown; the client count is as good as any.
        return fitted != null ? (int) (hitTick + fitted) : cube.tickCount;
    }

    private static boolean integrated() {
        return useIntegratedServer && Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /**
     * The sync and the attack each cross the network once, so a remote server
     * resolves the hit about one round trip of ticks later than a local one.
     */
    private static int pingTicks() {
        var client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null || client.getConnection() == null || client.player == null) return 0;
        if (latencyOverrideMs != null) return Math.max(0, Math.round(latencyOverrideMs / 50.0F));
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

    /**
     * The part of the start state that changes where a hit lands. The tick count
     * and click-timing catch-up advance 20 times a second; for a cube resting on
     * the ground neither changes the outcome, so they must not restart the solve.
     * A moving or floating cube really is changing, so its full state is used.
     */
    static Object outcomeKey(SulfurSyncedState.Start start) {
        Vec3 velocity = start.velocity();
        boolean settled = start.onGround() && Math.abs(velocity.x) < 1.0E-3 && Math.abs(velocity.z) < 1.0E-3
                && Math.abs(velocity.y) < 0.1;
        return settled ? java.util.List.of(roundBounds(start.bounds()), true) : start;
    }

    private static AABB roundBounds(AABB box) {
        return new AABB(Math.round(box.minX * 1000) / 1000.0, Math.round(box.minY * 1000) / 1000.0,
                Math.round(box.minZ * 1000) / 1000.0, Math.round(box.maxX * 1000) / 1000.0,
                Math.round(box.maxY * 1000) / 1000.0, Math.round(box.maxZ * 1000) / 1000.0);
    }

    /** Whether two forecasts describe the same motion (e.g. a resting cube). */
    static boolean samePath(dev.briefestboxer.core.Trajectory a, dev.briefestboxer.core.Trajectory b) {
        var first = a.getPositions();
        var second = b.getPositions();
        int count = Math.min(first.size(), second.size());
        for (int i = 0; i < count; i++) {
            if (first.get(i).distanceSquared(second.get(i)) > 1.0E-8) return false;
        }
        return true;
    }

    void clear() {
        integrated = new IntegratedSnapshot(Map.of(), startedTicks);
        afterLastTick.clear();
        synced.clear();
        phase.clear();
        tick = 0;
    }
}
