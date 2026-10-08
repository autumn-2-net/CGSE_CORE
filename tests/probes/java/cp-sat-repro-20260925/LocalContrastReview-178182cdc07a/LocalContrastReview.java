package org.cgse.core;

import com.google.gson.*;

import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;

/** Standalone black-box contrast. Production GTL sources are not modified. */
public final class LocalContrastReview {
    static boolean TRACE=false;
    record Fixture(String name,List<GraphRecipe<String>> recipes,Map<String,Long> stock,String target,long amount,boolean dag) {}
    record Result(String status,double ms,String info) {}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static Fixture subset(int n,int seed) {
        Random rng=new Random(seed);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>();
        long yes=0,no=0;List<Long> weights=new ArrayList<>();List<Integer> planted=new ArrayList<>();
        for(int i=0;i<n;i++){
            long w=100+rng.nextInt(9900);boolean chosen=rng.nextBoolean();weights.add(w);if(chosen){yes+=w;planted.add(i);}else no+=w;
            String u="U"+i;stock.put(u,1L);
            rs.add(r("yes"+i,Map.of(u,1L),Map.of("X",w)));
            rs.add(r("no"+i,Map.of(u,1L),Map.of("Y",w)));
        }
        rs.add(r("finish",Map.of("X",yes,"Y",no),Map.of("GOAL",1L)));
        System.out.println("FIXTURE subset_n"+n+"_seed"+seed+" weights="+weights+" X="+yes+" Y="+no+" planted="+planted);
        return new Fixture("subset_n"+n+"_seed"+seed,List.copyOf(rs),stock,"GOAL",1,true);
    }
    static Fixture sat(int n,int m,int seed) {
        Random rng=new Random(seed);boolean[] planted=new boolean[n];for(int i=0;i<n;i++)planted[i]=rng.nextBoolean();
        List<int[]> clauses=new ArrayList<>();
        while(clauses.size()<m){
            int[] c=new int[3];Set<Integer> used=new HashSet<>();boolean satisfied=false;
            for(int j=0;j<3;j++){int v;do{v=rng.nextInt(n);}while(!used.add(v));boolean positive=rng.nextBoolean();c[j]=positive?v+1:-v-1;satisfied|=planted[v]==positive;}
            if(satisfied)clauses.add(c);
        }
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),goalIn=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            stock.put("U"+i,1L);goalIn.put("D"+i,1L);
            for(int val=0;val<2;val++){
                Map<String,Long> outputs=new LinkedHashMap<>();outputs.put("D"+i,1L);
                for(int j=0;j<m;j++)for(int lit:clauses.get(j))if(Math.abs(lit)==i+1 && (lit>0)==(val==1))outputs.put("C"+j,1L);
                rs.add(r("v"+i+"_"+val,Map.of("U"+i,1L),outputs));
            }
        }
        for(int j=0;j<m;j++)goalIn.put("C"+j,1L);
        rs.add(r("finish",goalIn,Map.of("GOAL",1L)));
        return new Fixture("sat_n"+n+"_m"+m+"_seed"+seed,List.copyOf(rs),stock,"GOAL",1,true);
    }
    static Fixture cycle(long n){return new Fixture("two_step_cycle_"+n,List.of(r("R1",Map.of("A",1L,"X",1L),Map.of("B",1L)),r("R2",Map.of("B",1L),Map.of("A",1L,"H",1L))),Map.of("A",1L,"X",n),"H",n,false);}
    static Fixture dag(long n){return new Fixture("dag_"+n,List.of(r("R1",Map.of("X",1L),Map.of("H",1L))),Map.of("X",n),"H",n,true);}
    record Summary(Map<String,BigInteger> need,Map<String,BigInteger> delta,int nodes) {}
    static Summary summary(PlanStep step,Map<String,GraphRecipe<String>> recipes){
        if(step instanceof PlanStep.Batch b){
            GraphRecipe<String> r=recipes.get(b.recipe());Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();Set<String> keys=new LinkedHashSet<>(r.inputs().keySet());keys.addAll(r.outputs().keySet());
            BigInteger n=BigInteger.valueOf(b.runs());
            if(n.signum()==0)return new Summary(need,delta,1);
            for(String k:keys){BigInteger in=BigInteger.valueOf(r.inputs().getOrDefault(k,0L)),d=BigInteger.valueOf(r.outputs().getOrDefault(k,0L)).subtract(in);need.put(k,in.add(d.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));delta.put(k,d.multiply(n));}
            return new Summary(need,delta,1);
        }
        if(step instanceof PlanStep.Repeat r){
            Summary s=summary(r.body(),recipes);Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();BigInteger n=BigInteger.valueOf(r.times());
            if(n.signum()==0)return new Summary(need,delta,s.nodes+1);
            for(String k:s.need.keySet()){BigInteger d=s.delta.getOrDefault(k,BigInteger.ZERO);need.put(k,s.need.get(k).add(d.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));delta.put(k,d.multiply(n));}
            return new Summary(need,delta,s.nodes+1);
        }
        Map<String,BigInteger> need=new LinkedHashMap<>(),delta=new LinkedHashMap<>();int nodes=1;
        for(PlanStep child:((PlanStep.Sequence)step).children()){
            Summary s=summary(child,recipes);nodes+=s.nodes;
            for(String k:s.need.keySet())need.merge(k,s.need.get(k).subtract(delta.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max);
            s.delta.forEach((k,v)->delta.merge(k,v,BigInteger::add));
        }
        return new Summary(need,delta,nodes);
    }
    static void verify(Fixture f,Summary s){
        for(var e:s.need.entrySet())if(e.getValue().compareTo(BigInteger.valueOf(f.stock.getOrDefault(e.getKey(),0L)))>0)throw new AssertionError("Unfunded prefix "+f.name+" "+e);
        if(BigInteger.valueOf(f.stock.getOrDefault(f.target,0L)).add(s.delta.getOrDefault(f.target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(f.amount))<0)throw new AssertionError("Unmet goal");
    }
    static Result gtl(Fixture f,long ms){
        long start=System.nanoTime();PlanningBudget b=new PlanningBudget(ms,20_000_000,256L<<20,()->false,System::nanoTime);
        GraphPlan<String> p=new GraphPlanner<>(new GraphCompiler<>(f.recipes)).plan(f.target,f.amount,f.stock,true,true,b);
        double time=(System.nanoTime()-start)/1e6;
        String info="checks="+b.nodes()+" detail="+b.failureDetail();
        if(p.feasible()){Summary s=summary(p.steps(),p.recipes());verify(f,s);info+=" plan_nodes="+s.nodes+" counts="+p.patternTimesExact();}
        else info+=" trace="+b.diagnostics();
        return new Result(p.result().name(),time,info);
    }

    static Fixture file(String filename) throws Exception {
        JsonObject j = JsonParser.parseString(Files.readString(Path.of(filename))).getAsJsonObject();
        var stock = jsonMap(j.getAsJsonObject("stock"));
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (var e : j.getAsJsonArray("recipes")) {
            var v = e.getAsJsonObject();
            recipes.add(r(v.get("id").getAsString(), jsonMap(v.getAsJsonObject("inputs")), jsonMap(v.getAsJsonObject("outputs"))));
        }
        return new Fixture("supplied_json", recipes, stock, j.get("target").getAsString(), j.get("amount").getAsLong(), true);
    }
    static Map<String,Long> jsonMap(JsonObject j) {
        var result = new LinkedHashMap<String,Long>();
        j.entrySet().forEach(e -> result.put(e.getKey(),e.getValue().getAsLong()));
        return result;
    }
    static void witness(Fixture f) {
        var yes = Set.of(2,3,7,8,9,12,13);
        var inventory = new LinkedHashMap<String,BigInteger>();
        f.stock.forEach((k,v) -> inventory.put(k,BigInteger.valueOf(v)));
        for(int i=0; i<=20; i++) {
            final String id = i==20 ? "finish" : (yes.contains(i) ? "yes" : "no")+i;
            var recipe = f.recipes.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            recipe.inputs().forEach((k,v) -> {
                var available = inventory.getOrDefault(k,BigInteger.ZERO);
                if(available.compareTo(BigInteger.valueOf(v))<0) throw new AssertionError("Illegal firing "+id+" "+k);
                inventory.put(k,available.subtract(BigInteger.valueOf(v)));
            });
            recipe.outputs().forEach((k,v) -> inventory.merge(k,BigInteger.valueOf(v),BigInteger::add));
        }
        if(!inventory.get("GOAL").equals(BigInteger.ONE)) throw new AssertionError(inventory);
        if(!inventory.get("X").equals(BigInteger.ZERO) || !inventory.get("Y").equals(BigInteger.ZERO)) throw new AssertionError(inventory);
        System.out.println("SUPPLIED_WITNESS independently_fired=21 final="+inventory);
    }
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ROOT);
        if(args[0].equals("subset")) {
            var f=file(args[1]); witness(f);
            for(int i=0;i<16;i++)gtl(dag(100),3000);
            for(int i=0;i<Integer.parseInt(args[2]);i++)System.out.println("GTL_SUBSET "+i+" "+gtl(f,3000));
        } else if(args[0].equals("cycle")) {
            for(int i=0;i<48;i++)gtl(cycle(100),3000);
            for(long n:new long[]{1000,1_000_000_000L,Long.MAX_VALUE})System.out.println("GTL_CYCLE "+n+" "+gtl(cycle(n),3000));
        } else if(args[0].equals("generated")) {
            for(int n:new int[]{12,20,28,36})System.out.println("GTL_SUBSET_GENERATED "+n+" "+gtl(subset(n,42),3000));
        }
    }
}
