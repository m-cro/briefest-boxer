package dev.briefestboxer.fabric;

import dev.briefestboxer.core.AimDestination;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Checks /sulfurtarget's hit spots against real server hits. */
@SuppressWarnings("UnstableApiUsage")
final class SulfurAimSolverClientChecks {
    private static final int X = 32;
    private static final double RADIUS = 1.0;

    private SulfurAimSolverClientChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        var server = singleplayer.getServer();
        var connection = singleplayer.getConnection();
        int cubeId = server.computeOnServer(minecraftServer -> {
            var level = connection.getServerLevel();
            for (int x = X - 10; x <= X + 10; x++) {
                for (int z = -6; z <= 70; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, -59, z), Blocks.STONE.defaultBlockState());
                    for (int y = -58; y <= -46; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
            var player = connection.getServerPlayer();
            player.teleportTo(X + 0.5, -58.0, 0.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.COMMAND);
            cube.setBaby(false);
            cube.setSize(2, true);
            if (!cube.equipItem(new ItemStack(Blocks.OAK_LOG))) throw new AssertionError("Oak log was rejected");
            cube.getGoalSelector().removeAllGoals(goal -> true);
            cube.setPos(X + 0.5, -58.0, 3.5);
            level.addFreshEntity(cube);
            return cube.getId();
        });
        context.waitTicks(40);
        connection.waitForClientboundPackets();

        // Choose a destination the centre of the near face is known to reach,
        // then solve the whole grid for it.
        Solved solved = context.computeOnClient(client -> {
            client.player.setPos(X + 0.5, -58.0, 0.5);
            SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
            ItemStack body = cube.getItemBySlot(EquipmentSlot.BODY);
            ItemStack weapon = client.player.getWeaponItem();
            float charge = client.player.getAttackStrengthScale(0.5F);
            var start = BriefestBoxerClient.syncedStart(cube);
            Vec3 faceCenter = SulfurAimSolver.facePoint(SulfurAimSolver.aimBounds(cube), Direction.NORTH, 0.5, 0.5);
            var reference = SulfurAimSolver.forecastAimingAt(client, cube, body, weapon, charge, start,
                    faceCenter, SulfurAimSolver.SOLVE_STEPS).getPositions().getLast();
            var target = AimDestination.set(reference.x, reference.y - cube.getBbHeight() * 0.5, reference.z, RADIUS);
            var solver = new SulfurAimSolver();
            long solveStart = System.nanoTime();
            while (!solver.complete()) {
                solver.update(client, cube, body, weapon, charge, start, target, Long.MAX_VALUE / 4);
            }
            SulfurAimSolver.Cell lands = null, misses = null;
            int landing = 0, missing = 0;
            for (SulfurAimSolver.Cell cell : solver.cells()) {
                if (!cell.leaf()) continue;
                if (cell.result == SulfurAimSolver.Result.LANDS) {
                    landing++;
                    // Prefer a landing cell away from the reference aim.
                    if (lands == null || cell.aimPoint.distanceTo(faceCenter) > lands.aimPoint.distanceTo(faceCenter)) {
                        lands = cell;
                    }
                } else if (cell.result == SulfurAimSolver.Result.MISSES) {
                    missing++;
                    // A clear miss that still stays on the test platform.
                    if (misses == null && cell.distance > 3 * RADIUS && cell.distance < 20.0) misses = cell;
                }
            }
            long settledNanos = System.nanoTime() - solveStart;
            System.out.println("[Briefest Boxer GameTest] /sulfurtarget full solve: " + solver.cells().size()
                    + " cells evaluated in " + settledNanos / 1_000_000 + " ms");
            // Stopping once a cube has settled in place must not move any resting spot.
            long fullStart = System.nanoTime();
            double worst = 0.0;
            int differing = 0;
            for (SulfurAimSolver.Cell cell : solver.cells()) {
                if (cell.restingFeet == null) continue;
                var end = SulfurAimSolver.forecastAimingAt(client, cube, body, weapon, charge, start, cell.aimPoint,
                        SulfurAimSolver.SOLVE_STEPS, false).getPositions().getLast();
                Vec3 feet = new Vec3(end.x, end.y - cube.getBbHeight() * 0.5, end.z);
                worst = Math.max(worst, feet.distanceTo(cell.restingFeet));
                if (target.contains(feet.x, feet.y, feet.z) != (cell.result == SulfurAimSolver.Result.LANDS)) differing++;
            }
            long fullNanos = System.nanoTime() - fullStart;
            if (worst > 1.0E-9 || differing > 0) {
                throw new AssertionError("Early settle stop changed results: worst resting-spot difference " + worst
                        + ", " + differing + " cells classified differently");
            }
            System.out.println("[Briefest Boxer GameTest] /sulfurtarget early settle stop: identical resting spots for "
                    + solver.cells().size() + " cells; full simulation " + fullNanos / 1_000_000 + " ms vs "
                    + settledNanos / 1_000_000 + " ms with the stop");
            int leaves = (int) solver.cells().stream().filter(SulfurAimSolver.Cell::leaf).count();
            return new Solved(target, lands, misses, landing, missing, leaves, client.player.getEyePosition());
        });
        // The command, typed in chat with relative coordinates: a point, then an
        // area around the reference landing spot that the live solve then uses.
        AimDestination.Target[] typed = context.computeOnClient(client -> {
            Vec3 feet = client.player.position();
            client.player.connection.sendCommand("sulfurtarget set ~ ~1 ~3 2");
            AimDestination.Target point = AimDestination.current();
            var expected = new AimDestination.Target(feet.x, feet.y + 1, feet.z + 3, 2);
            if (!expected.equals(point)) throw new AssertionError("'/sulfurtarget set ~ ~1 ~3 2' gave " + point);
            var landing = solved.target();
            int bx = (int) Math.floor(landing.x), by = (int) Math.floor(landing.y), bz = (int) Math.floor(landing.z);
            int px = (int) Math.floor(feet.x), py = (int) Math.floor(feet.y), pz = (int) Math.floor(feet.z);
            client.player.connection.sendCommand(String.format(java.util.Locale.ROOT,
                    "sulfurtarget area ~%d ~%d ~%d ~%d ~%d ~%d",
                    bx - 1 - px, by - py, bz - 1 - pz, bx + 1 - px, by - py, bz + 1 - pz));
            AimDestination.Target area = AimDestination.current();
            var expectedArea = AimDestination.Target.blockArea(bx - 1, by, bz - 1, bx + 1, by, bz + 1);
            if (!expectedArea.equals(area)) throw new AssertionError("'/sulfurtarget area' with ~ gave " + area
                    + " expected " + expectedArea.minX + " " + expectedArea.minY + " " + expectedArea.minZ);
            return new AimDestination.Target[] {area};
        });
        // The overlay solves a little per frame while game ticks advance. The
        // cube's tick count and the click timing change every tick; a resting
        // cube's solve must still finish rather than restart each tick.
        SulfurAimSolver live = new SulfurAimSolver();
        boolean wallClock = SulfurSyncedState.serverRunsOnWallClock;
        SulfurSyncedState.serverRunsOnWallClock = true;
        int liveTicks = 0;
        try {
            for (; liveTicks < 200; liveTicks++) {
                boolean done = context.computeOnClient(client -> {
                    SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
                    live.update(client, cube, cube.getItemBySlot(EquipmentSlot.BODY), client.player.getWeaponItem(),
                            client.player.getAttackStrengthScale(0.5F), BriefestBoxerClient.syncedStart(cube),
                            typed[0], 20_000_000L);
                    return live.complete();
                });
                if (done) break;
                context.waitTicks(1);
            }
        } finally {
            SulfurSyncedState.serverRunsOnWallClock = wallClock;
        }
        long liveLanding = live.cells().stream()
                .filter(cell -> cell.leaf() && cell.result == SulfurAimSolver.Result.LANDS).count();
        if (!live.complete() || liveLanding == 0) {
            throw new AssertionError("Per-frame solve across live ticks did not finish with landing spots: complete="
                    + live.complete() + ", landing=" + liveLanding + ", cells=" + live.cells().size()
                    + " after " + liveTicks + " ticks");
        }
        // Moving pauses the solve until the player stands still; changing what
        // they hold restarts it at once.
        String movement = context.computeOnClient(client -> {
            SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
            ItemStack body = cube.getItemBySlot(EquipmentSlot.BODY);
            var start = BriefestBoxerClient.syncedStart(cube);
            client.player.setPos(X + 1.0, -58.0, 0.5);
            int before = live.evaluated();
            live.update(client, cube, body, client.player.getWeaponItem(), client.player.getAttackStrengthScale(0.5F),
                    start, typed[0], 20_000_000L);
            if (!live.waitingForPlayerToSettle() || live.evaluated() != before) {
                throw new AssertionError("Moving restarted the solve without waiting: waiting="
                        + live.waitingForPlayerToSettle() + ", evaluated " + before + " -> " + live.evaluated());
            }
            return "paused";
        });
        try {
            Thread.sleep(SulfurAimSolver.MOVE_SETTLE_NANOS / 1_000_000 + 50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        context.computeOnClient(client -> {
            SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
            ItemStack body = cube.getItemBySlot(EquipmentSlot.BODY);
            var start = BriefestBoxerClient.syncedStart(cube);
            live.update(client, cube, body, client.player.getWeaponItem(), client.player.getAttackStrengthScale(0.5F),
                    start, typed[0], 1L);
            if (live.waitingForPlayerToSettle() || live.complete()) {
                throw new AssertionError("Standing still after moving did not restart the solve");
            }
            while (!live.complete()) {
                live.update(client, cube, body, client.player.getWeaponItem(), client.player.getAttackStrengthScale(0.5F),
                        start, typed[0], 20_000_000L);
            }
            // Swap what is held while standing still: restarts immediately.
            ItemStack held = client.player.getMainHandItem();
            client.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_SWORD));
            live.update(client, cube, body, client.player.getWeaponItem(), client.player.getAttackStrengthScale(0.5F),
                    start, typed[0], 1L);
            boolean restarted = !live.waitingForPlayerToSettle() && !live.complete();
            client.player.setItemInHand(InteractionHand.MAIN_HAND, held);
            client.player.setPos(X + 0.5, -58.0, 0.5);
            if (!restarted) throw new AssertionError("Changing the held item did not restart the solve at once");
            return null;
        });
        System.out.println("[Briefest Boxer GameTest] /sulfurtarget restart rules: moving " + movement
                + " the solve until the player stood still; swapping the held item restarted it at once");
        System.out.println("[Briefest Boxer GameTest] /sulfurtarget set/area with ~ parsed; per-frame solve for the"
                + " typed area finished across " + liveTicks + " live ticks with " + liveLanding + " landing spots");
        if (solved.lands() == null || solved.misses() == null) {
            throw new AssertionError("Solver must find both landing and missing spots: lands=" + solved.landing()
                    + ", misses=" + solved.missing() + " of " + solved.total());
        }

        Vec3 landed = hitAndSettle(context, singleplayer, cubeId, solved.eye(), solved.lands().aimPoint);
        if (!solved.target().contains(landed.x, landed.y, landed.z)) {
            throw new AssertionError("Hitting a green spot " + solved.lands().aimPoint + " (forecast to rest at "
                    + solved.lands().restingFeet + ") left the cube at " + landed + ", outside "
                    + RADIUS + " of " + new Vec3(solved.target().x, solved.target().y, solved.target().z));
        }
        // Reset and hit a spot the solver rejected: it must not land there.
        server.runOnServer(minecraftServer -> {
            var cube = (SulfurCube) connection.getServerLevel().getEntity(cubeId);
            cube.setPos(X + 0.5, -58.0, 3.5);
            cube.setDeltaMovement(Vec3.ZERO);
        });
        context.waitTicks(40);
        connection.waitForClientboundPackets();
        Vec3 missed = hitAndSettle(context, singleplayer, cubeId, solved.eye(), solved.misses().aimPoint);
        if (solved.target().contains(missed.x, missed.y, missed.z)) {
            throw new AssertionError("A spot the solver rejected still landed in the destination: " + missed);
        }
        AimDestination.clear();
        System.out.println("[Briefest Boxer GameTest] /sulfurtarget hit spots verified: " + solved.landing()
                + " landing and " + solved.missing() + " missing of " + solved.total() + " cells; green spot rested at "
                + landed + " (forecast " + solved.lands().restingFeet + "), rejected spot rested at " + missed);
    }

