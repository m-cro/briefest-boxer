package dev.briefestboxer.forge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.level.storage.loot.predicates.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Evaluates synchronized, client-observable enchantment requirements without a fake ServerLevel. */
final class ClientEnchantmentValues {
    private enum Match { YES, NO, UNKNOWN }
    private interface Requirement { Match matches(Entity victim, DamageSource source); }
    private static final Requirement UNKNOWN = (v, s) -> Match.UNKNOWN;
    private static final Map<LootItemCondition, Requirement> CACHE = new WeakHashMap<>();
    private static Object registry;

    static float apply(ItemStack weapon, Entity victim, DamageSource source,
            DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>> component, float value) {
        var access = victim.level().registryAccess();
        if (registry != access) { CACHE.clear(); registry = access; }
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        var random = RandomSource.create(0L);
        for (var entry : weapon.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            for (var effect : entry.getKey().value().getEffects(component)) {
                Match match = effect.requirements().map(condition -> CACHE.computeIfAbsent(condition,
                        c -> compile(c, ops)).matches(victim, source)).orElse(Match.YES);
                // Unknown is deliberately not false: NOT(unknown) must never
                // turn a server-only requirement into a guaranteed bonus.
                if (match == Match.YES) value = effect.effect().process(entry.getIntValue(), random, value);
            }
        }
        return value;
    }

    private static Requirement compile(LootItemCondition condition, RegistryOps<JsonElement> ops) {
        if (condition instanceof InvertedLootItemCondition inverted) {
            Requirement child = compile(inverted.term(), ops);
            return (v, s) -> switch (child.matches(v, s)) {
                case YES -> Match.NO;
                case NO -> Match.YES;
                case UNKNOWN -> Match.UNKNOWN;
            };
        }
        if (condition instanceof LootItemEntityPropertyCondition entity) {
            if (entity.predicate().isEmpty()) return (v, s) -> Match.YES;
            var predicate = compileEntity(entity.predicate().get(), ops);
            return (v, s) -> predicate.matches(switch (entity.entityTarget()) {
                case THIS -> v;
                case ATTACKER -> s.getEntity();
                case DIRECT_ATTACKER -> s.getDirectEntity();
                default -> null;
            }, v, s);
        }
        if (condition instanceof DamageSourceCondition damage) {
            if (damage.predicate().isEmpty()) return (v, s) -> Match.YES;
            var p = damage.predicate().get();
            EntityRequirement direct = p.directEntity().map(e -> compileEntity(e, ops)).orElse((e, v, s) -> Match.YES);
            EntityRequirement attacker = p.sourceEntity().map(e -> compileEntity(e, ops)).orElse((e, v, s) -> Match.YES);
            return (v, s) -> {
                for (var tag : p.tags()) if (!tag.matches(s.typeHolder())) return Match.NO;
                if (p.isDirect().isPresent() && p.isDirect().get() != s.isDirect()) return Match.NO;
                return all(List.of(direct.matches(s.getDirectEntity(), v, s), attacker.matches(s.getEntity(), v, s)));
            };
        }
        if (condition instanceof AllOfCondition || condition instanceof AnyOfCondition) {
            // Composite terms are not exposed by the native API. Decode through
            // the synchronized registry codec once, rather than using reflection.
            var encoded = LootItemCondition.DIRECT_CODEC.encodeStart(ops, condition).result();
            if (encoded.isEmpty() || !encoded.get().isJsonObject()) return UNKNOWN;
            var terms = encoded.get().getAsJsonObject().getAsJsonArray("terms");
            if (terms == null) return UNKNOWN;
            List<Requirement> children = new ArrayList<>();
            for (var term : terms) {
                var decoded = LootItemCondition.DIRECT_CODEC.parse(ops, term).result();
                children.add(decoded.map(c -> compile(c, ops)).orElse(UNKNOWN));
            }
            boolean conjunction = condition instanceof AllOfCondition;
            return (v, s) -> {
                boolean unknown = false;
                for (var child : children) {
                    Match result = child.matches(v, s);
                    if (conjunction && result == Match.NO) return Match.NO;
                    if (!conjunction && result == Match.YES) return Match.YES;
                    unknown |= result == Match.UNKNOWN;
                }
                return unknown ? Match.UNKNOWN : conjunction ? Match.YES : Match.NO;
            };
        }
        // Random rolls, named server loot predicates, and server-only context
        // cannot be known from multiplayer client state.
        return UNKNOWN;
    }

    private interface EntityRequirement { Match matches(Entity entity, Entity victim, DamageSource source); }

    private static EntityRequirement compileEntity(EntityPredicate predicate, RegistryOps<JsonElement> ops) {
        var encoded = EntityPredicate.CODEC.encodeStart(ops, predicate).result();
        if (encoded.isEmpty() || !encoded.get().isJsonObject()) return (e, v, s) -> Match.UNKNOWN;
        List<EntitySubPredicate> parts = new ArrayList<>();
        boolean unsupported = false;
        for (var entry : encoded.get().getAsJsonObject().entrySet()) {
            var codec = BuiltInRegistries.ENTITY_SUB_PREDICATE_TYPE.getValue(Identifier.parse(entry.getKey()));
            if (codec == null) { unsupported = true; continue; }
            var part = codec.parse(ops, entry.getValue()).result();
            if (part.isEmpty() || !clientSafe(part.get())) { unsupported = true; continue; }
            parts.add(part.get());
        }
        final boolean unknown = unsupported;
        return (entity, victim, source) -> {
            if (entity == null) return Match.NO;
            for (var part : parts) {
                // These native predicates only inspect synchronized entity data;
                // none reads the ServerLevel argument.
                if (!part.matches(entity, null, victim.position())) return Match.NO;
            }
            return unknown ? Match.UNKNOWN : Match.YES;
        };
    }

    private static boolean clientSafe(EntitySubPredicate part) {
        return part instanceof EntityTypePredicate || part instanceof EntityFlagsPredicate
                || part instanceof EntityEquipmentPredicate || part instanceof EntityEffectsPredicate
                || part instanceof DistanceToPlayerPredicate || part instanceof MovementPredicate
                || part instanceof EntityExactDataComponentsPredicate || part instanceof EntityPartialComponentsPredicate
                || part instanceof TeamPredicate || part instanceof CubeMobPredicate;
    }

    private static Match all(List<Match> matches) {
        if (matches.contains(Match.NO)) return Match.NO;
        return matches.contains(Match.UNKNOWN) ? Match.UNKNOWN : Match.YES;
    }
}
