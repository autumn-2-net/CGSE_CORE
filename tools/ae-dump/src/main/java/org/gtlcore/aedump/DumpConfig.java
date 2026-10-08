// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import net.minecraftforge.common.ForgeConfigSpec;

public final class DumpConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue enabled, autoErrors, autoMissing, nodeNbt, machinePreviews;
    public static final ForgeConfigSpec.IntValue captureMiB, retainedMiB, recentCount, minutes, maxAuto, archiveMiB, diskMiB;
    static {
        var b = new ForgeConfigSpec.Builder();
        enabled = b.comment("Capture entire networks before CGSE/MaxFast previews. Diagnostic overhead is additional to solver work.").define("enabled", true);
        autoErrors = b.comment("Automatically save failures, exhausted budgets and fallback use.").define("autoErrors", true);
        autoMissing = b.comment("Ordinary missing-material previews can be exported with /ae dump. Disable automatic missing reports to avoid requester spam.").define("autoMissing", false);
        machinePreviews = b.comment("Also copy full networks before every automated requester/replan. Normally these capture the full network on failure/manual export, while retaining the actual CGSE inputs. Player previews always capture before solving.").define("captureEveryMachinePreview", false);
        nodeNbt = b.comment("Include connected grid block/part NBT for provider settings, cells and machine state.").define("includeNodeNbt", true);
        captureMiB = b.comment("Approximate capture memory cap; any incomplete section is explicitly reported.").defineInRange("captureMiB", 128, 8, 1024);
        retainedMiB = b.defineInRange("retainedMiB", 256, 16, 2048);
        recentCount = b.defineInRange("recentRequests", 12, 1, 128);
        minutes = b.defineInRange("retainMinutes", 30, 1, 1440);
        maxAuto = b.defineInRange("automaticArchivesPerSession", 16, 0, 1000);
        archiveMiB = b.defineInRange("maxUncompressedArchiveMiB", 256, 8, 2048);
        diskMiB = b.defineInRange("maxArchiveDirectoryMiB", 2048, 64, 16384);
        SPEC = b.build();
    }
    private DumpConfig() {}
}
