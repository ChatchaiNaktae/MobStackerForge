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
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class ModEvents {

    // ความถี่ในการเช็ค (Tick)
    public static int CHECK_INTERVAL = 10;

    // Key สำหรับเก็บข้อมูลใน NBT
    private static final String STACK_NBT_KEY = "StackAmount";

    // UUID คงที่เพื่อให้ระบบจำได้ว่าเป็นค่าเลือดโบนัสจาก Mod เรา (ห้ามเปลี่ยนเลขนี้)
    private static final UUID HEALTH_MODIFIER_UUID = UUID.fromString("d05b8a0a-e555-4e0f-bf3a-f10e1346210f");

    // ==================================================
    // 1. ระบบรวมร่าง MOB
    // ==================================================
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();

        // เช็คเงื่อนไขพื้นฐาน: ฝั่ง Server, ถึงรอบเช็ค, เป็น Monster หรือ Animal, และยังมีชีวิต
        if (entity.level().isClientSide || CHECK_INTERVAL <= 0 || entity.tickCount % CHECK_INTERVAL != 0) return;
        if (!(entity instanceof Monster) && !(entity instanceof Animal)) return;
        if (!entity.isAlive()) return;

        double radius = ModConfig.MOB_RADIUS.get();
        int minThreshold = ModConfig.MIN_STACK_THRESHOLD.get(); // ค่าจาก Config

        // ค้นหาเพื่อนในระยะ
        List<LivingEntity> neighbors = entity.level().getEntitiesOfClass(
                LivingEntity.class,
                entity.getBoundingBox().inflate(radius),
                e -> e != entity && e.getType() == entity.getType() && e.isAlive()
        );

        // --- ฟีเจอร์: เช็คจำนวนขั้นต่ำ ---
        // ถ้าจำนวนเพื่อน + ตัวเรา (1) รวมกันแล้วยังไม่ถึงค่าขั้นต่ำ ให้หยุดทำงานทันที
        if (neighbors.size() + 1 < minThreshold) return;

        for (LivingEntity neighbor : neighbors) {
            // กฎ: ต้องวัยเดียวกัน (เด็กคู่เด็ก / ผู้ใหญ่คู่ผู้ใหญ่)
            if (entity.isBaby() != neighbor.isBaby()) continue;

            int myStack = getStackSize(entity);
            int otherStack = getStackSize(neighbor);

            // รวมร่าง: เอาจำนวนของเพื่อนมาบวกใส่ตัวเรา
            setStackSize(entity, myStack + otherStack);

            // Effect: ระเบิดควัน + เสียง
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                        entity.getX(), entity.getY() + 0.5, entity.getZ(),
                        1, 0.0, 0.0, 0.0, 0.0);

                serverLevel.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }

            // ลบเพื่อนทิ้ง
            neighbor.discard();
            break; // ทำทีละคู่ เพื่อประสิทธิภาพ
        }
    }

    // ==================================================
    // 2. ระบบดรอปของคูณ (Loot Multiplier)
    // ==================================================
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        int stackSize = getStackSize(entity);

        if (stackSize > 1) {
            Collection<ItemEntity> drops = event.getDrops();
            List<ItemEntity> originalDrops = List.copyOf(drops);

            // วนลูปเพิ่มของตามจำนวน Stack ที่เหลือ (ลบ 1 ตัวต้นฉบับออก)
            for (int i = 0; i < stackSize - 1; i++) {
                for (ItemEntity item : originalDrops) {
                    ItemEntity newItem = new ItemEntity(
                            entity.level(),
                            item.getX(), item.getY(), item.getZ(),
                            item.getItem().copy()
                    );
                    drops.add(newItem);
                }
            }
        }
    }

    // ==================================================
    // 3. ระบบรวม ITEM (Stack เกิน 64)
    // ==================================================
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
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

            // เช็คว่าเป็นของชนิดเดียวกันและ Tag เหมือนกัน
            if (ItemStack.isSameItemSameTags(stack, neighborStack)) {

                // รวมจำนวน (ทะลุ 64 ได้)
                int totalCount = stack.getCount() + neighborStack.getCount();
                stack.setCount(totalCount);

                // ลบไอเทมเพื่อน
                neighbor.discard();

                // อัปเดตชื่อแสดงจำนวน
                updateItemName(currentItem, stack.getCount());

                // Effect วิ้งๆ + เสียงเก็บของ
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
    // UTILITY METHODS (ฟังก์ชันช่วย)
    // ==================================================

    // จัดการชื่อ Item
    private void updateItemName(ItemEntity itemEntity, int count) {
        if (!ModConfig.SHOW_MOB_COUNT.get()) return;

        if (count > 1) {
            var namePart = itemEntity.getItem().getHoverName().copy().withStyle(ChatFormatting.AQUA);
            var separatorPart = Component.literal(" x").withStyle(ChatFormatting.GRAY);
            var numberPart = Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.ITALIC);

            Component newName = namePart.append(separatorPart).append(numberPart);

            // เช็คว่าชื่อซ้ำไหมก่อนตั้งใหม่ (ลด Packet)
            if (itemEntity.hasCustomName() && itemEntity.getCustomName().getString().equals(newName.getString())) {
                return;
            }
            itemEntity.setCustomName(newName);
            itemEntity.setCustomNameVisible(true);
        }
    }

    // อ่านค่า Stack จาก NBT
    private int getStackSize(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(STACK_NBT_KEY)) {
            data.putInt(STACK_NBT_KEY, 1);
        }
        return data.getInt(STACK_NBT_KEY);
    }

    // ตั้งค่า Stack ลง NBT + จัดการชื่อ + จัดการเลือด
    private void setStackSize(LivingEntity entity, int size) {
        if (getStackSize(entity) == size) return;

        entity.getPersistentData().putInt(STACK_NBT_KEY, size);

        // --- 1. อัปเดตชื่อ ---
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

        // --- 2. อัปเดต Max Health (ฟีเจอร์ใหม่) ---
        updateHealthAttribute(entity, size);
    }

    // ฟังก์ชันคำนวณและเพิ่มเลือดด้วย AttributeModifier
    private void updateHealthAttribute(LivingEntity entity, int stackSize) {
        AttributeInstance healthAttribute = entity.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttribute == null) return;

        // ลบค่าโบนัสเก่าออกก่อนเสมอ (ถ้ามี) เพื่อไม่ให้บวกทับซ้อนกันเรื่อยๆ
        healthAttribute.removeModifier(HEALTH_MODIFIER_UUID);

        double bonusPerStack = ModConfig.HP_PER_STACK.get();

        // ถ้ามีการตั้งค่าให้เพิ่มเลือด และ Stack มากกว่า 1 (คือมีการรวมร่างเกิดขึ้น)
        if (bonusPerStack > 0 && stackSize > 1) {
            double totalBonus = (stackSize - 1) * bonusPerStack;

            // สร้าง Modifier ใหม่
            AttributeModifier modifier = new AttributeModifier(
                    HEALTH_MODIFIER_UUID,
                    "Stack Health Bonus",
                    totalBonus,
                    AttributeModifier.Operation.ADDITION // บวกเพิ่มจากค่าฐาน (Base Value)
            );

            // ใส่ Modifier เข้าไปที่ Attribute เลือด
            healthAttribute.addTransientModifier(modifier);

            // ฮีลเลือดให้เต็มตาม Max HP ใหม่
            entity.setHealth(entity.getMaxHealth());
        }
    }
}