package com.devdroggy.mobstacker;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_MOB_STACKING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ITEM_STACKING;

    public static final ForgeConfigSpec.DoubleValue MOB_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ITEM_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SHOW_MOB_COUNT;

    public static final ForgeConfigSpec.IntValue MIN_STACK_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue HP_PER_STACK;

    // New option
    public static final ForgeConfigSpec.BooleanValue ENABLE_MONSTER_MERGING;

    static {
        BUILDER.push("General Settings");

        ENABLE_MOB_STACKING = BUILDER.comment("Set to false to completely disable the Mob Stacking feature.")
                .define("enableMobStacking", true);

        ENABLE_ITEM_STACKING = BUILDER
                .comment("Set to false to completely disable the Item Stacking feature.")
                .comment("Useful for preventing duplication bugs with mods like Create.")
                .define("enableItemStacking", true);

        MOB_RADIUS = BUILDER.comment("Mob Merge Radius (The distance within which mobs will be merged together)")
                .defineInRange("mob_radius", 5.0, 1.0, 20.0);

        ITEM_RADIUS = BUILDER.comment("Item Merge Radius (The distance within which dropped items will be merged)")
                .defineInRange("item_radius", 2.0, 0.5, 10.0);

        SHOW_MOB_COUNT = BUILDER.comment("Show stack count on mob names")
                .define("show_mob_count", true);

        MIN_STACK_THRESHOLD = BUILDER.comment("Minimum number of mobs required to start stacking (Min: 2)")
                .defineInRange("min_stack_threshold", 2, 2, 64);

        HP_PER_STACK = BUILDER.comment("Additional health bonus for each stacked mob")
                .defineInRange("hp_bonus_per_stack", 1.0, 0.0, 100.0);

        // New config: allow/disallow monster merging
        ENABLE_MONSTER_MERGING = BUILDER
                .comment("Set to false to prevent hostile mobs (monsters) from automatically merging.")
                .define("enableMonsterMerging", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
