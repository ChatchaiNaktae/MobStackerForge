package com.devdroggy.mobstacker;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent; // ADDED: Import for XP drops
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class ModEvents {

    public static int CHECK_INTERVAL = 10;
    private static final String STACK_NBT_KEY = "StackAmount";
    private static final UUID HEALTH_MODIFIER_UUID = UUID.fromString("d05b8a0a-e555-4e0f-bf3a-f10e1346210f");

    // ==================================================
    // 1. ระบบรวมร่าง MOB
    // ==================================================
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();

        if (entity.level().isClientSide || CHECK_INTERVAL <= 0 || entity.tickCount % CHECK_INTERVAL != 0) return;
        if (!(entity instanceof Monster) && !(entity instanceof Animal)) return;
        if (!entity.isAlive()) return;

        double radius = ModConfig.MOB_RADIUS.get();
        int minThreshold = ModConfig.MIN_STACK_THRESHOLD.get();

        List<LivingEntity> neighbors = entity.level().getEntitiesOfClass(
                LivingEntity.class,
                entity.getBoundingBox().inflate(radius),
                e -> e != entity && e.getType() == entity.getType() && e.isAlive()
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
    // 2. ระบบดรอปของคูณ (Loot Multiplier) - FIXED & OPTIMIZED
    // ==================================================
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        int stackSize = getStackSize(entity);

        if (stackSize > 1) {
            Collection<ItemEntity> drops = event.getDrops();
            List<ItemEntity> originalDrops = List.copyOf(drops);

            // 1. Clear the original drops to rebuild them properly
            drops.clear();

            for (ItemEntity originalItem : originalDrops) {
                ItemStack baseStack = originalItem.getItem();

                // 2. Calculate total items (Original count * Stack size)
                // Exactly calculated to prevent missing items
                int totalItems = baseStack.getCount() * stackSize;

                // 3. Loop to create new ItemEntities, capped at Max Stack Size (usually 64)
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

                    // 4. Important: Copy movement (physics) and set pickup delay to prevent glitches
                    newItem.setDeltaMovement(originalItem.getDeltaMovement());
                    newItem.setDefaultPickUpDelay();

                    drops.add(newItem);
                }
            }
        }
    }

    // ==================================================
    // โบนัส: ระบบดรอป XP คูณตามจำนวน Stack
    // ==================================================
    @SubscribeEvent
    public void onExperienceDrop(LivingExperienceDropEvent event) {
        LivingEntity entity = event.getEntity();
        int stackSize = getStackSize(entity);

        if (stackSize > 1) {
            int originalXp = event.getDroppedExperience();
            // Multiply dropped XP by the stack size
            event.setDroppedExperience(originalXp * stackSize);
        }
    }

    // ==================================================
    // 3. ระบบรวม ITEM (Stack เกิน 64)
    // ==================================================
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        // --- แทรกบรรทัดนี้เข้าไปบนสุด ---
        // ถ้า Config ถูกตั้งเป็น false ให้หยุดการทำงาน (return) ออกไปเลยทันที
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

    private void processItemStacking(ItemEntity currentItem, ServerLevel level) {
        if (!currentItem.isAlive()) return;

        ItemStack stack = currentItem.getItem();
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
                updateItemName(currentItem, stack.getCount());

                level.sendParticles(ParticleTypes.INSTANT_EFFECT,
                        currentItem.getX(), currentItem.getY() + 0.5, currentItem.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);

                level.playSound(null, currentItem.getX(), currentItem.getY(), currentItem.getZ(),
                        SoundEvents.ITEM_PICKUP, SoundSource.AMBIENT, 0.2f, 2.0f);
                break;
            }
        }
    }

    // ==================================================
    // UTILITY METHODS
    // ==================================================
    private void updateItemName(ItemEntity itemEntity, int count) {
        if (!ModConfig.SHOW_MOB_COUNT.get()) return;

        if (count > 1) {
            var namePart = itemEntity.getItem().getHoverName().copy().withStyle(ChatFormatting.AQUA);
            var separatorPart = Component.literal(" x").withStyle(ChatFormatting.GRAY);
            var numberPart = Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.ITALIC);

            Component newName = namePart.append(separatorPart).append(numberPart);

            if (itemEntity.hasCustomName() && itemEntity.getCustomName().getString().equals(newName.getString())) {
                return;
            }
            itemEntity.setCustomName(newName);
            itemEntity.setCustomNameVisible(true);
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
        if (getStackSize(entity) == size) return;

        entity.getPersistentData().putInt(STACK_NBT_KEY, size);

        if (ModConfig.SHOW_MOB_COUNT.get() && size > 1) {
            String rawName = entity.getType().getDescription().getString();
            if (entity.isBaby()) rawName = "Baby " + rawName;

            var namePart = Component.literal(rawName).withStyle(ChatFormatting.AQUA);
            var separatorPart = Component.literal(" x").withStyle(ChatFormatting.GRAY);
            var numberPart = Component.literal(String.valueOf(size)).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.ITALIC);

            Component newName = namePart.append(separatorPart).append(numberPart);

            if (!entity.hasCustomName() || !entity.getCustomName().getString().equals(newName.getString())) {
                entity.setCustomName(newName);
                entity.setCustomNameVisible(true);
            }
        }

        updateHealthAttribute(entity, size);
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
            entity.setHealth(entity.getMaxHealth());
        }
    }
}