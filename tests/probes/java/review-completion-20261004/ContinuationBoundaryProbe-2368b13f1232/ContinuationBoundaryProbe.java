package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ContinuationBoundaryProbe {
    static long checks, resumes;static int dead, feasible, migrated;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static boolean oracle(List<GraphRecipe<String>> recipes,Map<String,Long> stock,BigInteger[] counts,Set<List<BigInteger>> seen) {
        if(Arrays.stream(counts).allMatch(x->x.signum()==0))return true;
        if(!seen.add(List.of(counts.clone())))return false;
        for(int i=0;i<counts.length;i++)if(counts[i].signum()>0){var recipe=recipes.get(i);boolean enabled=true;for(var in:recipe.inputs().entrySet())enabled&=stock.getOrDefault(in.getKey(),0L)>=in.getValue();if(!enabled)continue;
            var next=new HashMap<>(stock);recipe.inputs().forEach((k,v)->next.merge(k,-v,Long::sum));recipe.outputs().forEach((k,v)->next.merge(k,v,Long::sum));counts[i]=counts[i].subtract(BigInteger.ONE);
            if(oracle(recipes,next,counts,seen)){counts[i]=counts[i].add(BigInteger.ONE);return true;}counts[i]=counts[i].add(BigInteger.ONE);
        }return false;
    }
    static void negativeContinuations() {
        for(int seed=0;seed<360;seed++) {
            var r=new Random(7001L*seed+19);var recipes=new ArrayList<GraphRecipe<String>>();int n=3+r.nextInt(4);BigInteger[] counts=new BigInteger[n];var stock=new LinkedHashMap<String,Long>();
            for(int j=0;j<3;j++)stock.put("k"+j,(long)r.nextInt(5));
            for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=0;j<1+r.nextInt(2);j++)in.merge("k"+r.nextInt(3),1L+r.nextInt(2),Long::sum);for(int j=0;j<1+r.nextInt(2);j++)out.merge("k"+r.nextInt(3),1L+r.nextInt(3),Long::sum);recipes.add(ContinuationProbe.recipe("r"+i,in,out));counts[i]=BigInteger.valueOf(r.nextInt(3));}
            boolean expected=oracle(recipes,stock,counts.clone(),new HashSet<>());var b=ContinuationProbe.budget();
            try(var model=RecipeCountModel.forShell(recipes,Map.of(),stock,Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)) {
                CountSchedule<String> active=pool.acquire(counts);
                try {
                    while(!active.step()){check(pool.retain(active),"retention refused");var previous=active;active=null;active=pool.acquire(counts.clone());check(active==previous,"negative continuation reset");resumes++;}
                    check(active.result()==(expected?CountSchedule.Result.WITNESS:CountSchedule.Result.DEAD),"wrong oracle result "+seed+" "+active.result());
                    if(expected){ContinuationProbe.verify(new ContinuationProbe.Example(recipes,stock,counts),active.witness(),b);feasible++;}else dead++;
                    check(!pool.retain(active),"completed outcome cached");
                    try(var fresh=pool.acquire(counts)){check(fresh!=active,"DEAD/WITNESS deduplicated as continuation");}
                }finally{if(active!=null)active.close();}
            }
            check(b.reservedBytes()==0,"oracle leak");
        }
    }
    static void migration() throws Exception {
        var first=Executors.newSingleThreadExecutor();var second=Executors.newSingleThreadExecutor();
        var start=IntegerCountBranch.class.getDeclaredMethod("beginScheduling");start.setAccessible(true);
        try {
            for(int seed:new int[]{2992,3520,468,105,628,3104,141,2800}) {
                var e=ContinuationProbe.example(seed);var recipes=new ArrayList<>(e.recipes());recipes.add(ContinuationProbe.recipe("finish",Map.of(),Map.of("goal",1L)));
                var counts=Arrays.copyOf(e.counts(),e.counts().length+1);counts[counts.length-1]=BigInteger.ONE;var b=ContinuationProbe.budget();
                try(var model=RecipeCountModel.forShell(recipes,Map.of("goal",BigInteger.ONE),e.stock(),Set.of(),b);var execution=new CountExecution<>(model,b);
                    var branch=new IntegerCountBranch<>(model,execution,"goal",1,e.stock(),Map.of(),Set.of(),false,true,b,System.nanoTime(),List.of())) {
                    branch.initialized=true;branch.failureNeighborhoodTried=true;branch.linearConstraints=new ArrayList<>(model.constraints);branch.lower=new BigInteger[counts.length];Arrays.fill(branch.lower,BigInteger.ZERO);branch.upper=counts.clone();
                    int proposals=0,rounds=0;
                    do {
                        branch.counts=counts.clone();branch.jumpCandidate=true;branch.jumpLate=true;branch.state=IntegerCountBranch.State.OPEN;start.invoke(branch);proposals++;
                        while(branch.state==IntegerCountBranch.State.OPEN) {
                            var worker=(rounds++%2==0)?first:second;
                            worker.submit(()->branch.run(128,List.of(),List.of(),List.of(),null,()->false)).get();
                            check(rounds<10000,"migration did not progress");
                        }
                        check(proposals<20,"migration lost continuation");
                    }while(branch.state==IntegerCountBranch.State.UNRESOLVED);
                    check(branch.state==IntegerCountBranch.State.FOUND&&proposals>=1,"migration outcome");PlanVerifier.verifyRuntimeInventory(branch.plan);migrated++;
                }
                check(b.reservedBytes()==0,"migration leak");
            }
        }finally{first.shutdownNow();second.shutdownNow();}
    }
    public static void main(String[] args)throws Exception {
        negativeContinuations();migration();var report=Map.of("oracleCases",360,"dead",dead,"feasible",feasible,"resumptions",resumes,"workerMigrationCases",migrated,"assertions",checks);
        Files.writeString(Path.of(args[0],"continuation-boundaries.json"),new Gson().toJson(report));System.out.println(report);
    }
}
