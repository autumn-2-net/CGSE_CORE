package org.cgse.core;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;

/** Standalone black-box contrast. Production GTL sources are not modified. */
public final class ContrastProbe {
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
    // For the generated DAG family these state equations are exact: all source
    // choices consume independent initial tokens; only finish consumes outputs.
    static Result cpDag(Fixture f,double seconds){
        long start=System.nanoTime();CpModel cp=new CpModel();IntVar[] count=new IntVar[f.recipes.size()];
        long upper=f.amount;for(long x:f.stock.values())upper=Math.max(upper,x);
        for(int i=0;i<count.length;i++)count[i]=cp.newIntVar(0, f.recipes.get(i).id().equals("finish")?1:upper,"x"+i);
        Set<String> keys=new LinkedHashSet<>(f.stock.keySet());for(var r:f.recipes){keys.addAll(r.inputs().keySet());keys.addAll(r.outputs().keySet());}
        for(String k:keys){long[] coefficients=new long[count.length];for(int i=0;i<count.length;i++)coefficients[i]=f.recipes.get(i).outputs().getOrDefault(k,0L)-f.recipes.get(i).inputs().getOrDefault(k,0L);
            cp.addGreaterOrEqual(LinearExpr.weightedSum(count,coefficients),(k.equals(f.target)?f.amount:0)-f.stock.getOrDefault(k,0L));}
        long built=System.nanoTime();CpSolver solver=new CpSolver();solver.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(seconds).setLogSearchProgress(TRACE);CpSolverStatus status=solver.solve(cp);long end=System.nanoTime();
        if(status==CpSolverStatus.FEASIBLE||status==CpSolverStatus.OPTIMAL){
            List<PlanStep> steps=new ArrayList<>();Map<String,GraphRecipe<String>> recipes=new LinkedHashMap<>();for(int i=0;i<count.length;i++){var r=f.recipes.get(i);recipes.put(r.id(),r);steps.add(new PlanStep.Batch(r.id(),solver.value(count[i])));}verify(f,summary(new PlanStep.Sequence(steps),recipes));
        }
        return new Result(status.name(),(end-start)/1e6,"build_ms="+(built-start)/1e6+" solve_ms="+(end-built)/1e6+" branches="+solver.numBranches()+" conflicts="+solver.numConflicts()+" validation="+(status==CpSolverStatus.MODEL_INVALID?cp.validate():"ok"));
    }
    static Result cpCycle(Fixture f,double seconds,boolean expanded){
        long start=System.nanoTime();CpModel cp=new CpModel();long n=f.amount;IntVar a=null,b=null;BoolVar[][] fire=null;
        if(expanded){
            int horizon=Math.toIntExact(2*n);IntVar[][] q=new IntVar[horizon+1][4];String[] keys={"A","B","X","H"};
            for(int t=0;t<=horizon;t++)for(int k=0;k<4;k++)q[t][k]=cp.newIntVar(0,k<2?1:n,"q"+t+"_"+k);
            for(int k=0;k<4;k++)cp.addEquality(q[0][k],f.stock.getOrDefault(keys[k],0L));
            fire=new BoolVar[horizon][2];
            for(int t=0;t<horizon;t++){
                // Producing n H requires n of each recipe and the horizon is 2n;
                // therefore every step fires. State this implied cardinality explicitly.
                for(int r=0;r<2;r++)fire[t][r]=cp.newBoolVar("f"+t+"_"+r);cp.addExactlyOne(fire[t]);
                for(int k=0;k<4;k++){
                    long in0=f.recipes.get(0).inputs().getOrDefault(keys[k],0L),in1=f.recipes.get(1).inputs().getOrDefault(keys[k],0L);
                    cp.addGreaterOrEqual(q[t][k],LinearExpr.weightedSum(fire[t],new long[]{in0,in1}));
                    cp.addEquality(q[t+1][k],LinearExpr.weightedSum(new IntVar[]{q[t][k],fire[t][0],fire[t][1]},new long[]{1,f.recipes.get(0).outputs().getOrDefault(keys[k],0L)-in0,f.recipes.get(1).outputs().getOrDefault(keys[k],0L)-in1}));
                }
            }
            cp.addGreaterOrEqual(q[horizon][3],n);cp.addGreaterOrEqual(q[horizon][0],1);
        }else{
            // Complete structural encoding for this specific two-step cycle:
            // R1 then R2 can repeat iff A starts with one and X covers all runs.
            a=cp.newIntVar(0,n,"R1_count");b=cp.newIntVar(0,n,"R2_count");
            cp.addEquality(a,b);cp.addLessOrEqual(a,f.stock.get("X"));cp.addGreaterOrEqual(b,n);
        }
        long built=System.nanoTime();CpSolver solver=new CpSolver();solver.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(seconds);CpSolverStatus status=solver.solve(cp);long end=System.nanoTime();
        if(status==CpSolverStatus.OPTIMAL||status==CpSolverStatus.FEASIBLE){
            var recipes=new LinkedHashMap<String,GraphRecipe<String>>();for(var r:f.recipes)recipes.put(r.id(),r);
            PlanStep witness;
            if(expanded){List<PlanStep> steps=new ArrayList<>();for(int t=0;t<fire.length;t++)for(int r=0;r<2;r++)if(solver.booleanValue(fire[t][r]))steps.add(new PlanStep.Batch(f.recipes.get(r).id(),1));witness=new PlanStep.Sequence(steps);}
            else {if(solver.value(a)!=n||solver.value(b)!=n)throw new AssertionError();witness=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("R1",1),new PlanStep.Batch("R2",1))),n);}
            verify(f,summary(witness,recipes));
        }
        return new Result(status.name(),(end-start)/1e6,"build_ms="+(built-start)/1e6+" solve_ms="+(end-built)/1e6+" vars="+cp.getBuilder().getVariablesCount()+" branches="+solver.numBranches()+" validation="+(status==CpSolverStatus.MODEL_INVALID?cp.validate():"ok"));
    }
    public static void main(String[] args)throws Exception{
        Locale.setDefault(Locale.ROOT);Loader.loadNativeLibraries();
        String mode=args.length>0?args[0]:"screen";List<Fixture> cases=new ArrayList<>();
        if(mode.equals("screen")){
            for(int i=0;i<12;i++){Fixture f=dag(100);gtl(f,3000);cpDag(f,3);}
            for(int n:new int[]{12,20,28,36})cases.add(subset(n,42));
            for(int n:new int[]{12,20,32})cases.add(sat(n,n*4,42));
            for(Fixture f:cases){System.out.println("CP "+f.name+" "+cpDag(f,3));System.out.println("GTL "+f.name+" "+gtl(f,3000));}
        }else if(mode.equals("cycle")){
            for(int i=0;i<16;i++){var f=cycle(100);gtl(f,3000);cpCycle(f,3,false);}
            for(long n:new long[]{100,1000,10000,1_000_000_000L,Long.MAX_VALUE}){
                var f=cycle(n);System.out.println("GTL "+f.name+" "+gtl(f,3000));System.out.println("CP_COMPACT "+f.name+" "+cpCycle(f,3,false));
                if(n<=10000)System.out.println("CP_EXPANDED "+f.name+" "+cpCycle(f,3,true));
            }
        }else if(mode.equals("repeat")){
            int n=Integer.parseInt(args[1]);var f=subset(n,42);
            for(int i=0;i<3;i++){System.out.println("CP "+f.name+" "+cpDag(f,3));System.out.println("GTL "+f.name+" "+gtl(f,3000));}
        }else if(mode.equals("bench")){
            var f=subset(20,42);
            for(int i=0;i<16;i++){gtl(dag(100),3000);cpDag(f,3);}
            for(int i=0;i<21;i++)System.out.println("MEASURE_CP_SUBSET "+i+" "+cpDag(f,3));
            for(int i=0;i<7;i++)System.out.println((i<2?"WARM_GTL_SUBSET ":"MEASURE_GTL_SUBSET ")+i+" "+gtl(f,3000));
            var c=cycle(1_000_000_000L);
            for(int i=0;i<48;i++){gtl(c,3000);cpCycle(c,3,false);}
            for(int i=0;i<51;i++){
                if(i%2==0){System.out.println("MEASURE_GTL_CYCLE "+i+" "+gtl(c,3000));System.out.println("MEASURE_CP_CYCLE "+i+" "+cpCycle(c,3,false));}
                else {System.out.println("MEASURE_CP_CYCLE "+i+" "+cpCycle(c,3,false));System.out.println("MEASURE_GTL_CYCLE "+i+" "+gtl(c,3000));}
            }
            for(int i=0;i<3;i++)System.out.println("MEASURE_CP_EXPANDED "+i+" "+cpCycle(cycle(1000),3,true));
        }else if(mode.equals("trace")){
            TRACE=true;System.out.println(cpDag(subset(20,42),3));
        }
    }
}
