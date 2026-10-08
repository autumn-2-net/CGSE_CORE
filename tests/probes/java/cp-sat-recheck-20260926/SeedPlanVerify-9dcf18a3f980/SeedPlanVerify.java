import java.util.*;
import java.math.BigInteger;
import org.cgse.core.*;
public class SeedPlanVerify {
    record S(Map<String,BigInteger> required,Map<String,BigInteger> delta){}
    static S repeat(S s,long times){if(times==0)return new S(Map.of(),Map.of());var need=new HashMap<String,BigInteger>();var delta=new HashMap<String,BigInteger>();Set<String> keys=new HashSet<>(s.required.keySet());keys.addAll(s.delta.keySet());for(String k:keys){var d=s.delta.getOrDefault(k,BigInteger.ZERO);need.put(k,s.required.getOrDefault(k,BigInteger.ZERO).add(d.min(BigInteger.ZERO).negate().multiply(BigInteger.valueOf(times-1))));delta.put(k,d.multiply(BigInteger.valueOf(times)));}return new S(need,delta);}
    static S summary(PlanStep p,Map<String,GraphRecipe<String>> recipes){
        if(p instanceof PlanStep.Repeat r)return repeat(summary(r.body(),recipes),r.times());
        if(p instanceof PlanStep.Batch b){var r=recipes.get(b.recipe());var need=new HashMap<String,BigInteger>();var delta=new HashMap<String,BigInteger>();r.inputs().forEach((k,v)->{need.put(k,BigInteger.valueOf(v));delta.put(k,BigInteger.valueOf(v).negate());});r.outputs().forEach((k,v)->delta.merge(k,BigInteger.valueOf(v),BigInteger::add));return repeat(new S(need,delta),b.runs());}
        var need=new HashMap<String,BigInteger>();var delta=new HashMap<String,BigInteger>();for(var child:((PlanStep.Sequence)p).children()){var s=summary(child,recipes);s.required.forEach((k,v)->need.merge(k,v.subtract(delta.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max));s.delta.forEach((k,v)->delta.merge(k,v,BigInteger::add));}return new S(need,delta);
    }
    public static void main(String[]args)throws Exception{
        DumpReplay.main(new String[]{".local/ae-dumps/sky2-all.json",".local/cp-sat-recheck-20260926/empty.jsonl","1","__none__"});
        for(String target:List.of("k747","k3176"))for(long amount:new long[]{1,Integer.MAX_VALUE,Long.MAX_VALUE}){
            var b=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);var work=new GraphPlanningWork<>(DumpReplay.compiler(target),target,amount,DumpReplay.stock,DumpReplay.external,Map.of(),true,true,b).catalysts(CatalystPolicy.MINIMAL);while(!work.step()){}var p=work.result();if(!p.feasible()&&p.missingExact().isEmpty())throw new AssertionError(p.result()+" "+b.diagnostics());var s=summary(p.steps(),p.recipes());
            for(var e:s.required.entrySet())if(e.getValue().compareTo(p.initialExact().getOrDefault(e.getKey(),BigInteger.ZERO))>0)throw new AssertionError("unfunded prefix "+e);
            if(p.initialExact().getOrDefault(target,BigInteger.ZERO).add(s.delta.getOrDefault(target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(amount).add(BigInteger.valueOf(p.seeds().getOrDefault(target,0L))))<0)throw new AssertionError("goal");
            for(var e:p.seeds().entrySet())if(p.initialExact().getOrDefault(e.getKey(),BigInteger.ZERO).add(s.delta.getOrDefault(e.getKey(),BigInteger.ZERO)).compareTo(BigInteger.valueOf(e.getValue()))<0)throw new AssertionError("reserve");
            for(var e:p.initialExact().entrySet()){BigInteger missing=DumpReplay.external.contains(e.getKey())?BigInteger.ZERO:e.getValue().subtract(BigInteger.valueOf(DumpReplay.stock.getOrDefault(e.getKey(),0L))).max(BigInteger.ZERO);if(!missing.equals(p.missingExact().getOrDefault(e.getKey(),BigInteger.ZERO)))throw new AssertionError("hidden deficit "+e);}
            System.out.println("PASS target="+DumpReplay.labels.get(target)+" amount="+amount+" result="+p.result()+" seeds="+p.seeds().size()+" recipes="+p.recipes().size()+" checks="+b.nodes()+" independent exact funded prefix, delivery, returned seeds and deficits");
        }
    }
}
