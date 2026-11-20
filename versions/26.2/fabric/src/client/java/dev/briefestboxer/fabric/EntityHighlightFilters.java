package dev.briefestboxer.fabric;

import dev.briefestboxer.core.BriefestBoxerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import java.util.ArrayList;
import java.util.List;

/** Registry-based filtering; no entity creation or per-frame registry scans. */
final class EntityHighlightFilters {
    private EntityHighlightFilters() {}
    static String id(EntityType<?> type) { return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(); }
    static int group(EntityType<?> type) {
        return id(type).equals("minecraft:player") ? 0 : type.getCategory() == MobCategory.MONSTER ? 2 : 1;
    }
    static boolean enabled(EntityType<?> type) {
        int group = group(type);
        return BriefestBoxerConfig.entityTypeEnabled(id(type), group == 0, group == 2);
    }
    static List<EntityType<?>> livingTypes() {
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (DefaultAttributes.hasSupplier(type) || id(type).equals("minecraft:player")) types.add(type);
        }
        types.sort(java.util.Comparator.comparing(type -> type.getDescription().getString()));
        return types;
    }
    private static void openGroup(int group) {
        if (group == 0) {
            BriefestBoxerConfig.showAimPoints = true;
            return;
        }
        // Opening one group must not revive the other group after the master
        // mob switch had hidden both of them.
        if (!BriefestBoxerConfig.showEntities) {
            BriefestBoxerConfig.showFriendlyMobs = false;
            BriefestBoxerConfig.showHostileMobs = false;
        }
        BriefestBoxerConfig.showEntities = true;
        if (group == 1) BriefestBoxerConfig.showFriendlyMobs = true;
        else BriefestBoxerConfig.showHostileMobs = true;
    }

    static void setGroup(int group, boolean enabled, List<EntityType<?>> types) {
        if (enabled) openGroup(group);
        else if (group == 0) BriefestBoxerConfig.showAimPoints = false;
        else if (group == 1) BriefestBoxerConfig.showFriendlyMobs = false;
        else BriefestBoxerConfig.showHostileMobs = false;
        for (EntityType<?> type : types) {
            if (group(type) == group) BriefestBoxerConfig.setEntityTypeEnabled(id(type), enabled);
        }
    }

    static void setType(EntityType<?> type, boolean enabled) {
        if (enabled) {
            int group = group(type);
            // Materialize the disabled group's previous choices before opening
            // its gate, so selecting one type cannot select every sibling.
            if (!enabled(type)) {
                for (EntityType<?> sibling : livingTypes()) {
                    if (group(sibling) == group && !enabled(sibling))
                        BriefestBoxerConfig.setEntityTypeEnabled(id(sibling), false);
                }
            }
            openGroup(group);
        }
        BriefestBoxerConfig.setEntityTypeEnabled(id(type), enabled);
    }
}
