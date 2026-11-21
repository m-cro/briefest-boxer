package dev.briefestboxer.forge;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.briefestboxer.core.CommandCoordinate;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** One client-command coordinate: {@code 12}, {@code -3.5}, {@code ~} or {@code ~2}. Never sent to a server. */
final class CoordinateArgument implements ArgumentType<CommandCoordinate> {
    private static final DynamicCommandExceptionType INVALID = new DynamicCommandExceptionType(
            text -> Component.literal("Invalid coordinate '" + text + "': use a number, ~ or ~<offset>"));

    static CoordinateArgument coordinate() {
        return new CoordinateArgument();
    }

    static CommandCoordinate get(CommandContext<?> context, String name) {
        return context.getArgument(name, CommandCoordinate.class);
    }

    @Override
    public CommandCoordinate parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') reader.skip();
        String text = reader.getString().substring(start, reader.getCursor());
        try {
            return CommandCoordinate.parse(text);
        } catch (IllegalArgumentException invalid) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader, text);
        }
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if ("~".startsWith(builder.getRemaining())) builder.suggest("~");
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("12", "-3.5", "~", "~2");
    }
}
