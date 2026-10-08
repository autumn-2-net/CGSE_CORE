package org.gtlcore.test;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.me.crafting.CraftingPlanSummary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanSummaryEntry;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.cgse.core.*;

import java.math.BigInteger;
import java.util.*;

/** Exercises the transformed AE summary and its real addon packet, never submits a job. */
public final class SeedSummaryProbe {
    public static void run(IGrid grid, IActionSource source, AEKey paper) {
        try {
            AEKey a=key(paper,"A"), c=key(paper,"C"), p=key(paper,"P");
            var recipe=new GraphRecipe<AEKey>("work","work",
                    List.of(new GraphRecipe.Slot<>(a,1),new GraphRecipe.Slot<>(c,2)),
                    Map.of(a,1L,c,1L,p,1L));
            var pattern=new AEProcessingPattern(AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(a,1),new GenericStack(c,2)},
                    new GenericStack[]{new GenericStack(p,1),new GenericStack(a,1),new GenericStack(c,1)})));
            var compiler=new GraphCompiler<>(List.of(recipe));
            for(int test=0;test<4;test++) {
                long amount=test==3?Long.MAX_VALUE:test==1?2:1;
                Map<AEKey,Long> stock=switch(test){case 1,2->Map.of(a,1L,c,2L);default->Map.of();};
                var plan=new GraphPlanner<>(compiler).plan(p,amount,stock,true,true,
                        new PlanningBudget(5000,2_000_000,()->false));
                check(plan.seeds().equals(Map.of(a,1L)),"Only A is retained: "+plan.seeds());
                check(plan.initialExact().get(c).equals(BigInteger.valueOf(amount).add(BigInteger.ONE)),"Wrong net consumable quantity");
                var result=switch(test){case 1->GraphPlan.Result.MISSING_INPUT;case 2->GraphPlan.Result.FEASIBLE;default->GraphPlan.Result.MISSING_SEED;};
                check(plan.result()==result,"Unexpected status "+plan.result());
                MEStorage inventory=new MEStorage(){
                    public net.minecraft.network.chat.Component getDescription(){return paper.getDisplayName();}
                    public void getAvailableStacks(KeyCounter out){stock.forEach(out::add);}
                    public long extract(AEKey key,long count,Actionable mode,IActionSource who){
                        if(mode==Actionable.MODULATE)throw new AssertionError("Read-only fixture extraction");
                        return Math.min(stock.getOrDefault(key,0L),count);
                    }
                };
                IStorageProvider provider=mounts->mounts.mount(inventory);
                grid.getStorageService().addGlobalStorageProvider(provider);
                try {
                    var actual=new KeyCounter();grid.getStorageService().getInventory().getAvailableStacks(actual);
                    check(actual.get(a)==stock.getOrDefault(a,0L)&&actual.get(c)==stock.getOrDefault(c,0L),"Fixture storage not mounted");
                    var wrapped=new AeGraphPlan(plan,Map.of("work",pattern),Set.of(),stock);
                    var summary=CraftingPlanSummary.fromJob(grid,source,wrapped);
                    inspect(summary,a,c,test==0||test==3,test!=2);
                    var copy=GraphPacketProbe.roundTrip(summary);
                    inspect(copy,a,c,test==0||test==3,test!=2);
                } finally {grid.getStorageService().removeGlobalStorageProvider(provider);}
                System.out.println("[Seed Summary] PASS case="+test+" amount="+amount+" result="+result+
                        " required_C="+plan.initialExact().get(c)+" seed_A=1 seed_C=0 packet=OK");
            }
            System.out.println("[Seed Summary] DONE");
        } catch(Throwable e){System.out.println("[Seed Summary] FAIL "+e);e.printStackTrace();}
    }
    private static AEKey key(AEKey paper,String name){
        var tag=new CompoundTag();tag.m_128359_("seed_summary_review_20260925",name);
        return AEItemKey.of((Item)paper.getPrimaryKey(),tag);
    }
    private static void inspect(CraftingPlanSummary summary,AEKey a,AEKey c,boolean seedMissing,boolean consumableMissing){
        int found=0;
        for(var entry:summary.getEntries()) {
            var extra=(ICraftingPlanSummaryEntry)entry;
            if(entry.getWhat().equals(a)) {
                found++;
                check(extra.gtlcore$getGraphSeed()==1,"A reserve lost");
                check(extra.gtlcore$isMissingGraphSeed()==seedMissing,"A startup warning lost or spurious");
                check((entry.getMissingAmount()>0)==seedMissing,"Wrong A missing quantity");
            } else if(entry.getWhat().equals(c)) {
                found++;
                check(extra.gtlcore$getGraphSeed()==0,"Net-consumed C labelled as retained");
                check(!extra.gtlcore$isMissingGraphSeed(),"Net-consumed C labelled as seed");
                check((entry.getMissingAmount()>0)==consumableMissing,"C missing amount hidden");
            }
        }
        check(found==2,"A or C absent from summary");
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
