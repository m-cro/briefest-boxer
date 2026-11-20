package dev.briefestboxer.fabric;

import dev.briefestboxer.core.Trajectory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Runs the overlay's forecast against a real dedicated server: no integrated
 * server to read, so the client must rely on synced packets alone, including the
 * bobbing phase of a floating buoyant cube that it has to fit from those syncs.
 */
@SuppressWarnings("UnstableApiUsage")
public final class SulfurMultiplayerClientGameTest implements FabricClientGameTest {
    private static final int PATH_TICKS = 48;
    /** Pool X offset, clear of the singleplayer suite's other fixtures. */
    private static final int X = 14;

    @Override
    public void runTest(ClientGameTestContext context) {
        // The project owner accepted the Minecraft EULA (https://aka.ms/MinecraftEULA)
        // for this local test server. Loom wipes the run folder before each run.
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("eula.txt"), "eula=true\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("Could not write the test server's eula.txt", e);
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            if (context.computeOnClient(client -> client.getSingleplayerServer() != null)) {
                throw new AssertionError("Dedicated-server test unexpectedly has an integrated server");
            }
            floatingHit(context, server, connection, "Dedicated-server");
            for (int delay : new int[] {1, 3}) laggyHit(context, server, connection, delay);
        }
    }

    /**
     * Holds every clientbound packet for {@code delay} ticks and lands the attack
     * {@code delay} ticks after the forecast, like a connection with a round trip
     * of {@code 2 * delay} ticks, then checks the ping-compensated forecast.
     */
    private static void laggyHit(ClientGameTestContext context, TestServerContext server,
            TestDedicatedServerConnection connection, int delay) {
        int cubeId = server.computeOnServer(minecraftServer -> {
            var level = connection.getServerLevel();
            var player = connection.getServerPlayer();
            player.teleportTo(X + 0.5, -58.0, 18.5);
            SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.COMMAND);
            cube.setBaby(false);
            cube.setSize(2, true);
            if (!cube.equipItem(new ItemStack(Blocks.OAK_LOG))) throw new AssertionError("Oak log was rejected");
            cube.getGoalSelector().removeAllGoals(goal -> true);
            cube.setPos(X + 0.5, -58.0, 21.5);
            if (!level.addFreshEntity(cube)) throw new AssertionError("Sulfur Cube spawn was rejected");
            return cube.getId();
        });
        context.waitTicks(40);
        connection.waitForClientboundPackets();
        PacketDelay lag = context.computeOnClient(client -> PacketDelay.install(client, delay));
        try {
            server.runOnServer(minecraftServer -> ((SulfurCube) connection.getServerLevel().getEntity(cubeId))
                    .setDeltaMovement(new Vec3(0.0, 0.5, 0.1)));
            for (int tick = 0; tick < delay + 3; tick++) {
                context.waitTicks(1);
                lag.advance();
            }
            float[] look = new float[2];
            Trajectory[] uncompensated = new Trajectory[1];
            Trajectory prediction = context.computeOnClient(client -> {
                SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
                client.player.setPos(X + 0.5, -58.0, 18.5);
                client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        cube.getBoundingBox().getCenter());
                look[0] = client.player.getYRot();
                look[1] = client.player.getXRot();
                ItemStack body = cube.getItemBySlot(EquipmentSlot.BODY);
                float charge = client.player.getAttackStrengthScale(0.5F);
                try {
                    SulfurSyncedState.latencyOverrideMs = 0;
                    uncompensated[0] = BriefestBoxerClient.calculateSulfurTrajectory(client, cube, body,
                            client.player.getWeaponItem(), charge, BriefestBoxerClient.syncedStart(cube));
                    SulfurSyncedState.latencyOverrideMs = 2 * delay * 50;
                    return BriefestBoxerClient.calculateSulfurTrajectory(client, cube, body,
                            client.player.getWeaponItem(), charge, BriefestBoxerClient.syncedStart(cube));
                } finally {
                    SulfurSyncedState.latencyOverrideMs = null;
                }
            });
            // The attack packet also spends the one-way delay in flight.
            for (int tick = 0; tick < delay; tick++) {
                context.waitTicks(1);
                lag.advance();
            }
            Hit hit = server.computeOnServer(minecraftServer -> {
                var cube = (SulfurCube) connection.getServerLevel().getEntity(cubeId);
                var player = connection.getServerPlayer();
                player.setPos(X + 0.5, -58.0, 18.5);
                player.setYRot(look[0]);
                player.setXRot(look[1]);
                Vec3 center = cube.getBoundingBox().getCenter();
                player.attack(cube);
                Hit result = new Hit(cube.tickCount, center, cube.getDeltaMovement());
                cube.discard();
                return result;
            });
            double[] errors = launchErrors(prediction, hit);
            double[] without = launchErrors(uncompensated[0], hit);
            if (errors[0] > 0.02 || errors[1] > 0.02) {
                throw new AssertionError("Ping-compensated hit (" + (2 * delay * 50) + " ms) mismatch: startError="
                        + errors[0] + ", launchError=" + errors[1] + "; uncompensated start="
                        + without[0] + ", launch=" + without[1]);
            }
            System.out.println("[Briefest Boxer GameTest] " + (2 * delay * 50) + " ms round-trip hit matched: startError="
                    + errors[0] + ", launchError=" + errors[1] + " (without ping compensation: start="
                    + without[0] + ", launch=" + without[1] + ")");
        } finally {
            context.runOnClient(client -> lag.remove());
            context.waitTicks(5);
        }
    }

    private static double[] launchErrors(Trajectory path, Hit hit) {
        var first = path.getPositions().get(0);
        var second = path.getPositions().get(1);
        return new double[] {
                new Vec3(first.x, first.y, first.z).distanceTo(hit.center()),
                new Vec3(second.x - first.x, second.y - first.y, second.z - first.z).distanceTo(hit.velocity())};
    }

    /** Holds decoded clientbound packets on the Netty event loop for a number of advances. */
    private static final class PacketDelay extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final int delay;
        private final java.util.ArrayDeque<Object[]> held = new java.util.ArrayDeque<>();
        private io.netty.channel.ChannelHandlerContext context;
        private long now;

        private PacketDelay(int delay) { this.delay = delay; }

        static PacketDelay install(Minecraft client, int delay) {
            try {
                var field = net.minecraft.network.Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                var channel = (io.netty.channel.Channel) field.get(client.getConnection().getConnection());
                var handler = new PacketDelay(delay);
                channel.pipeline().addBefore("packet_handler", "briefest_boxer_test_delay", handler);
                return handler;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Could not reach the client connection channel", e);
            }
        }

        @Override public void handlerAdded(io.netty.channel.ChannelHandlerContext ctx) { context = ctx; }

        @Override public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object message) {
            held.add(new Object[] {now, message});
        }

        void advance() {
            context.executor().execute(() -> {
                now++;
                while (!held.isEmpty() && (long) held.peek()[0] + delay <= now) context.fireChannelRead(held.poll()[1]);
            });
        }

        void remove() {
            context.executor().execute(() -> {
                while (!held.isEmpty()) context.fireChannelRead(held.poll()[1]);
                context.pipeline().remove(this);
            });
        }
    }

    /**
     * Hits a floating buoyant cube using only synced client state. Also run from
     * singleplayer with the integrated-server shortcut disabled.
     */
    static void floatingHit(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, String label) {
        {
            int cubeId = server.computeOnServer(minecraftServer -> {
                var level = connection.getServerLevel();
                for (int x = X - 5; x <= X + 5; x++) {
                    for (int z = 18; z <= 56; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, -59, z), Blocks.STONE.defaultBlockState());
                        for (int y = -58; y <= -52; y++) {
                            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                // An enclosed one-deep pool of still source water.
                for (int x = X - 4; x <= X + 4; x++) {
                    for (int z = 29; z <= 55; z++) {
                        boolean wall = x == X - 4 || x == X + 4 || z == 29 || z == 55;
                        level.setBlockAndUpdate(new BlockPos(x, -58, z),
                                wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                    }
                }
                var player = connection.getServerPlayer();
                player.teleportTo(X + 0.5, -58.0, 27.5);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.COMMAND);
                if (cube == null) throw new AssertionError("Could not create a Sulfur Cube");
                cube.setBaby(false);
                cube.setSize(2, true);
                if (!cube.equipItem(new ItemStack(Blocks.OAK_LOG))) throw new AssertionError("Oak log was rejected");
                cube.getGoalSelector().removeAllGoals(goal -> true);
                cube.setPos(X + 0.5, -58.0, 33.5);
                if (!level.addFreshEntity(cube)) throw new AssertionError("Sulfur Cube spawn was rejected");
                return cube.getId();
            });
            // Let the cube settle into bobbing and the client fit its phase.
            context.waitTicks(80);
            connection.waitForClientboundPackets();

            float[] look = new float[2];
            Trajectory[] unfitted = new Trajectory[1];
            int[] estimate = new int[1];
            Trajectory prediction = context.computeOnClient(client -> {
                SulfurCube cube = (SulfurCube) client.level.getEntity(cubeId);
                if (cube == null) throw new AssertionError("Client never received the Sulfur Cube");
                if (!cube.isInWater()) throw new AssertionError("Fixture cube is not floating in water");
                client.player.setPos(X + 0.5, -58.0, 27.5);
                client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        cube.getBoundingBox().getCenter());
                look[0] = client.player.getYRot();
                look[1] = client.player.getXRot();
                var start = BriefestBoxerClient.syncedStart(cube);
                estimate[0] = start.tickCount();
                ItemStack body = cube.getItemBySlot(EquipmentSlot.BODY);
                float charge = client.player.getAttackStrengthScale(0.5F);
                // For the log: the same forecast without the fitted bobbing phase.
                unfitted[0] = BriefestBoxerClient.calculateSulfurTrajectory(client, cube, body,
                        client.player.getWeaponItem(), charge, new SulfurSyncedState.Start(start.bounds(),
                                start.velocity(), start.onGround(), start.catchUpTicks(), cube.tickCount));
                return BriefestBoxerClient.calculateSulfurTrajectory(client, cube, body,
                        client.player.getWeaponItem(), charge, start);
            });
            Hit hit = server.computeOnServer(minecraftServer -> {
                var cube = (SulfurCube) connection.getServerLevel().getEntity(cubeId);
                var player = connection.getServerPlayer();
                player.setPos(X + 0.5, -58.0, 27.5);
                player.setYRot(look[0]);
                player.setXRot(look[1]);
                int tickCount = cube.tickCount;
                Vec3 center = cube.getBoundingBox().getCenter();
                player.attack(cube);
                return new Hit(tickCount, center, cube.getDeltaMovement());
            });
            double phaseError = Math.abs(Math.atan2(Math.sin(0.4 * (estimate[0] - hit.tickCount())),
                    Math.cos(0.4 * (estimate[0] - hit.tickCount()))));
            if (phaseError > 0.05) {
                throw new AssertionError("Fitted bobbing phase is off by " + phaseError + " rad: estimate "
                        + estimate[0] + ", server tickCount " + hit.tickCount());
            }
            var first = prediction.getPositions().get(0);
            var second = prediction.getPositions().get(1);
            double startError = new Vec3(first.x, first.y, first.z).distanceTo(hit.center());
            double launchError = new Vec3(second.x - first.x, second.y - first.y, second.z - first.z)
                    .distanceTo(hit.velocity());
            if (startError > 0.02 || launchError > 0.02) {
                throw new AssertionError("Multiplayer floating hit mismatch: startError=" + startError
                        + ", launchError=" + launchError + ", actual start " + hit.center()
                        + ", launch " + hit.velocity());
            }
            double maxError = 0.0;
            double maxUnfittedError = 0.0;
            StringBuilder trace = new StringBuilder();
            for (int tick = 1; tick <= PATH_TICKS; tick++) {
                context.waitTicks(1);
                Vec3 actual = server.computeOnServer(minecraftServer ->
                        connection.getServerLevel().getEntity(cubeId).getBoundingBox().getCenter());
                var expected = prediction.getPositions().get(tick);
                var guess = unfitted[0].getPositions().get(tick);
                double error = actual.distanceTo(new Vec3(expected.x, expected.y, expected.z));
                maxError = Math.max(maxError, error);
                maxUnfittedError = Math.max(maxUnfittedError, actual.distanceTo(new Vec3(guess.x, guess.y, guess.z)));
                trace.append(tick).append(": expected=").append(expected.x).append(',').append(expected.y)
                        .append(',').append(expected.z).append(" actual=").append(actual).append("; ");
                if (error > 0.05) {
                    throw new AssertionError("Multiplayer floating path diverged at tick " + tick
                            + ", error " + error + "; trace=" + trace);
                }
            }
            System.out.println("[Briefest Boxer GameTest] " + label + " floating hit matched " + PATH_TICKS
                    + " ticks: phaseError=" + phaseError + " rad, startError=" + startError
                    + ", launchError=" + launchError + ", max path error=" + maxError
                    + " (without the fitted phase: " + maxUnfittedError + ")");
        }
    }

    private record Hit(int tickCount, Vec3 center, Vec3 velocity) {}
}
