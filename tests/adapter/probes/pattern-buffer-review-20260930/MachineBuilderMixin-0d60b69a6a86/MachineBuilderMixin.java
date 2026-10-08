/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder
 *  net.minecraft.network.chat.Component
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.Unique
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.ModifyArg
 */
package com.gtladd.gtladditions.mixin.gtceu.api.machine;

import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import java.util.Arrays;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={MachineBuilder.class}, priority=900)
public class MachineBuilderMixin {
    @Unique
    private static final Component gTLAdditions$tooltips = Component.m_237115_((String)"gtceu.universal.enabled").m_130946_("(").m_7220_((Component)Component.m_237115_((String)"gui.gtladditions.modify").m_130946_(")"));
    @Unique
    private static final String[] gTLAdditions$keyWords = new String[]{"auto_configuration_maintenance_hatch", "cleaning_configuration_maintenance_hatch", "sterile_configuration_cleaning_maintenance_hatch", "law_configuration_cleaning_maintenance_hatch", "gravity_configuration_hatch", "cleaning_gravity_configuration_maintenance_hatch", "sterile_cleaning_gravity_configuration_maintenance_hatch", "law_cleaning_gravity_configuration_maintenance_hatch"};
    @Shadow(remap=false)
    @Final
    protected String name;

    @ModifyArg(method={"tooltips"}, at=@At(value="INVOKE", target="Ljava/util/Arrays;stream([Ljava/lang/Object;)Ljava/util/stream/Stream;"), remap=false)
    public <T> T[] tooltips(T[] array) {
        if (Arrays.stream(gTLAdditions$keyWords).anyMatch(this.name::contains)) {
            array[0] = gTLAdditions$tooltips;
        }
        return array;
    }
}
