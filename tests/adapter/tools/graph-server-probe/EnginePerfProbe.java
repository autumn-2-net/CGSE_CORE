package org.gtlcore.test;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.*;
import appeng.api.stacks.*;
import appeng.crafting.CraftingCalculation;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;
import java.math.BigInteger;

/** Local-only serial benchmark: actual Forge/AE host, never dispatches production. */
public final class EnginePerfProbe implements ICraftingProvider {
    private static EnginePerfProbe active;
    private static final ExecutorService OLD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Engine-Perf-MAX-FAST"); t.setDaemon(true); return t;
    });
    private final PlanningScheduler fallbackScheduler = new PlanningScheduler(4, 16, 128, 2_000_000);
    private final IGrid grid;
    private final Level level;
    private final IActionSource source;
    private final AEKey paper;
    private final List<Fixture> cases = new ArrayList<>();
    private Fixture fixture;
    private int caseIndex, sampleIndex, engineIndex;
    private CompletableFuture<Sample> pending;
    private NetworkCraftingProviders providers;
    private IGridNode node;
    private IStorageProvider store;
    private GtlPatternCatalog fallbackCatalog = new GtlPatternCatalog();
    private final String nonce = UUID.randomUUID().toString();
    private final Map<String,Object> saved = new HashMap<>();
    private final List<Sample> records = new ArrayList<>();
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    private record Recipe(String id, Map<String,Long> inputs, Map<String,Long> outputs) {}
    private record Fixture(String name, String target, long amount, Map<String,Long> stock,
                           List<Recipe> recipes, String truth, int warmups, int measured, boolean byproducts) {}
    private final Map<String,AEKey> keys = new LinkedHashMap<>();
    private final Map<AEKey,Long> stock = new LinkedHashMap<>();
    private final List<IPatternDetails> patterns = new ArrayList<>();
    private final Map<IPatternDetails,Recipe> originals = new IdentityHashMap<>();
    private record Sample(String caseName, String engine, int sample, String phase, long amount,
                          long wallNanos, long setupNanos, long runNanos, long snapshotNanos,
                          long activeNanos, long work, long maxFastCalls, ICraftingPlan plan, String failure) {}

    public static void start(IGrid grid, Level level, IActionSource source, AEKey paper) throws Exception {
        if (active != null) throw new IllegalStateException("Benchmark active");
        active = new EnginePerfProbe(grid, level, source, paper);
        System.out.println("[Engine Perf] START head=40e59a2f default_work=20000000 fallback_work=131072 fallback_ms=250 serial=true");
    }
    public static void tick() {
        if (active == null) return;
        try {
            if (active.advance()) { active.close(); active=null; System.out.println("[Engine Perf] COMPLETE"); }
        } catch (Throwable t) {
            System.out.println("[Engine Perf] FAIL " + t); t.printStackTrace(); active.close(); active=null;
        }
    }
    private EnginePerfProbe(IGrid grid, Level level, IActionSource source, AEKey paper) throws Exception {
        this.grid=grid; this.level=level; this.source=source; this.paper=paper;
        var c=ConfigHolder.INSTANCE;
        for (String name:List.of("ae2CraftingEngine","ae2CalculationMode","ae2GraphSeedPolicy","ae2GraphByteCostMode",
                "ae2GraphPlannerMaxSteps","ae2GraphPlannerTimeoutMs","ae2GraphFallback","ae2GraphDiscoverByproducts",
                "ae2GraphPlannerMemoryMiB","ae2GraphDiagnosticLogging")) {
            saved.put(name,c.getClass().getField(name).get(c));
        }
        c.ae2CraftingEngine=AECraftingEngine.GRAPH; c.ae2CalculationMode=AE2CalculationMode.MAX_FAST;
        c.ae2GraphSeedPolicy=AEGraphSeedPolicy.PRESERVE; c.ae2GraphByteCostMode=CraftingCostModel.Mode.LEGACY;
        c.ae2GraphPlannerMaxSteps=20_000_000; c.ae2GraphPlannerTimeoutMs=0; c.ae2GraphFallback=true;
        c.ae2GraphDiscoverByproducts=false; c.ae2GraphPlannerMemoryMiB=128; c.ae2GraphDiagnosticLogging=false;
        System.out.println("[Engine Perf] JVM="+System.getProperty("java.version")+" workers="+c.ae2GraphPlannerThreads+
                " heap="+Runtime.getRuntime().maxMemory()+" cpu="+Runtime.getRuntime().availableProcessors());
        chain(32,1,false); chain(256,1,false); chain(1024,1,false);
        chain(256,1_000_000,false); chain(1024,1_000_000_000L,false);
        chain(32,Long.MAX_VALUE,false); chain(256,1_000_000,true);
        shared(16,16,1_000_000_000L);
        cases.add(new Fixture("batch_3to2", "goal", 1_000_000, Map.of("raw",3_000_000L), List.of(
                r("a",m("raw",3),m("mid",2)),r("b",m("mid",3),m("goal",2))),"SAT",12,31,false));
        cases.add(new Fixture("alternative_sources", "goal", 1_000_000, Map.of("rawA",500_000L,"rawB",500_000L), List.of(
                r("a",m("rawA",1),m("goal",1)),r("b",m("rawB",1),m("goal",1))),"SAT",12,31,false));
        cases.add(new Fixture("shared_coproduct", "goal", 1_000_000, Map.of("raw",1_000_000L), List.of(
                r("a",m("raw",1),m("A",1,"B",1)),r("b",m("A",1,"B",1),m("goal",1))),"SAT",12,31,false));
        cases.add(new Fixture("catalyst_1m", "goal", 1_000_000, Map.of("raw",1_000_000L,"cat",1L), List.of(
                r("a",m("cat",1,"raw",1),m("mid",1)),r("b",m("mid",1),m("goal",1,"cat",1))),"SAT",12,31,false));
        cases.add(new Fixture("catalyst_missing_seed", "goal", 1_000_000, Map.of("raw",1_000_000L), List.of(
                r("a",m("cat",1,"raw",1),m("mid",1)),r("b",m("mid",1),m("goal",1,"cat",1))),"UNSAT",12,31,false));
        cases.add(new Fixture("lossy_conversion", "diamond", 6, Map.of("diamond",1L,"dust",8L), List.of(
                r("grind",m("diamond",1),m("dust",1)),r("restore",m("dust",4),m("diamond",3))),"SAT",12,31,false));
        var hard=readHardCase();
        cases.add(hard);
        cases.add(new Fixture("joint_accounts_19_limit10k",hard.target,hard.amount,hard.stock,hard.recipes,hard.truth,6,11,true));
        var missing=cases.get(6);
        cases.add(new Fixture("missing_chain_profile",missing.target,missing.amount,missing.stock,missing.recipes,missing.truth,0,1,false));
        var large=cases.get(2);
        cases.add(new Fixture("large_chain_profile",large.target,large.amount,large.stock,large.recipes,large.truth,0,1,false));
    }
    private static LinkedHashMap<String,Long> m(Object... pairs) {
        var result=new LinkedHashMap<String,Long>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],((Number)pairs[i+1]).longValue());
        return result;
    }
    private static Recipe r(String id,Map<String,Long> input,Map<String,Long> output){return new Recipe(id,input,output);}
    private void chain(int depth,long amount,boolean missing) {
        List<Recipe> recipes=new ArrayList<>();
        for(int i=1;i<=depth;i++) recipes.add(r("r"+i,m("k"+(i-1),1),m("k"+i,1)));
        cases.add(new Fixture("chain"+depth+"_"+amount+(missing?"_missing":""),"k"+depth,amount,
                Map.of("k0",missing?amount/2:amount),recipes,missing?"UNSAT":"SAT",12,31,false));
    }
    private void shared(int depth,int width,long amount) {
        List<Recipe> recipes=new ArrayList<>();
        for(int layer=1;layer<=depth;layer++)for(int b=0;b<width;b++){
            var inputs=layer==1?m("raw",2):m("k"+(layer-1)+"_"+b,1,"k"+(layer-1)+"_"+((b+1)%width),1);
            recipes.add(r("r"+layer+"_"+b,inputs,m("k"+layer+"_"+b,2)));
        }
        cases.add(new Fixture("shared"+(depth*width)+"_1b","k"+depth+"_0",amount,
                Map.of("raw",amount+10000),recipes,"SAT",12,31,false));
    }
    private Fixture readHardCase() throws Exception {
        var j=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("fallback-review.json"))).getAsJsonObject();
        List<Recipe> recipes=new ArrayList<>();
        for(var value:j.getAsJsonArray("recipes")){
            var rr=value.getAsJsonObject(); recipes.add(r(rr.get("id").getAsString(),map(rr.getAsJsonObject("inputs")),map(rr.getAsJsonObject("outputs"))));
        }
        return new Fixture("joint_accounts_19",j.get("target").getAsString(),j.get("amount").getAsLong(),map(j.getAsJsonObject("stock")),recipes,"SAT",2,5,true);
    }
    private static Map<String,Long> map(com.google.gson.JsonObject j){var m=new LinkedHashMap<String,Long>();j.entrySet().forEach(e->m.put(e.getKey(),e.getValue().getAsLong()));return m;}
    private AEKey key(String name) {
        return keys.computeIfAbsent(name,n->{var tag=new CompoundTag();tag.m_128359_("engine_perf",nonce+":"+caseIndex+":"+n);
            return AEItemKey.of((net.minecraft.world.item.Item)paper.getPrimaryKey(),tag);});
    }
    private void install(Fixture f) throws Exception {
        remove(); fixture=f; keys.clear(); stock.clear(); patterns.clear(); originals.clear(); fallbackCatalog=new GtlPatternCatalog();
        ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=f.byproducts;
        ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps=f.name.endsWith("limit10k")?10_000:20_000_000;
        ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging=f.name.endsWith("_profile");
        f.stock.forEach((k,n)->stock.put(key(k),n));
        for(var recipe:f.recipes){
            GenericStack[] in=recipe.inputs.entrySet().stream().map(e->new GenericStack(key(e.getKey()),e.getValue())).toArray(GenericStack[]::new);
            GenericStack[] out=recipe.outputs.entrySet().stream().map(e->new GenericStack(key(e.getKey()),e.getValue())).toArray(GenericStack[]::new);
            IPatternDetails pattern=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(in,out),level);
            if(pattern==null)throw new AssertionError("Pattern decode failed"); patterns.add(pattern); originals.put(pattern,recipe);
        }
        var field=CraftingService.class.getDeclaredField("craftingProviders");field.setAccessible(true);providers=(NetworkCraftingProviders)field.get(grid.getCraftingService());
        node=(IGridNode)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IGridNode.class},(p,method,args)->switch(method.getName()){
            case "getService" -> args[0]==ICraftingProvider.class?this:null;
            case "getGrid" -> grid; case "hashCode" -> System.identityHashCode(p);case "equals" -> p==args[0];default -> null;
        });
        store=mounts->mounts.mount(new MEStorage(){
            public net.minecraft.network.chat.Component getDescription(){return paper.getDisplayName();}
            public void getAvailableStacks(KeyCounter out){stock.forEach(out::add);}
            public long extract(AEKey k,long n,Actionable mode,IActionSource src){if(mode!=Actionable.SIMULATE)throw new AssertionError("Benchmark mutated stock");return Math.min(n,stock.getOrDefault(k,0L));}
        });
        grid.getStorageService().addGlobalStorageProvider(store);providers.addProvider(node);
        System.out.println("[Engine Perf] CASE name="+f.name+" patterns="+patterns.size()+" amount="+f.amount+" truth="+f.truth+" byproducts="+f.byproducts);
    }
    private boolean advance() throws Exception {
        if(pending!=null){
            if(!pending.isDone())return false;
            Sample result=pending.get(); pending=null; report(result); records.add(result);
            if(++engineIndex==3){engineIndex=0;sampleIndex++;}
        }
        if(fixture==null || sampleIndex>=1+fixture.warmups+fixture.measured){
            if(caseIndex>=cases.size())return true;
            install(cases.get(caseIndex++));sampleIndex=engineIndex=0;
            return false; // Let the AE storage cache observe the provider installation.
        }
        int engine=(sampleIndex+engineIndex)%3;
        pending=engine==0?legacy():engine==1?graph():fallback();
        return false;
    }
    private String phase(){return sampleIndex==0?"cold":sampleIndex<=fixture.warmups?"warmup":"measured";}
    private Sample sample(String engine,long start,long setup,long run,long snapshot,long active,long work,long maxFast,ICraftingPlan plan,Throwable error){
        return new Sample(fixture.name,engine,sampleIndex,phase(),fixture.amount,System.nanoTime()-start,setup,run,snapshot,active,work,maxFast,plan,error==null?"":error.toString());
    }
    private CompletableFuture<Sample> legacy(){
        long start=System.nanoTime(),counter=LegacyCalls.maxFast.get();
        try {
            var calc=new CraftingCalculation(level,grid,()->source,new GenericStack(key(fixture.target),fixture.amount),CalculationStrategy.REPORT_MISSING_ITEMS);
            long setup=System.nanoTime()-start;
            return CompletableFuture.supplyAsync(()->{
                long began=System.nanoTime(); ICraftingPlan plan=null;Throwable error=null;
                try{plan=calc.run();}catch(Throwable t){error=t;}
                long elapsed=System.nanoTime()-began;
                return sample("MAX_FAST",start,setup,elapsed,0,setup+elapsed,0,LegacyCalls.maxFast.get()-counter,plan,error);
            },OLD).orTimeout(30,TimeUnit.SECONDS);
        }catch(Throwable t){return CompletableFuture.completedFuture(sample("MAX_FAST",start,0,0,0,0,0,0,null,t));}
    }
    private CompletableFuture<Sample> graph(){
        long start=System.nanoTime();
        var request=(GraphPlanningRequest)grid.getCraftingService().beginCraftingCalculation(level,()->source,key(fixture.target),fixture.amount,CalculationStrategy.REPORT_MISSING_ITEMS);
        long setup=System.nanoTime()-start;
        return request.handle((plan,error)->{
            var b=request.budget();var ns=b.metrics().activeNanos();long total=ns.values().stream().mapToLong(Long::longValue).sum();
            var result=sample("CGSE",start,setup,plan instanceof AeGraphPlan a?a.graph().planningNanos():0,ns.getOrDefault(PlanningBudget.Phase.SNAPSHOT,0L),total,b.nodes(),0,plan,error);
            if(fixture.name.endsWith("_profile"))System.out.println("[Engine Perf] PROFILE "+fixture.name+" "+b.diagnostics()+" "+b.metrics());
            return result;
        }).orTimeout(30,TimeUnit.SECONDS);
    }
    private CompletableFuture<Sample> fallback(){
        long start=System.nanoTime(); var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
        var capture=fallbackCatalog.begin(grid,(CraftingService)grid.getCraftingService(),level,source,key(fixture.target),budget);
        var snapshotFuture=new CompletableFuture<GtlPatternCatalog.Snapshot>();
        GraphSnapshots.enqueue(capture,budget,snapshotFuture,t->{});
        long setup=System.nanoTime()-start;
        return snapshotFuture.thenCompose(snap->fallbackScheduler.submit(snap.structure().catalog().build(budget),budget).thenApply(prepared->{
            long began=System.nanoTime();var quick=new PlanningBudget(250,131_072,16L<<20,()->false,System::nanoTime);
            ICraftingPlan plan=null;Throwable error=null;
            try{var p=GraphFallback.plan(prepared.compiler(),key(fixture.target),fixture.amount,snap.stock(),snap.emitable(),Map.of(),true,true,quick);
                plan=new AeGraphPlan(p,prepared.bindings(),snap.emitable(),snap.stock(),true);
            }catch(Throwable t){error=t;}
            long run=System.nanoTime()-began;var ns=budget.metrics().activeNanos();
            return sample("FALLBACK_ONLY",start,setup,run,ns.getOrDefault(PlanningBudget.Phase.SNAPSHOT,0L),ns.values().stream().mapToLong(Long::longValue).sum()+run,quick.nodes(),0,plan,error);
        })).exceptionally(error->sample("FALLBACK_ONLY",start,setup,0,0,0,0,0,null,error));
    }
    private void report(Sample s){
        var row=new LinkedHashMap<String,Object>();
        row.put("case",s.caseName);row.put("engine",s.engine);row.put("sample",s.sample);row.put("phase",s.phase);
        row.put("amount",Long.toString(s.amount));row.put("truth",fixture.truth);row.put("byproducts",fixture.byproducts);
        row.put("wall_ms",s.wallNanos/1e6);row.put("setup_ms",s.setupNanos/1e6);row.put("run_ms",s.runNanos/1e6);
        row.put("snapshot_ms",s.snapshotNanos/1e6);row.put("active_ms",s.activeNanos/1e6);row.put("work",s.work);
        row.put("max_fast_calls",s.maxFastCalls);row.put("error",s.failure);
        if(s.plan!=null){
            row.put("simulation",s.plan.simulation());row.put("missing_keys",s.plan.missingItems().size());
            row.put("recipes_used",s.plan.patternTimes().size());row.put("bytes",s.plan.bytes());
            row.put("fallback",s.plan instanceof AeGraphPlan a&&a.fallback());
            row.put("result",s.plan instanceof AeGraphPlan a?a.graph().result().name():s.plan.simulation()?"MISSING_PREVIEW":"FEASIBLE");
            row.put("verified",verify(s.plan));
        }else{row.put("result","ERROR");row.put("verified",false);}
        System.out.println("[Engine Perf] SAMPLE "+JSON.toJson(row));
    }
    // Independent material check; graph execution also checked by an independent compact prefix summary.
    private String verify(ICraftingPlan plan){
        if(!plan.finalOutput().what().equals(key(fixture.target))||plan.finalOutput().amount()!=fixture.amount)throw new AssertionError("Wrong order");
        Map<AEKey,BigInteger> available=new HashMap<>();stock.forEach((k,v)->available.put(k,BigInteger.valueOf(v)));
        if(plan instanceof AeGraphPlan a){
            var p=a.graph();Summary summary=summary(p.steps(),p);
            p.missingExact().forEach((k,v)->available.merge(k,v,BigInteger::add));
            for(var e:summary.need.entrySet())if(available.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(e.getValue())<0)throw new AssertionError("Invalid prefix");
            if(summary.delta.getOrDefault(key(fixture.target),BigInteger.ZERO).compareTo(BigInteger.valueOf(fixture.amount))<0)throw new AssertionError("No newly crafted target");
            return plan.simulation()?"FUNDED_PREFIX":"PREFIX_AND_TARGET";
        }
        for(var e:plan.missingItems())available.merge(e.getKey(),BigInteger.valueOf(e.getLongValue()),BigInteger::add);
        Map<IPatternDetails,BigInteger> todo=new LinkedHashMap<>();
        plan.patternTimes().forEach((r,n)->{if(n<0)throw new AssertionError("Negative legacy count");if(n>0)todo.put(r,BigInteger.valueOf(n));});
        int passes=0;
        while(!todo.isEmpty()&&passes++<10000){
            boolean progress=false;
            for(var it=todo.entrySet().iterator();it.hasNext();){
                var e=it.next();var r=originals.get(e.getKey());if(r==null)throw new AssertionError("Unknown pattern");
                BigInteger fire=e.getValue();
                for(var in:r.inputs.entrySet())fire=fire.min(available.getOrDefault(key(in.getKey()),BigInteger.ZERO).divide(BigInteger.valueOf(in.getValue())));
                if(fire.signum()==0)continue;progress=true;
                for(var in:r.inputs.entrySet())available.merge(key(in.getKey()),fire.multiply(BigInteger.valueOf(in.getValue())).negate(),BigInteger::add);
                for(var out:r.outputs.entrySet())available.merge(key(out.getKey()),fire.multiply(BigInteger.valueOf(out.getValue())),BigInteger::add);
                if(fire.equals(e.getValue()))it.remove();else e.setValue(e.getValue().subtract(fire));
            }
            if(!progress)break;
        }
        if(!todo.isEmpty())return "UNVERIFIED_ORDER";
        BigInteger goal=available.getOrDefault(key(fixture.target),BigInteger.ZERO).subtract(BigInteger.valueOf(stock.getOrDefault(key(fixture.target),0L)));
        // A missing target itself is a preview boundary, not a claim of new production.
        if(goal.compareTo(BigInteger.valueOf(fixture.amount))<0)return "INVALID_TARGET";
        return plan.simulation()?"FUNDED_PREFIX":"PREFIX_AND_TARGET";
    }
    private record Summary(Map<AEKey,BigInteger> need,Map<AEKey,BigInteger> delta){}
    private static Summary compose(Summary a,Summary b){
        var need=new HashMap<>(a.need);var delta=new HashMap<>(a.delta);
        b.need.forEach((k,v)->need.merge(k,v.subtract(a.delta.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max));
        b.delta.forEach((k,v)->delta.merge(k,v,BigInteger::add));return new Summary(need,delta);
    }
    private static Summary repeat(Summary body,BigInteger n){
        if(n.signum()==0)return new Summary(Map.of(),Map.of());
        var need=new HashMap<>(body.need);var delta=new HashMap<AEKey,BigInteger>();
        body.delta.forEach((k,v)->{delta.put(k,v.multiply(n));if(v.signum()<0)need.merge(k,v.negate().multiply(n.subtract(BigInteger.ONE)),BigInteger::add);});
        return new Summary(need,delta);
    }
    private static Summary summary(PlanStep step,GraphPlan<AEKey> p){
        if(step instanceof PlanStep.Batch b){var r=p.recipes().get(b.recipe());var need=new HashMap<AEKey,BigInteger>();var delta=new HashMap<AEKey,BigInteger>();
            r.inputs().forEach((k,v)->{need.put(k,BigInteger.valueOf(v));delta.put(k,BigInteger.valueOf(v).negate());});
            r.outputs().forEach((k,v)->delta.merge(k,BigInteger.valueOf(v),BigInteger::add));return repeat(new Summary(need,delta),BigInteger.valueOf(b.runs()));
        }
        if(step instanceof PlanStep.Repeat r)return repeat(summary(r.body(),p),BigInteger.valueOf(r.times()));
        Summary s=new Summary(Map.of(),Map.of());for(var child:((PlanStep.Sequence)step).children())s=compose(s,summary(child,p));return s;
    }
    private void remove(){if(providers!=null&&node!=null)providers.removeProvider(node);if(store!=null)grid.getStorageService().removeGlobalStorageProvider(store);providers=null;node=null;store=null;}
    private void close(){remove();fallbackScheduler.close();saved.forEach((k,v)->{try{ConfigHolder.INSTANCE.getClass().getField(k).set(ConfigHolder.INSTANCE,v);}catch(Exception ignored){}});}
    public List<IPatternDetails> getAvailablePatterns(){return patterns;}
    public boolean isBusy(){return false;}
    public boolean pushPattern(IPatternDetails pattern,KeyCounter[] inputs){throw new AssertionError("Benchmark submitted production");}
}
