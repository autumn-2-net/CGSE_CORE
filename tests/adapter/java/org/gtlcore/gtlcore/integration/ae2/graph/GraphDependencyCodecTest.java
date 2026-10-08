package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import appeng.api.stacks.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import java.io.*;
import java.util.*;

/** Local only: real AE keys, compressed NBT, delayed outputs and legacy migration. */
public final class GraphDependencyCodecTest {
    static GraphRecipe<AEKey> recipe(String id, Map<AEKey,Long> in, Map<AEKey,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    public static void run() throws Exception {
        AEKey seed=AEItemKey.of(Items.DIAMOND),raw=AEItemKey.of(Items.COAL),mid=AEItemKey.of(Items.GOLD_INGOT),a=AEItemKey.of(Items.GOLD_NUGGET),
            other=AEItemKey.of(Items.IRON_INGOT),b=AEItemKey.of(Items.IRON_NUGGET),target=AEItemKey.of(Items.EMERALD);
        var r1=recipe("a",Map.of(seed,1L,raw,1L),Map.of(mid,1L));
        var r2=recipe("b",Map.of(mid,1L),Map.of(seed,1L,a,1L));
        var independent=recipe("independent",Map.of(other,1L),Map.of(b,1L));
        var join=recipe("join",Map.of(a,1L,b,1L),Map.of(target,1L));
        Map<String,GraphRecipe<AEKey>> recipes=new LinkedHashMap<>();
        for(var r:List.of(r1,r2,independent,join)) recipes.put(r.id(),r);
        int count=67;
        var loop=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1))),count);
        var steps=new PlanStep.Sequence(List.of(loop,new PlanStep.Batch("independent",count),new PlanStep.Batch("join",count)));
        var stock=Map.of(seed,1L,raw,(long)count,other,(long)count);
        var plan=new GraphPlan<>(target,count,true,steps,recipes,stock,Map.of(seed,1L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var runner=new Runner(new GraphJobRuntime<>(plan,stock,Map.of()));
        runner.blocked=true;
        for(int tick=0;tick<20;tick++)runner.step();
        check(runner.accepted.getOrDefault("independent",0L)>0,"Independent branch dispatched while cycle blocked");
        check(!runner.accepted.containsKey("join"),"No predicted material consumption");
        var blocked=disk(GraphJobCodec.write(runner.runtime.snapshot()));
        for(int i=0;i<100;i++) {
            runner.runtime=new GraphJobRuntime<>(GraphJobCodec.read(blocked));
            var again=disk(GraphJobCodec.write(runner.runtime.snapshot()));
            check(blocked.equals(again),"Idle reload does not grow or change pending program");
            blocked=again;
        }
        var corrupt=blocked.copy();
        corrupt.getCompound("acceptedRuns").putString("independent","0");
        try { new GraphJobRuntime<>(GraphJobCodec.read(corrupt));throw new AssertionError("Corrupt accepted/pending mismatch was accepted"); }
        catch(IllegalArgumentException expected) {}
        runner.blocked=false;
        for(int tick=0;tick<3000&&!runner.runtime.finished();tick++)runner.step();
        check(runner.runtime.finished(),"Reloaded parallel task finishes");
        check(runner.runtime.snapshot().acceptedRuns().equals(plan.patternTimesExact()),"No duplicate work after every-tick NBT reload");
        check(runner.delivered==count,"Exact target delivery");
        check(runner.refunds.equals(Map.of(seed,1L)),"Exact seed refund");

        // Schema 9's prefetched pipeline had already accepted 'a' but not its
        // delayed physical return. Its cursor/window are migrated without replay.
        var current=new GraphJobRuntime<>(plan,stock,Map.of()).snapshot();
        var cursor=new PlanCursor(steps);
        cursor.dispatched(1);
        var window=new ArrayList<PlanStep.Batch>();
        for(int i=0;i<32;i++) { var part=cursor.current();window.add(part);cursor.dispatched(part.runs()); }
        var obligations=new OutputObligations<AEKey>(Map.of());
        obligations.dispatch("a",1,Map.of(mid,1L),false);
        var legacy=new GraphJobRuntime.Snapshot<>(plan,Map.of(raw,(long)count-1,other,(long)count),Map.of(mid,1L),Map.<AEKey,Long>of(),
            Map.of("a",java.math.BigInteger.ONE),cursor.snapshot(),window,count,current.state(),false,"",obligations.snapshot(),current.recovery(),Map.of());
        var tag=GraphJobCodec.write(legacy);tag.putInt("schemaVersion",9);
        var migrated=new Runner(new GraphJobRuntime<>(GraphJobCodec.read(disk(tag))));
        migrated.accepted.put("a",1L);
        migrated.flights.add(new Flight(3,Map.of(mid,1L)));
        for(int tick=0;tick<3000&&!migrated.runtime.finished();tick++)migrated.step();
        check(migrated.runtime.finished(),"Legacy prefetched cyclic task migrates and finishes");
        check(migrated.accepted.equals(plan.patternTimes()),"Legacy dispatch is not replayed");
        check(migrated.delivered==count&&migrated.refunds.equals(Map.of(seed,1L)),"Legacy output ownership preserved");
        System.out.println("Dependency NBT: every-tick compressed disk reload, 100 idle reloads, blocked independent branch, corrupt count and schema-9 delayed-output migration passed");
    }
    static CompoundTag disk(CompoundTag tag)throws IOException {
        var bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,bytes);
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
    }
    static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
    record Flight(long due,Map<AEKey,Long> outputs){}
    static class Runner implements GraphJobRuntime.Adapter<AEKey> {
        GraphJobRuntime<AEKey> runtime; long tick,delivered;boolean blocked;
        Map<String,Long> accepted=new LinkedHashMap<>();Map<AEKey,Long> refunds=new LinkedHashMap<>();List<Flight> flights=new ArrayList<>();
        Runner(GraphJobRuntime<AEKey> r){runtime=r;}
        void step()throws Exception {
            for(var it=flights.iterator();it.hasNext();) {var f=it.next();if(f.due>tick)continue;
                for(var out:f.outputs.entrySet())check(runtime.accept(out.getKey(),out.getValue(),false)==out.getValue(),"Declared output received once");
                it.remove();}
            runtime.tick(this,tick++,8);
            var saved=GraphJobCodec.write(runtime.snapshot());
            runtime=new GraphJobRuntime<>(GraphJobCodec.read(disk(saved)));
        }
        public long capacity(GraphRecipe<AEKey> r,long n){return blocked&&r.id().equals("a")?0:Math.min(n,2);}
        public GraphJobRuntime.Outcome push(GraphRecipe<AEKey> r,long n,Map<AEKey,Long> in){
            accepted.merge(r.id(),n,Math::addExact);var out=new LinkedHashMap<AEKey,Long>();r.outputs().forEach((k,v)->out.put(k,Math.multiplyExact(v,n)));
            flights.add(new Flight(tick+1+(accepted.get(r.id())%5),out));return GraphJobRuntime.Outcome.ACCEPTED;
        }
        public long deliver(AEKey k,long n){delivered=Math.addExact(delivered,n);return n;}
        public long refund(AEKey k,long n){refunds.merge(k,n,Math::addExact);return n;}
    }
}
