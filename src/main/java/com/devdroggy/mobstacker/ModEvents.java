package com.devdroggy.mobstacker;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

public class ModEvents {

    public static int CHECK_INTERVAL = 10;
    private static final String STACK_NBT_KEY = "StackAmount";
    private static final String SPLIT_TIME_NBT_KEY = "MobStackerSplitTime";
    private static final int SPLIT_COOLDOWN_TICKS = 100; // 5 seconds

    private static final String HONEY_PLAYER_NBT = "HoneyPlayerUUID";
    private static final String HONEY_TIME_NBT = "HoneyTime";
    private static final String HONEY_PARTNER_NBT = "HoneyPartnerUUID";
    private static final int HONEY_TIMEOUT_TICKS = 600; // 30 seconds
    private static final double HONEY_PAIR_RADIUS = 10.0;

    private static final String LOCK_NBT_KEY = "MobStackerLocked";
    private static final UUID HEALTH_MODIFIER_UUID = UUID.fromString("d05b8a0a-e555-4e0f-bf3a-f10e1346210f");

    // ==================================================
    // 1. Mob Merging (with split cooldown & lock/honey skip)
    // ==================================================
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!ModConfig.ENABLE_MOB_STACKING.get()) return;

        LivingEntity entity = event.getEntity();

        if (entity.level().isClientSide || CHECK_INTERVAL <= 0 || entity.tickCount % CHECK_INTERVAL != 0) return;
        if (!(entity instanceof Monster) && !(entity instanceof Animal)) return;
        if (!entity.isAlive()) return;

        // Skip entities that are temporarily immune (freshly split, honeyed, or locked)
        if (hasRecentSplit(entity) || hasHoneyData(entity) || isLocked(entity)) return;

        double radius = ModConfig.MOB_RADIUS.get();
        int minThreshold = ModConfig.MIN_STACK_THRESHOLD.get();

        List<LivingEntity> neighbors = entity.level().getEntitiesOfClass(
                LivingEntity.class,
                entity.getBoundingBox().inflate(radius),
                e -> e != entity && e.getType() == entity.getType() && e.isAlive() && isCompatible(entity, e)
                        && !hasRecentSplit(e) && !hasHoneyData(e) && !isLocked(e)
        );

        if (neighbors.size() + 1 < minThreshold) return;

        for (LivingEntity neighbor : neighbors) {
            if (entity.isBaby() != neighbor.isBaby()) continue;

            int myStack = getStackSize(entity);
            int otherStack = getStackSize(neighbor);

            setStackSize(entity, myStack + otherStack);

            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                        entity.getX(), entity.getY() + 0.5, entity.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);
                serverLevel.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }

            neighbor.discard();
            break;
        }
    }

    // ==================================================
    // 2. Honey comb pairing & following logic (inside tick)
    // ==================================================
    @SubscribeEvent
    public void onHoneyTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide || !entity.isAlive()) return;

        CompoundTag data = entity.getPersistentData();
        if (!data.contains(HONEY_PLAYER_NBT)) return; // not honeyed

        if (data.contains(HONEY_PARTNER_NBT)) {
            // ---- Engaged (has partner) ----
            handleEngagedMob(entity, data);
        } else {
            // ---- Waiting (follow player, timeout, spawn sparks) ----
            handleWaitingMob(entity, data);
        }
    }

    private void handleWaitingMob(LivingEntity mob, CompoundTag data) {
        long currentTime = mob.level().getGameTime();
        long honeyTime = data.getLong(HONEY_TIME_NBT);

        // Timeout check
        if (currentTime - honeyTime > HONEY_TIMEOUT_TICKS) {
            clearHoneyData(mob);
            return;
        }

        // Follow the player who honeyed
        UUID playerUUID = UUID.fromString(data.getString(HONEY_PLAYER_NBT));
        Player player = mob.level().getPlayerByUUID(playerUUID);
        if (player != null && player.isAlive() && mob.distanceToSqr(player) > 2.0) {
            mob.getNavigation().moveTo(player, 1.2);
        }

        // Yellow sparkles every 5 ticks
        if (mob.tickCount % 5 == 0 && mob.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.WAX_ON,
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
                    1, 0.2, 0.1, 0.2, 0.01);
        }
    }

    private void handleEngagedMob(LivingEntity mob, CompoundTag data) {
        UUID partnerUUID = UUID.fromString(data.getString(HONEY_PARTNER_NBT));
        if (mob.level() instanceof ServerLevel serverLevel) {
            Entity partnerEntity = serverLevel.getEntity(partnerUUID);
            if (!(partnerEntity instanceof LivingEntity partner) || !partner.isAlive()) {
                // Partner gone, remove honey data
                clearHoneyData(mob);
                return;
            }

            // Move towards partner
            double dist = mob.distanceToSqr(partner);
            if (dist > 2.0) {
                mob.getNavigation().moveTo(partner, 1.5);
                // Hearts while moving
                if (mob.tickCount % 3 == 0) {
                    serverLevel.sendParticles(ParticleTypes.HEART,
                            mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
                            1, 0.3, 0.1, 0.3, 0.01);
                }
            } else {
                // Close enough -> merge!
                int myStack = getStackSize(mob);
                int otherStack = getStackSize(partner);

                // Combine into this mob
                setStackSize(mob, myStack + otherStack);

                // Merge particles + sound
                serverLevel.sendParticles(ParticleTypes.HEART,
                        mob.getX(), mob.getY() + 0.5, mob.getZ(),
                        5, 0.3, 0.1, 0.3, 0.1);
                serverLevel.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                        SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1.0f, 1.2f);

                // Clear honey data from the surviving mob
                clearHoneyData(mob);
                // Discard the partner
                partner.discard();
            }
        }
    }

    private void clearHoneyData(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        data.remove(HONEY_PLAYER_NBT);
        data.remove(HONEY_TIME_NBT);
        data.remove(HONEY_PARTNER_NBT);
    }

    private boolean hasHoneyData(LivingEntity entity) {
        return entity.getPersistentData().contains(HONEY_PLAYER_NBT);
    }

    // ==================================================
    // 3. Loot / XP multiplication (unchanged)
    // ==================================================
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        int stackSize = getStackSize(entity);

        if (stackSize > 1) {
            Collection<ItemEntity> drops = event.getDrops();
            List<ItemEntity> originalDrops = List.copyOf(drops);
            drops.clear();

            for (ItemEntity originalItem : originalDrops) {
                ItemStack baseStack = originalItem.getItem();
                int totalItems = baseStack.getCount() * stackSize;

                while (totalItems > 0) {
                    int amountForThisStack = Math.min(totalItems, baseStack.getMaxStackSize());
                    totalItems -= amountForThisStack;

                    ItemStack newStack = baseStack.copy();
                    newStack.setCount(amountForThisStack);

                    ItemEntity newItem = new ItemEntity(
                            entity.level(),
                            originalItem.getX(),
                            originalItem.getY(),
                            originalItem.getZ(),
                            newStack
                    );
                    newItem.setDeltaMovement(originalItem.getDeltaMovement());
                    newItem.setDefaultPickUpDelay();
                    drops.add(newItem);
                }
            }
        }
    }

    @SubscribeEvent
    public void onExperienceDrop(LivingExperienceDropEvent event) {
        LivingEntity entity = event.getEntity();
        int stackSize = getStackSize(entity);
        if (stackSize > 1) {
            event.setDroppedExperience(event.getDroppedExperience() * stackSize);
        }
    }

    // ==================================================
    // 4. Item stacking (unchanged)
    // ==================================================
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (!ModConfig.ENABLE_ITEM_STACKING.get()) return;
        if (event.level.isClientSide || event.phase != TickEvent.Phase.END) return;
        if (CHECK_INTERVAL > 0 && event.level.getGameTime() % CHECK_INTERVAL != 0) return;

        if (event.level instanceof ServerLevel serverLevel) {
            for (Entity entity : serverLevel.getAllEntities()) {
                if (entity instanceof ItemEntity itemEntity) {
                    processItemStacking(itemEntity, serverLevel);
                }
            }
        }
    }

    // ==================================================
    // 5. Interactions (sheep, honey comb, emerald, split)
    // ==================================================
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        ItemStack itemStack = event.getItemStack();
        Player player = event.getEntity();

        // --- Sheep shearing / dyeing (existing, with split cooldown fix) ---
        if (target instanceof Sheep sheep) {
            int stackSize = getStackSize(sheep);

            if (itemStack.getItem() instanceof ShearsItem) {
                if (sheep.readyForShearing() && stackSize > 1 && !event.getLevel().isClientSide) {
                    int extraSheep = stackSize - 1;
                    int totalExtraWool = 0;
                    for (int i = 0; i < extraSheep; i++) {
                        totalExtraWool += 1 + sheep.getRandom().nextInt(3);
                    }
                    if (totalExtraWool > 0) {
                        ItemLike woolItem = getWoolByColor(sheep.getColor());
                        if (woolItem != null) {
                            sheep.spawnAtLocation(new ItemStack(woolItem, totalExtraWool));
                        }
                    }
                }
            }
            // Dyeing – now adds split cooldown to prevent re-merge
            else if (itemStack.getItem() instanceof DyeItem dyeItem) {
                DyeColor newColor = dyeItem.getDyeColor();
                if (sheep.getColor() != newColor && stackSize > 1) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    if (!event.getLevel().isClientSide) {
                        if (!player.isCreative()) itemStack.shrink(1);

                        setStackSize(sheep, stackSize - 1);
                        Sheep dyedSheep = EntityType.SHEEP.create(sheep.level());
                        if (dyedSheep != null) {
                            dyedSheep.moveTo(sheep.getX(), sheep.getY(), sheep.getZ(),
                                    sheep.getYRot(), sheep.getXRot());
                            dyedSheep.setColor(newColor);
                            dyedSheep.setAge(sheep.getAge());

                            long time = sheep.level().getGameTime();
                            sheep.getPersistentData().putLong(SPLIT_TIME_NBT_KEY, time);
                            dyedSheep.getPersistentData().putLong(SPLIT_TIME_NBT_KEY, time);

                            sheep.level().addFreshEntity(dyedSheep);
                            sheep.playSound(SoundEvents.DYE_USE, 1.0F, 1.0F);
                        }
                    }
                    return;
                }
            }
        }

        // --- Honey Comb on any LivingEntity ---
        if (itemStack.getItem() == Items.HONEYCOMB && target instanceof LivingEntity living) {
            if (isLocked(living)) return; // locked mobs cannot be honeyed (optional)

            CompoundTag data = living.getPersistentData();
            // Only allow if not already honeyed or previous honey expired
            if (!data.contains(HONEY_PLAYER_NBT) || isHoneyExpired(living)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (!event.getLevel().isClientSide) {
                    if (!player.isCreative()) itemStack.shrink(1);

                    // Store honey state
                    data.putString(HONEY_PLAYER_NBT, player.getUUID().toString());
                    data.putLong(HONEY_TIME_NBT, living.level().getGameTime());
                    data.remove(HONEY_PARTNER_NBT); // ensure no old partner

                    // Look for another honeyed mob of the same player, type, compatibility, within radius
                    Player honeyPlayer = player;
                    List<LivingEntity> candidates = living.level().getEntitiesOfClass(
                            LivingEntity.class,
                            living.getBoundingBox().inflate(HONEY_PAIR_RADIUS),
                            e -> e != living && e.isAlive() && e.getType() == living.getType()
                                    && isCompatible(living, e) && hasHoneyData(e)
                                    && !e.getPersistentData().contains(HONEY_PARTNER_NBT)
                                    && e.getPersistentData().getString(HONEY_PLAYER_NBT).equals(honeyPlayer.getUUID().toString())
                    );

                    if (!candidates.isEmpty()) {
                        // Pair with the closest candidate
                        LivingEntity partner = candidates.get(0);
                        // Mark both as paired
                        data.putString(HONEY_PARTNER_NBT, partner.getUUID().toString());
                        partner.getPersistentData().putString(HONEY_PARTNER_NBT, living.getUUID().toString());

                        // Play a sound to indicate pairing
                        if (living.level() instanceof ServerLevel serverLevel) {
                            serverLevel.playSound(null, living.getX(), living.getY(), living.getZ(),
                                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.5f);
                        }
                    }
                    // (if no partner found, the mob simply waits and follows the player)
                }
            }
            return;
        }

        // --- Emerald locking ---
        if (itemStack.getItem() == Items.EMERALD && target instanceof LivingEntity living) {
            if (!isLocked(living)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (!event.getLevel().isClientSide) {
                    if (!player.isCreative()) itemStack.shrink(1);
                    living.getPersistentData().putBoolean(LOCK_NBT_KEY, true);
                    // Refresh name to show (*)
                    setStackSize(living, getStackSize(living));
                    // Sound
                    living.level().playSound(null, living.getX(), living.getY(), living.getZ(),
                            SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.NEUTRAL, 0.8f, 1.5f);
                }
            }
            return;
        }

        // --- Shift + right-click with empty hand: split or return emerald ---
        if (target instanceof LivingEntity living &&
                player.isShiftKeyDown() &&
                itemStack.isEmpty()) {

            // If locked, return the emerald and remove the lock, do NOT split
            if (isLocked(living)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (!event.getLevel().isClientSide) {
                    living.getPersistentData().remove(LOCK_NBT_KEY);
                    // Refresh name to remove (*)
                    setStackSize(living, getStackSize(living));
                    // Drop the emerald
                    living.spawnAtLocation(new ItemStack(Items.EMERALD));
                    living.playSound(SoundEvents.ITEM_PICKUP, 0.5f, 1.0f);
                }
                return;
            }

            int stackSize = getStackSize(living);
            if (stackSize > 1) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);

                if (!event.getLevel().isClientSide) {
                    int half = stackSize / 2;
                    int remaining = stackSize - half;

                    // Preserve health ratio on the original entity
                    float healthRatio = living.getHealth() / living.getMaxHealth();

                    setStackSize(living, remaining);
                    living.setHealth(living.getMaxHealth() * healthRatio);

                    EntityType<?> type = living.getType();
                    Entity newEntity = type.create(living.level());
                    if (newEntity instanceof LivingEntity newLiving) {
                        newEntity.moveTo(living.getX(), living.getY(), living.getZ(),
                                living.getYRot(), living.getXRot());

                        newLiving.setBaby(living.isBaby());
                        newLiving.setAge(living.getAge());

                        if (living instanceof Sheep oldSheep && newLiving instanceof Sheep newSheep) {
                            newSheep.setColor(oldSheep.getColor());
                        }

                        setStackSize(newLiving, half);
                        // Give the new entity the same health ratio (or full – you decide)
                        newLiving.setHealth(newLiving.getMaxHealth() * healthRatio);

                        long gameTime = living.level().getGameTime();
                        living.getPersistentData().putLong(SPLIT_TIME_NBT_KEY, gameTime);
                        newLiving.getPersistentData().putLong(SPLIT_TIME_NBT_KEY, gameTime);

                        if (living.level() instanceof ServerLevel serverLevel) {
                            serverLevel.sendParticles(ParticleTypes.CLOUD,
                                    living.getX(), living.getY() + 0.5, living.getZ(),
                                    5, 0.2, 0.2, 0.2, 0.02);
                            serverLevel.playSound(null, living.getX(), living.getY(), living.getZ(),
                                    SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, 0.5f, 1.0f);
                        }
                        living.level().addFreshEntity(newLiving);
                    }
                }
            }
        }
    }

    // ==================================================
    // 6. Chicken egg laying (unchanged)
    // ==================================================
    @SubscribeEvent
    public void onChickenTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (event.getEntity() instanceof Chicken chicken) {
            int stackSize = getStackSize(chicken);
            if (stackSize > 1) {
                int extraChickens = stackSize - 1;
                for (int i = 0; i < extraChickens; i++) {
                    if (chicken.getRandom().nextInt(9000) == 0) {
                        chicken.playSound(SoundEvents.CHICKEN_EGG, 1.0F,
                                (chicken.getRandom().nextFloat() - chicken.getRandom().nextFloat()) * 0.2F + 1.0F);
                        chicken.spawnAtLocation(Items.EGG);
                    }
                }
            }
        }
    }

    // ==================================================
    // UTILITY METHODS
    // ==================================================
    private void processItemStacking(ItemEntity currentItem, ServerLevel level) {
        if (!currentItem.isAlive()) return;
        ItemStack stack = currentItem.getItem();
        int currentCount = stack.getCount();
        CompoundTag data = currentItem.getPersistentData();

        int lastCount = data.contains("LastItemCount") ? data.getInt("LastItemCount") : -1;
        if (currentCount != lastCount) {
            updateItemName(currentItem, currentCount);
            data.putInt("LastItemCount", currentCount);
        }

        double radius = ModConfig.ITEM_RADIUS.get();
        List<ItemEntity> neighbors = level.getEntitiesOfClass(
                ItemEntity.class,
                currentItem.getBoundingBox().inflate(radius),
                e -> e != currentItem && e.isAlive()
        );

        for (ItemEntity neighbor : neighbors) {
            ItemStack neighborStack = neighbor.getItem();
            if (ItemStack.isSameItemSameTags(stack, neighborStack)) {
                int totalCount = stack.getCount() + neighborStack.getCount();
                stack.setCount(totalCount);
                neighbor.discard();

                updateItemName(currentItem, totalCount);
                data.putInt("LastItemCount", totalCount);

                level.sendParticles(ParticleTypes.INSTANT_EFFECT,
                        currentItem.getX(), currentItem.getY() + 0.5, currentItem.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);
                level.playSound(null, currentItem.getX(), currentItem.getY(), currentItem.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.AMBIENT, 0.2f, 2.0f);
                break;
            }
        }
    }

    private void updateItemName(ItemEntity itemEntity, int count) {
        if (!ModConfig.SHOW_MOB_COUNT.get()) return;
        if (count > 1) {
            var namePart = itemEntity.getItem().getHoverName().copy().withStyle(ChatFormatting.AQUA);
            var separatorPart = Component.literal(" x").withStyle(ChatFormatting.GRAY);
            var numberPart = Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.ITALIC);
            Component newName = namePart.append(separatorPart).append(numberPart);
            if (itemEntity.hasCustomName() && itemEntity.getCustomName().getString().equals(newName.getString())) return;
            itemEntity.setCustomName(newName);
            itemEntity.setCustomNameVisible(true);
        } else {
            itemEntity.setCustomName(null);
            itemEntity.setCustomNameVisible(false);
        }
    }

    private int getStackSize(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(STACK_NBT_KEY)) {
            data.putInt(STACK_NBT_KEY, 1);
        }
        return data.getInt(STACK_NBT_KEY);
    }

    private void setStackSize(LivingEntity entity, int size) {
        if (getStackSize(entity) == size && !entity.getPersistentData().contains(LOCK_NBT_KEY)) return; // allow refresh for lock

        entity.getPersistentData().putInt(STACK_NBT_KEY, size);

        if (ModConfig.SHOW_MOB_COUNT.get()) {
            Component finalName = buildEntityName(entity, size);
            if (size > 1 || isLocked(entity)) {
                if (!entity.hasCustomName() || !entity.getCustomName().getString().equals(finalName.getString())) {
                    entity.setCustomName(finalName);
                    entity.setCustomNameVisible(true);
                }
            } else {
                entity.setCustomName(null);
                entity.setCustomNameVisible(false);
            }
        }

        updateHealthAttribute(entity, size);
    }

    private Component buildEntityName(LivingEntity entity, int stackSize) {
        String mobName = entity.getType().getDescription().getString();
        var name = Component.empty();

        if (entity instanceof Sheep sheep) {
            String colorName = sheep.getColor().getName().substring(0, 1).toUpperCase() + sheep.getColor().getName().substring(1);
            name.append(Component.literal("(" + colorName + ") ").withStyle(ChatFormatting.GRAY));
        }
        if (entity.isBaby()) {
            name.append(Component.literal("Baby ").withStyle(ChatFormatting.WHITE));
        }

        name.append(Component.literal(mobName).withStyle(ChatFormatting.AQUA));

        if (stackSize > 1) {
            name.append(Component.literal(" x").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(stackSize)).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.ITALIC));
        }

        // Lock indicator
        if (isLocked(entity)) {
            name.append(Component.literal(" (*)").withStyle(ChatFormatting.RED));
        }

        return name;
    }

    private void updateHealthAttribute(LivingEntity entity, int stackSize) {
        AttributeInstance healthAttribute = entity.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttribute == null) return;

        healthAttribute.removeModifier(HEALTH_MODIFIER_UUID);
        double bonusPerStack = ModConfig.HP_PER_STACK.get();

        if (bonusPerStack > 0 && stackSize > 1) {
            double totalBonus = (stackSize - 1) * bonusPerStack;
            AttributeModifier modifier = new AttributeModifier(
                    HEALTH_MODIFIER_UUID,
                    "Stack Health Bonus",
                    totalBonus,
                    AttributeModifier.Operation.ADDITION
            );
            healthAttribute.addTransientModifier(modifier);
        }
    }

    private ItemLike getWoolByColor(DyeColor color) {
        return switch (color) {
            case WHITE -> Blocks.WHITE_WOOL;
            case ORANGE -> Blocks.ORANGE_WOOL;
            case MAGENTA -> Blocks.MAGENTA_WOOL;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case YELLOW -> Blocks.YELLOW_WOOL;
            case LIME -> Blocks.LIME_WOOL;
            case PINK -> Blocks.PINK_WOOL;
            case GRAY -> Blocks.GRAY_WOOL;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WOOL;
            case CYAN -> Blocks.CYAN_WOOL;
            case PURPLE -> Blocks.PURPLE_WOOL;
            case BLUE -> Blocks.BLUE_WOOL;
            case BROWN -> Blocks.BROWN_WOOL;
            case GREEN -> Blocks.GREEN_WOOL;
            case RED -> Blocks.RED_WOOL;
            case BLACK -> Blocks.BLACK_WOOL;
        };
    }

    private boolean isCompatible(LivingEntity a, LivingEntity b) {
        if (a instanceof Sheep sheepA && b instanceof Sheep sheepB) {
            return sheepA.getColor() == sheepB.getColor();
        }
        return true;
    }

    private boolean hasRecentSplit(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (data.contains(SPLIT_TIME_NBT_KEY)) {
            long splitTime = data.getLong(SPLIT_TIME_NBT_KEY);
            return entity.level().getGameTime() - splitTime < SPLIT_COOLDOWN_TICKS;
        }
        return false;
    }

    // --- Emerald lock helpers ---
    private boolean isLocked(LivingEntity entity) {
        return entity.getPersistentData().getBoolean(LOCK_NBT_KEY);
    }

    // --- Honey helpers ---
    private boolean isHoneyExpired(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (data.contains(HONEY_TIME_NBT)) {
            long time = data.getLong(HONEY_TIME_NBT);
            return entity.level().getGameTime() - time > HONEY_TIMEOUT_TICKS;
        }
        return false;
    }
}
