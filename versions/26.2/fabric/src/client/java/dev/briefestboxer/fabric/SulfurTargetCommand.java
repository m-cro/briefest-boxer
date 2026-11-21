package dev.briefestboxer.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.briefestboxer.core.AimDestination;
import dev.briefestboxer.core.CommandCoordinate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /sulfurtarget}: sets where a hit Sulfur Cube should come to rest. While a
 * destination is set, the overlay marks the spots on the targeted cube that land it there.
 *
 * <pre>
 * /sulfurtarget set &lt;x&gt; &lt;y&gt; &lt;z&gt; [radius]            a point (the cube's bottom centre)
 * /sulfurtarget area &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;  blocks between two corners, inclusive
 * /sulfurtarget here [radius]                     where the player stands
 * /sulfurtarget clear
 * </pre>
 * Coordinates accept {@code ~} and {@code ~offset}, relative to the player.
 */
final class SulfurTargetCommand {
    private SulfurTargetCommand() {}

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("sulfurtarget")
                .executes(context -> show(context.getSource()))
                .then(ClientCommands.literal("set")
                        .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                                .executes(context -> setPoint(context, AimDestination.DEFAULT_RADIUS))
                                .then(radiusArgument().executes(context ->
                                        setPoint(context, DoubleArgumentType.getDouble(context, "radius"))))))))
                .then(ClientCommands.literal("area")
                        .then(coordinate("x1").then(coordinate("y1").then(coordinate("z1")
                                .then(coordinate("x2").then(coordinate("y2").then(coordinate("z2")
                                        .executes(SulfurTargetCommand::setArea))))))))
                .then(ClientCommands.literal("here")
                        .executes(context -> here(context.getSource(), AimDestination.DEFAULT_RADIUS))
                        .then(radiusArgument().executes(context ->
                                here(context.getSource(), DoubleArgumentType.getDouble(context, "radius")))))
                .then(ClientCommands.literal("clear").executes(context -> {
                    AimDestination.clear();
                    context.getSource().sendFeedback(Component.literal("Sulfur Cube destination cleared."));
                    return 1;
                })));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, CommandCoordinate> coordinate(String name) {
        return ClientCommands.argument(name, CoordinateArgument.coordinate());
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Double> radiusArgument() {
        return ClientCommands.argument("radius",
                DoubleArgumentType.doubleArg(AimDestination.MIN_RADIUS, AimDestination.MAX_RADIUS));
    }

    private static int setPoint(CommandContext<FabricClientCommandSource> context, double radius) {
        Vec3 origin = context.getSource().getPosition();
        return announce(context.getSource(), AimDestination.set(
                CoordinateArgument.get(context, "x").resolvePoint(origin.x, true),
                CoordinateArgument.get(context, "y").resolvePoint(origin.y, false),
                CoordinateArgument.get(context, "z").resolvePoint(origin.z, true), radius));
    }

    private static int setArea(CommandContext<FabricClientCommandSource> context) {
        Vec3 origin = context.getSource().getPosition();
        try {
            return announce(context.getSource(), AimDestination.set(AimDestination.Target.blockArea(
                    CoordinateArgument.get(context, "x1").resolveBlock(origin.x),
                    CoordinateArgument.get(context, "y1").resolveBlock(origin.y),
                    CoordinateArgument.get(context, "z1").resolveBlock(origin.z),
                    CoordinateArgument.get(context, "x2").resolveBlock(origin.x),
                    CoordinateArgument.get(context, "y2").resolveBlock(origin.y),
                    CoordinateArgument.get(context, "z2").resolveBlock(origin.z))));
        } catch (IllegalArgumentException tooLarge) {
            context.getSource().sendError(Component.literal("Sulfur Cube destination " + tooLarge.getMessage() + "."));
            return 0;
        }
    }

    private static int here(FabricClientCommandSource source, double radius) {
        Vec3 feet = source.getPosition();
        return announce(source, AimDestination.set(feet.x, feet.y, feet.z, radius));
    }

    private static int show(FabricClientCommandSource source) {
        AimDestination.Target target = AimDestination.current();
        if (target == null) {
            source.sendFeedback(Component.literal("No Sulfur Cube destination. Use /sulfurtarget set <x> <y> <z> [radius],"
                    + " /sulfurtarget area <x1> <y1> <z1> <x2> <y2> <z2> or /sulfurtarget here [radius]"
                    + " (coordinates accept ~)."));
            return 0;
        }
        return announce(source, target);
    }

    private static int announce(FabricClientCommandSource source, AimDestination.Target target) {
        source.sendFeedback(Component.literal(describe(target)
                + ". Look at an adult Sulfur Cube: green spots send it there."));
        return 1;
    }

    static String describe(AimDestination.Target target) {
        if (target.box) {
            return String.format(java.util.Locale.ROOT, "Sulfur Cube destination: area %.1f %.1f %.1f to %.1f %.1f %.1f",
                    target.minX, target.minY, target.minZ, target.maxX, target.maxY, target.maxZ);
        }
        return String.format(java.util.Locale.ROOT, "Sulfur Cube destination: %.2f %.2f %.2f (within %.2f blocks)",
                target.x, target.y, target.z, target.radius);
    }
}
