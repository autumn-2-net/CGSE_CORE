// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.gtlcore.aedump;

import net.minecraftforge.fml.common.Mod;

/** Retained for developers building the legacy diagnostic-only distribution. */
@Mod("aedump")
public final class AeDumpMod {
    public AeDumpMod() {
        new AeDump();
    }
}