    /** Hits the cube on the server aiming at {@code aimPoint}, then waits for it to settle. */
    private static Vec3 hitAndSettle(ClientGameTestContext context, TestSingleplayerContext singleplayer,
            int cubeId, Vec3 eye, Vec3 aimPoint) {
        var server = singleplayer.getServer();
        var connection = singleplayer.getConnection();
        server.runOnServer(minecraftServer -> {
            var player = connection.getServerPlayer();
            player.setPos(X + 0.5, -58.0, 0.5);
            float[] rotation = SulfurAimSolver.rotationTowards(eye, aimPoint);
            player.setYRot(rotation[0]);
            player.setXRot(rotation[1]);
            player.attack(connection.getServerLevel().getEntity(cubeId));
            // Keep the player clear of the cube's flight.
            player.setPos(X + 9.5, -58.0, -5.5);
        });
        int still = 0;
        Vec3 previous = null;
        for (int tick = 0; tick < SulfurAimSolver.SOLVE_STEPS && still < 5; tick++) {
            context.waitTicks(1);
            Vec3 feet = server.computeOnServer(minecraftServer ->
                    connection.getServerLevel().getEntity(cubeId).position());
            still = previous != null && feet.distanceToSqr(previous) < 1.0E-10 ? still + 1 : 0;
            previous = feet;
        }
        return previous;
    }

    private record Solved(AimDestination.Target target, SulfurAimSolver.Cell lands, SulfurAimSolver.Cell misses,
            int landing, int missing, int total, Vec3 eye) {}
}
