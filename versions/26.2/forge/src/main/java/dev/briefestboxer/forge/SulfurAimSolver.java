package dev.briefestboxer.forge;

import dev.briefestboxer.core.AimDestination;
import dev.briefestboxer.core.Trajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the spots on a Sulfur Cube that send it to the {@link AimDestination}.
 *
 * <p>Each face turned towards the player is split into a grid. For every cell,
 * the hit is forecast with the player aiming at that cell's centre (aim changes
 * the knockback direction and its vertical split), using the same native physics
 * and synced start state as the trajectory preview. The landing point is very
 * sensitive to aim, so the cells landing closest to the destination are split
 * 3x3 and re-evaluated, a few levels deep. The finest cells that come to rest
 * inside the destination are drawn green. Work is spread over frames under a
 * small time budget, and restarts whenever an input that changes the outcome
 * does (the cube's state, the player's position, charge, items, destination).
 */
final class SulfurAimSolver {
    /** Odd sizes keep each face's centre at a cell centre at every level. */
    static final int GRID = 7;
    private final int grid;

    SulfurAimSolver() {
        this(GRID);
    }

    /** @param grid starting cells per face edge (odd) */
    SulfurAimSolver(int grid) {
        this.grid = grid;
    }
    static final int SPLIT = 3;
    static final int MAX_DEPTH = 3;
    /** Cells split per refinement round, closest to the destination first. */
    private static final int REFINE_PER_ROUND = 8;
    /**
     * A cell is split only if it lands within this many blocks of the
     * destination (per depth); finer cells must land closer to earn a split.
     */
    private static final double[] REFINE_DISTANCE = {16.0, 6.0, 2.5};
    /** Hard cap on evaluated cells per solve, so a full solve stays a second or two. */
    static final int MAX_CELLS = 700;
    /** A launched cube settles well within this; a floating one is judged where it is. */
    static final int SOLVE_STEPS = 600;
    /** Share of each frame the solver may use, and its floor and ceiling per frame. */
    private static final double FRAME_SHARE = 0.15;
    private static final long MIN_BUDGET_NANOS = 1_000_000L, MAX_BUDGET_NANOS = 4_000_000L;
    /** Stand still this long after moving before the solve restarts. */
    static final long MOVE_SETTLE_NANOS = 300_000_000L;
    private static final int LANDS_COLOR = 0x55FF55;

    enum Result { PENDING, LANDS, MISSES, OUT_OF_REACH }

    /** One cell: a face, its span as face fractions, the aim point and its outcome. */
    static final class Cell {
        final Direction face;
        final double s0, s1, t0, t1;
        final int depth;
        final Vec3 aimPoint;
        Result result = Result.PENDING;
        Vec3 restingFeet;
        double distance = Double.POSITIVE_INFINITY;
        /** Split into finer cells, which are drawn instead. */
        boolean refined;

        Cell(AABB box, Direction face, double s0, double s1, double t0, double t1, int depth) {
            this.face = face;
            this.s0 = s0;
            this.s1 = s1;
            this.t0 = t0;
            this.t1 = t1;
            this.depth = depth;
            this.aimPoint = facePoint(box, face, (s0 + s1) * 0.5, (t0 + t1) * 0.5);
        }

        boolean leaf() {
            return !refined;
        }
    }

    private record Key(int cubeId, Object start, long eyeX, long eyeY, long eyeZ,
            int charge, ItemStack weapon, ItemStack body, AimDestination.Target target, AABB aimBounds) {
        boolean same(Key other) {
            return sameExceptEye(other) && sameEye(other);
        }

        boolean sameEye(Key other) {
            return other != null && eyeX == other.eyeX && eyeY == other.eyeY && eyeZ == other.eyeZ;
        }

        /** Everything but the player's position: these changes restart the solve at once. */
        boolean sameExceptEye(Key other) {
            return other != null && cubeId == other.cubeId && start.equals(other.start)
                    && charge == other.charge && target.equals(other.target) && aimBounds.equals(other.aimBounds)
                    && ItemStack.isSameItemSameComponents(weapon, other.weapon)
                    && ItemStack.isSameItemSameComponents(body, other.body);
        }
    }

