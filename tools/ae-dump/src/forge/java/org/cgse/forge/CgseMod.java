// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.forge;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.gtlcore.aedump.AeDump;

/** The optional engine and its AE diagnostics share one player-installed mod. */
@Mod("cgse")
public final class CgseMod {
    public CgseMod() {
        if (ModList.get().isLoaded("gtlcore") && ModList.get().isLoaded("ae2")) new AeDump();
    }
}
