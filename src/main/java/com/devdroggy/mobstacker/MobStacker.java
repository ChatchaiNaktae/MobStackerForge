package com.devdroggy.mobstacker;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext; // เพิ่ม import
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig; // เพิ่ม import
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MobStacker.MOD_ID)
public class MobStacker {
    public static final String MOD_ID = "mobstacker";

    public MobStacker() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // --- เพิ่มบรรทัดนี้: ลงทะเบียน Config ---
        // เพื่อให้เกมสร้างไฟล์ mobstacker-common.toml และอ่านค่าที่เราตั้งไว้
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.devdroggy.mobstacker.ModConfig.SPEC);

        // ลงทะเบียน Event Bus
        MinecraftForge.EVENT_BUS.register(new ModEvents());
    }
}