package org.cgse.core;
import com.google.gson.Gson;
import java.util.*;
import java.math.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class DemandProbe {
    static long checks,requests;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static GraphPlan<String> solve(GraphCompiler.Compiled<String> graph,GraphDemandProgram<String> program,String target,long amount,Map<String,Long> stock,Map<String,Long> seeds,Set<String> external,boolean force,boolean preserve){var b=budget();var work=new GraphSolve<>(graph,target,amount,stock,external,seeds,preserve,force,b,System.nanoTime(),CatalystPolicy.STOCK,stock).program(program);while(!work.step()){}var result=work.result();if(result.feasible())PlanVerifier.verifyRuntimeInventory(result);return result;}
    static void compare(GraphPlan<String> a,GraphPlan<String> z,String label){check(a.result()==z.result()&&a.patternTimesExact().equals(z.patternTimesExact())&&a.initialExact().equals(z.initialExact())&&a.missingExact().equals(z.missingExact())&&a.seeds().equals(z.seeds())&&a.steps().equals(z.steps()),"demand program changed plan "+label);requests++;}
    static void random(){for(int seed=0;seed<1600;seed++){
        var rng=new Random(seed*99991L+121);int n=4+rng.nextInt(45);var rs=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();in.put("k"+(i-1),1L+rng.nextInt(11));for(int j=0;j<2;j++)if(rng.nextBoolean())in.merge("k"+(rng.nextInt(i+1)-1),1L+rng.nextInt(4),Long::sum);var out=new LinkedHashMap<String,Long>();out.put("k"+i,2L+rng.nextInt(15));if(rng.nextBoolean())out.put("by"+i,1L+rng.nextInt(10));rs.add(recipe("r"+i,in,out));}
        var c=new GraphCompiler<>(rs);String target="k"+(n-1);var graph=c.compile(target,Map.of(),Set.of(),budget());var b=budget();check(c.demandProgram(graph,b)==null,"unobserved cache preparation");var program=c.demandProgram(graph,b);check(program!=null&&c.demandProgram(graph,b)==program,"missing completed program reuse");check(b.reservedBytes()==0,"program preparation leak");
        for(int profile=0;profile<8;profile++){var stock=new HashMap<String,Long>();stock.put("k-1",profile%4==0?Long.MAX_VALUE:1000000L);for(int i=0;i<n;i++)if(rng.nextInt(3)==0)stock.put("k"+i,(long)rng.nextInt(100));var seeds=profile%3==0?Map.of("k"+rng.nextInt(n),3L):Map.<String,Long>of();var ext=profile%5==0?Set.of("k-1"):Set.<String>of();long amount=profile==7?Long.MAX_VALUE:1L+rng.nextInt(1000);compare(solve(graph,null,target,amount,stock,seeds,ext,profile%2==0,profile%3==0),solve(graph,program,target,amount,stock,seeds,ext,profile%2==0,profile%3==0),seed+"/"+profile);}
    }}
    static void boundaries(){
        var rs=List.of(recipe("feed",Map.of("A",2L),Map.of("X",3L)),recipe("left",Map.of("X",1L),Map.of("L",1L)),recipe("right",Map.of("X",1L),Map.of("R",1L)),recipe("end",Map.of("L",1L,"R",1L),Map.of("B",1L)));var c=new GraphCompiler<>(rs);var graph=c.compile("B",Map.of(),Set.of(),budget());var b=budget();var p=GraphDemandProgram.create(graph,b);var plan=solve(graph,p,"B",1,Map.of("A",2L),Map.of(),Set.of(),true,false);check(plan.feasible(),"shared ceil became missing");check(plan.patternTimesExact().get("feed").equals(BigInteger.ONE),"ceil applied before accumulating consumers");check(b.reservedBytes()==0,"shared preparation leak");
        var mixed=List.of(recipe("seed",Map.of("A",1L),Map.of("X",1L)),recipe("grow",Map.of("X",1L),Map.of("Y",2L)),recipe("return",Map.of("Y",1L),Map.of("X",1L)),recipe("end",Map.of("X",1L),Map.of("B",1L)));var mc=new GraphCompiler<>(mixed);
        for(int chosen=0;chosen<2;chosen++){var g=mc.compile("B",Map.of("X",chosen),Set.of(),budget());var program=GraphDemandProgram.create(g,budget());for(int quantity:new int[]{1,2,1000})for(boolean seed:List.of(false,true)){var stock=seed?Map.of("A",10000L,"X",1L):Map.of("A",10000L);compare(solve(g,null,"B",quantity,stock,Map.of(),Set.of(),true,false),solve(g,program,"B",quantity,stock,Map.of(),Set.of(),true,false),"mixed");}}
        for(int cap=1;cap<=200;cap++){final int stop=cap;var counter=new AtomicInteger();b=new PlanningBudget(0,2_000_000,128L<<20,()->counter.incrementAndGet()>stop,System::nanoTime);try{GraphDemandProgram.create(graph,b);}catch(java.util.concurrent.CancellationException expected){}check(b.reservedBytes()==0,"cancel program leak");}
        var fresh=new GraphCompiler<>(rs);var fg=fresh.compile("B",Map.of(),Set.of(),budget());check(fresh.demandProgram(fg,budget())==null,"first access");check(fresh.demandProgram(fg,new PlanningBudget(0,1024,128L<<20,()->false,System::nanoTime))==null,"small allowance admitted");check(fresh.demandProgram(fg,budget())!=null,"incomplete compile poisoned cache");
        try{new GraphSolve<>(fg,"B",1,Map.of(),Set.of(),Map.of(),false,true,budget(),0,CatalystPolicy.STOCK,Map.of()).program(p);throw new AssertionError("foreign program accepted");}catch(IllegalArgumentException expected){}
    }
    public static void main(String[]args)throws Exception{random();boundaries();var report=Map.of("DAGs",1600,"requests",requests,"checks",checks,"cancellations",200);Files.writeString(Path.of(args[0],"demand-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
