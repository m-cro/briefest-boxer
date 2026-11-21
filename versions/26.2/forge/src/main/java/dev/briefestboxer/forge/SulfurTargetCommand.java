package dev.briefestboxer.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.briefestboxer.core.AimDestination;
import dev.briefestboxer.core.CommandCoordinate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sulfurtarget")
                .executes(context -> show(context.getSource()))
                .then(Commands.literal("set")
                        .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                                .executes(context -> setPoint(context, AimDestination.DEFAULT_RADIUS))
                                .then(radiusArgument().executes(context ->
                                        setPoint(context, DoubleArgumentType.getDouble(context, "radius"))))))))
                .then(Commands.literal("area")
                        .then(coordinate("x1").then(coordinate("y1").then(coordinate("z1")
                                .then(coordinate("x2").then(coordinate("y2").then(coordinate("z2")
                                        .executes(SulfurTargetCommand::setArea))))))))
                .then(Commands.literal("here")
                        .executes(context -> here(context.getSource(), AimDestination.DEFAULT_RADIUS))
                        .then(radiusArgument().executes(context ->
                                here(context.getSource(), DoubleArgumentType.getDouble(context, "radius")))))
                .then(Commands.literal("clear").executes(context -> {
                    AimDestination.clear();
                    context.getSource().sendSuccess(() -> Component.literal("Sulfur Cube destination cleared."), false);
                    return 1;
                })));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, CommandCoordinate> coordinate(String name) {
        return Commands.argument(name, CoordinateArgument.coordinate());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> radiusArgument() {
        return Commands.argument("radius",
                DoubleArgumentType.doubleArg(AimDestination.MIN_RADIUS, AimDestination.MAX_RADIUS));
    }

    private static int setPoint(CommandContext<CommandSourceStack> context, double radius) {
        Vec3 origin = context.getSource().getPosition();
        return announce(context.getSource(), AimDestination.set(
                CoordinateArgument.get(context, "x").resolvePoint(origin.x, true),
                CoordinateArgument.get(context, "y").resolvePoint(origin.y, false),
                CoordinateArgument.get(context, "z").resolvePoint(origin.z, true), radius));
    }

    private static int setArea(CommandContext<CommandSourceStack> context) {
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
            context.getSource().sendFailure(Component.literal("Sulfur Cube destination " + tooLarge.getMessage() + "."));
            return 0;
        }
    }

    private static int here(CommandSourceStack source, double radius) {
        Vec3 feet = source.getPosition();
        return announce(source, AimDestination.set(feet.x, feet.y, feet.z, radius));
    }

    private static int show(CommandSourceStack source) {
        AimDestination.Target target = AimDestination.current();
        if (target == null) {
            source.sendSuccess(() -> Component.literal("No Sulfur Cube destination. Use /sulfurtarget set <x> <y> <z> [radius],"
                    + " /sulfurtarget area <x1> <y1> <z1> <x2> <y2> <z2> or /sulfurtarget here [radius]"
                    + " (coordinates accept ~)."), false);
            return 0;
        }
        return announce(source, target);
    }

    private static int announce(CommandSourceStack source, AimDestination.Target target) {
        source.sendSuccess(() -> Component.literal(describe(target)
                + ". Look at an adult Sulfur Cube: green spots send it there."), false);
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
