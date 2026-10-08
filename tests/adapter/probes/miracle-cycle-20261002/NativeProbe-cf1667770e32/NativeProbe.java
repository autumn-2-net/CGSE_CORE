package local.cgseprobe;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.google.gson.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.common.Mod;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Local ignored diagnostic helper. No job submission or world mutation. */
@Mod("localmiracleprobe")
public final class NativeProbe {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final PlanningScheduler WORKER=new PlanningScheduler(1,2,4096,2_000_000L);
    private static final ThreadLocal<Map<AEKey,String>> KEYS=ThreadLocal.withInitial(HashMap::new);
    public static String key(AEKey key) { return KEYS.get().computeIfAbsent(key,k->k.toTagGeneric().toString()); }
    private static JsonObject quantities(Map<AEKey,? extends Number> values) {
        var out=new JsonObject();values.forEach((k,v)->out.addProperty(key(k),v.toString()));return out;
    }
    private static JsonObject recipe(GraphRecipe<AEKey> r) {
        var out=new JsonObject();out.addProperty("id",r.id());out.addProperty("binding",r.binding());
        var slots=new JsonArray();
        for(var s:r.slots()) {var v=new JsonObject();v.addProperty("key",key(s.key()));v.addProperty("amount",Long.toString(s.amount()));v.addProperty("input_slot",s.inputSlot());v.addProperty("configuration",s.configuration());v.addProperty("reusable",s.reusable());slots.add(v);}
        out.add("slots",slots);out.add("outputs",quantities(r.outputs()));return out;
    }
    public static CompletableFuture<String> capture(Level level,IGrid grid,IActionSource source,AEKey target) {
        int idleParallel=1;
        for(var cpu:grid.getCraftingService().getCpus()) if(!cpu.isBusy()) idleParallel=(int)Math.max(idleParallel,Math.min(4096L,(long)cpu.getCoProcessors()+1));
        final int parallel=idleParallel;
        final int maxExtra=ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies;
        var budget=new PlanningBudget(0,2_000_000_000L,1024L*1024*1024,()->false,System::nanoTime);
        var snapshots=new GtlPatternCatalog();var capture=snapshots.begin(grid,(CraftingService)grid.getCraftingService(),level,source,target,budget);
        var future=new CompletableFuture<GtlPatternCatalog.Snapshot>();
        GraphSnapshots.enqueue(capture,budget,future,timing->{});
        return future.thenCompose(snapshot->WORKER.submit(snapshot.structure().catalog().build(budget),budget).thenApply(prepared->{
            KEYS.set(new HashMap<>());
            var out=new JsonObject();out.addProperty("target",key(target));out.addProperty("complete",true);
            out.addProperty("force_craft",true);out.addProperty("preserve_seeds",true);
            out.add("stock",quantities(snapshot.stock()));var ext=new JsonArray();snapshot.emitable().forEach(k->ext.add(key(k)));out.add("external",ext);
            var recipes=new JsonArray();var keys=new LinkedHashSet<AEKey>();
            for(var r:prepared.compiler().catalog()) {recipes.add(recipe(r));keys.addAll(r.executionOutputs().keySet());}
            out.add("recipes",recipes);var order=new JsonObject();
            for(var k:keys) {var ids=new JsonArray();prepared.compiler().producers(k).forEach(r->ids.add(r.id()));order.add(key(k),ids);}
            out.add("producers",order);
            var compiled=prepared.compiler().compile(target,Map.of(),Set.of(),budget);
            var selected=new JsonObject();compiled.selected().forEach((k,r)->selected.addProperty(key(k),r.id()));
            out.add("compiled_selected",selected);out.addProperty("compiled_recipes",compiled.recipes().size());
            out.addProperty("parallelism",parallel);out.addProperty("max_extra_copies",maxExtra);
            out.addProperty("capture_work",Long.toString(budget.nodes()));out.addProperty("capture_peak_bytes",Long.toString(budget.peakBytes()));return JSON.toJson(out);
        }));
    }
    public static String describe(ICraftingPlan plan,GraphPlanningRequest request) {
        KEYS.set(new HashMap<>());
        var out=new JsonObject();out.addProperty("ok",true);out.addProperty("simulation",plan.simulation());out.addProperty("bytes",Long.toString(plan.bytes()));
        if(plan instanceof AeGraphPlan ae) {
            var g=ae.graph();out.addProperty("result",g.result().toString());out.addProperty("fallback",ae.fallback());out.addProperty("amount",Long.toString(g.amount()));
            out.add("missing",quantities(g.missingExact()));out.add("initial",quantities(g.initialExact()));out.add("seeds",quantities(g.seeds()));
            var used=new JsonArray();g.patternTimesExact().forEach((id,count)->{var r=recipe(g.recipes().get(id));r.addProperty("runs",count.toString());used.add(r);});out.add("selected",used);
        }
        out.addProperty("work",Long.toString(request.budget().nodes()));out.addProperty("peak_bytes",Long.toString(request.budget().peakBytes()));
        out.addProperty("diagnostics",request.budget().diagnostics());out.addProperty("failure_detail",request.budget().failureDetail());
        return JSON.toJson(out);
    }
}