    private Key key;
    private final List<Cell> cells = new ArrayList<>();
    private int next;
    private boolean exhausted;
    /** Position-only change awaiting the player standing still; null when none. */
    private Key moving;
    private long stillSinceNanos;

    void clear() {
        key = null;
        cells.clear();
        next = 0;
        exhausted = false;
        moving = null;
    }

    /** Cells evaluated since the solve last (re)started. */
    int evaluated() {
        return next;
    }

    /** Whether the player moved and the solve is waiting for them to stand still. */
    boolean waitingForPlayerToSettle() {
        return moving != null;
    }

    /** Every cell evaluated so far, coarse and fine; only leaves are drawn. */
    List<Cell> cells() {
        return cells;
    }

    private AimDestination.Target target() {
        return key.target;
    }

    boolean complete() {
        return key != null && next >= cells.size() && exhausted;
    }

    /** Restarts if inputs changed, then evaluates cells until the budget runs out. */
    void update(Minecraft client, SulfurCube cube, ItemStack body, ItemStack weapon, float charge,
            SulfurSyncedState.Start start, AimDestination.Target target, long budgetNanos) {
        var player = client.player;
        Vec3 eye = player.getEyePosition();
        AABB aimBounds = aimBounds(cube);
        Key current = new Key(cube.getId(), SulfurSyncedState.outcomeKey(start), Math.round(eye.x * 20), Math.round(eye.y * 20),
                Math.round(eye.z * 20), Math.round(charge * 20), weapon.copy(), body.copy(), target, aimBounds);
        if (!current.same(key)) {
            if (current.sameExceptEye(key)) {
                // Only the player moved: the old spots no longer apply, but do not
                // re-solve on every step. Wait until they stand still.
                long now = System.nanoTime();
                if (!current.sameEye(moving)) {
                    moving = current;
                    stillSinceNanos = now;
                }
                if (now - stillSinceNanos < MOVE_SETTLE_NANOS) return;
            }
            moving = null;
            key = current;
            buildCells(aimBounds, eye);
            next = 0;
            exhausted = false;
        } else {
            moving = null;
        }
        double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        long deadline = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < deadline) {
            if (next < cells.size()) {
                evaluate(client, cube, body, weapon, charge, start, target, eye, reach, cells.get(next++));
            } else if (!exhausted && !refine(aimBounds)) {
                exhausted = true;
            } else if (exhausted) {
                return;
            }
        }
    }

    /** Splits the evaluated cells landing closest to the destination; false when none qualify. */
    private boolean refine(AABB box) {
        List<Cell> candidates = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.leaf() && cell.depth < MAX_DEPTH && cell.result != Result.PENDING
                    && cell.result != Result.OUT_OF_REACH
                    && cell.distance <= Math.max(REFINE_DISTANCE[cell.depth], target().tolerance() * 2.0)) {
                candidates.add(cell);
            }
        }
        if (candidates.isEmpty() || cells.size() + SPLIT * SPLIT > MAX_CELLS) return false;
        candidates.sort(java.util.Comparator.comparingDouble(cell -> cell.distance));
        int budget = (MAX_CELLS - cells.size()) / (SPLIT * SPLIT);
        for (Cell cell : candidates.subList(0, Math.min(Math.min(REFINE_PER_ROUND, budget), candidates.size()))) {
            cell.refined = true;
            double ds = (cell.s1 - cell.s0) / SPLIT, dt = (cell.t1 - cell.t0) / SPLIT;
            for (int i = 0; i < SPLIT; i++) {
                for (int j = 0; j < SPLIT; j++) {
                    cells.add(new Cell(box, cell.face, cell.s0 + i * ds, cell.s0 + (i + 1) * ds,
                            cell.t0 + j * dt, cell.t0 + (j + 1) * dt, cell.depth + 1));
                }
            }
        }
        return true;
    }

    /** The cube box the player aims at, including the pick margin vanilla targeting adds. */
    static AABB aimBounds(SulfurCube cube) {
        AABB box = cube.getBoundingBox();
        float pick = cube.getPickRadius();
        return round(pick > 0.0F ? box.inflate(pick) : box);
    }

    private static AABB round(AABB box) {
        return new AABB(Math.round(box.minX * 1000) / 1000.0, Math.round(box.minY * 1000) / 1000.0,
                Math.round(box.minZ * 1000) / 1000.0, Math.round(box.maxX * 1000) / 1000.0,
                Math.round(box.maxY * 1000) / 1000.0, Math.round(box.maxZ * 1000) / 1000.0);
    }

    private void buildCells(AABB box, Vec3 eye) {
        cells.clear();
        for (Direction face : Direction.values()) {
            Vec3 faceCenter = facePoint(box, face, 0.5, 0.5);
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            if (eye.subtract(faceCenter).dot(normal) <= 0.0) continue; // Turned away from the player.
            for (int u = 0; u < grid; u++) {
                for (int v = 0; v < grid; v++) {
                    cells.add(new Cell(box, face, (double) u / grid, (u + 1.0) / grid,
                            (double) v / grid, (v + 1.0) / grid, 0));
                }
            }
        }
    }

    /** A point on a box face, at fractions (s, t) along that face's two in-plane axes. */
    static Vec3 facePoint(AABB box, Direction face, double s, double t) {
        double x, y, z;
        switch (face.getAxis()) {
            case X -> {
                x = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.maxX : box.minX;
                y = Mth.lerp(t, box.minY, box.maxY);
                z = Mth.lerp(s, box.minZ, box.maxZ);
            }
            case Y -> {
                y = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.maxY : box.minY;
                x = Mth.lerp(s, box.minX, box.maxX);
                z = Mth.lerp(t, box.minZ, box.maxZ);
            }
            default -> {
                z = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.maxZ : box.minZ;
                x = Mth.lerp(s, box.minX, box.maxX);
                y = Mth.lerp(t, box.minY, box.maxY);
            }
        }
        return new Vec3(x, y, z);
    }

    private static void evaluate(Minecraft client, SulfurCube cube, ItemStack body, ItemStack weapon,
            float charge, SulfurSyncedState.Start start, AimDestination.Target target, Vec3 eye,
            double reach, Cell cell) {
        if (eye.distanceTo(cell.aimPoint) > reach
                || !ForgeClientAimGuide.isPointVisible(client, client.player, eye, cell.aimPoint)) {
            cell.result = Result.OUT_OF_REACH;
            return;
        }
        Trajectory path = forecastAimingAt(client, cube, body, weapon, charge, start, cell.aimPoint, SOLVE_STEPS);
        var end = path.getPositions().getLast();
        cell.restingFeet = new Vec3(end.x, end.y - cube.getBbHeight() * 0.5, end.z);
        cell.distance = target.distance(cell.restingFeet.x, cell.restingFeet.y, cell.restingFeet.z);
        cell.result = target.contains(cell.restingFeet.x, cell.restingFeet.y, cell.restingFeet.z)
                ? Result.LANDS : Result.MISSES;
    }

    /**
     * Forecasts the hit with the player looking at {@code aimPoint}. The view is
     * restored before returning; rendering is single-threaded and no packet is
     * sent in between, so nothing else observes the temporary rotation.
     */
    static Trajectory forecastAimingAt(Minecraft client, SulfurCube cube, ItemStack body, ItemStack weapon,
            float charge, SulfurSyncedState.Start start, Vec3 aimPoint, int steps) {
        return forecastAimingAt(client, cube, body, weapon, charge, start, aimPoint, steps, true);
    }

    /** @param stopWhenSettled end once the resting spot is fixed; the result's last sample is unchanged. */
    static Trajectory forecastAimingAt(Minecraft client, SulfurCube cube, ItemStack body, ItemStack weapon,
            float charge, SulfurSyncedState.Start start, Vec3 aimPoint, int steps, boolean stopWhenSettled) {
        var player = client.player;
        float yRot = player.getYRot(), xRot = player.getXRot();
        try {
            float[] rotation = rotationTowards(player.getEyePosition(), aimPoint);
            player.setYRot(rotation[0]);
            player.setXRot(rotation[1]);
            return ForgeClientAimGuide.calculateSulfurTrajectory(client, cube, body, weapon, charge, start, steps,
                    stopWhenSettled);
        } finally {
            player.setYRot(yRot);
            player.setXRot(xRot);
        }
    }

    /** Yaw and pitch from {@code eye} towards {@code point}, as Entity.lookAt computes them. */
    static float[] rotationTowards(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F);
        float pitch = Mth.wrapDegrees((float) -(Mth.atan2(dy, horizontal) * 180.0 / Math.PI));
        return new float[] {yaw, pitch};
    }

    /** Draws the landing cells on the cube as the player sees it this frame. */
    void render(AABB renderAimBounds) {
        if (moving != null) return; // Spots were solved for where the player stood.
        List<Vec3[]> quads = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.result != Result.LANDS || !cell.leaf()) continue;
            Vec3 outward = new Vec3(cell.face.getStepX(), cell.face.getStepY(), cell.face.getStepZ()).scale(0.003);
            double s0 = cell.s0, s1 = cell.s1, t0 = cell.t0, t1 = cell.t1;
            quads.add(new Vec3[] {
                    facePoint(renderAimBounds, cell.face, s0, t0).add(outward),
                    facePoint(renderAimBounds, cell.face, s1, t0).add(outward),
                    facePoint(renderAimBounds, cell.face, s1, t1).add(outward),
                    facePoint(renderAimBounds, cell.face, s0, t1).add(outward)});
        }
        if (quads.isEmpty()) return;
        int color = (0xAA << 24) | LANDS_COLOR;
        Gizmos.addGizmo((primitives, progress) -> {
            for (Vec3[] quad : quads) primitives.addQuad(quad[0], quad[1], quad[2], quad[3], color);
        }).setAlwaysOnTop();
    }

    /** Marks the destination: the box itself, or a flat square of a point's radius, plus its centre. */
    static void renderDestination(AimDestination.Target target) {
        int color = 0xFF000000 | LANDS_COLOR;
        AABB area = target.box
                ? new AABB(target.minX, target.minY, target.minZ, target.maxX, target.maxY, target.maxZ)
                : new AABB(target.x - target.radius, target.y, target.z - target.radius,
                        target.x + target.radius, target.y + 0.05, target.z + target.radius);
        Gizmos.cuboid(area, GizmoStyle.strokeAndFill(color, 2.0F, (0x33 << 24) | LANDS_COLOR)).setAlwaysOnTop();
        Gizmos.point(new Vec3(target.x, target.y, target.z), color, 10.0F).setAlwaysOnTop();
    }

    private long lastFrameNanos;
    private double frameNanos = 16_666_667.0;

    /**
     * This frame's solver budget: a share of the smoothed frame time, so high
     * frame rates are not slowed by a fixed cost. Call once per frame.
     */
    long frameBudgetNanos() {
        long now = System.nanoTime();
        long interval = now - lastFrameNanos;
        if (lastFrameNanos != 0 && interval > 0 && interval < 250_000_000L) {
            frameNanos += (interval - frameNanos) * 0.1;
        }
        lastFrameNanos = now;
        return Math.max(MIN_BUDGET_NANOS, Math.min(MAX_BUDGET_NANOS, (long) (frameNanos * FRAME_SHARE)));
    }
}
