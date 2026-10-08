package org.cgse.core;

import com.google.ortools.Loader;
import java.util.*;
import java.math.BigInteger;
import static org.cgse.core.ContrastProbe.*;

/** Same fixtures/models as the previous contrast, with symmetric warmup and extra seeds. */
public final class RecheckProbe {
    static Result runGtl(Fixture f) {
        long start=System.nanoTime();
        PlanningBudget b=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
        GraphPlan<String> p=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).plan(f.target(),f.amount(),f.stock(),true,true,b);
        double time=(System.nanoTime()-start)/1e6;
        String info="checks="+b.nodes()+" detail="+b.failureDetail();
        if(p.feasible()) {
            Summary s=summary(p.steps(),p.recipes());verify(f,s);
            for(var e:p.seeds().entrySet()) {
                BigInteger end=BigInteger.valueOf(f.stock().getOrDefault(e.getKey(),0L)).add(s.delta().getOrDefault(e.getKey(),BigInteger.ZERO));
                if(end.compareTo(BigInteger.valueOf(e.getValue()))<0)throw new AssertionError("Seed reserve violated");
            }
            info+=" plan_nodes="+s.nodes()+" counts="+p.patternTimesExact();
        }
        return new Result(p.result().name(),time,info+" trace="+b.diagnostics());
    }
    static Result runCp(Fixture f) {return f.dag()?cpDag(f,3):cpCycle(f,3,false);}
    static void bench(Fixture f,int warm,int measured) {
        System.out.println("SETTINGS "+f.name()+" warm="+warm+" samples="+measured);
        for(int i=0;i<warm;i++){runGtl(f);runCp(f);}
        for(int i=0;i<measured;i++) {
            Result a,b;
            if(i%2==0){a=runGtl(f);b=runCp(f);}else{b=runCp(f);a=runGtl(f);}
            System.out.println("SAMPLE\t"+f.name()+"\tCGE\t"+i+"\t"+a.status()+"\t"+a.ms());
            System.out.println("SAMPLE\t"+f.name()+"\tCP_SAT\t"+i+"\t"+b.status()+"\t"+b.ms());
            if(i==0){System.out.println("DETAIL_CGE "+f.name()+" "+a.info());System.out.println("DETAIL_CP "+f.name()+" "+b.info());}
        }
    }
    static Fixture shortage(Fixture base) {
        List<GraphRecipe<String>> rs=new ArrayList<>(base.recipes());
        GraphRecipe<String> finish=rs.remove(rs.size()-1);
        Map<String,Long> in=new LinkedHashMap<>(finish.inputs());in.merge("X",1L,Long::sum);
        rs.add(r("finish",in,Map.of("GOAL",1L)));
        return new Fixture(base.name()+"_short1",List.copyOf(rs),base.stock(),"GOAL",1,true);
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);Loader.loadNativeLibraries();
        String mode=args.length>0?args[0]:"bench";
        switch(mode) {
            case "bench" -> {
                for(int n:new int[]{20,28,36})bench(subset(n,42),20,31);
                bench(sat(12,48,42),20,31);
                bench(cycle(1_000_000_000L),48,51);
            }
            case "boundary" -> {
                bench(sat(20,80,42),1,3);
                bench(sat(32,128,42),1,3);
            }
            case "old" -> bench(subset(20,42),1,3);
            case "seeds" -> {
                for(int seed=0;seed<8;seed++) {
                    Fixture f=subset(20,seed);
                    Result c=runCp(f),g=runGtl(f);
                    System.out.println("SEED "+seed+" CP="+c+" CGE="+g);
                    if(!c.status().equals("OPTIMAL")||!g.status().startsWith("FEASIBLE"))throw new AssertionError("Expected feasible seed "+seed);
                    if(seed<4) {
                        Fixture u=shortage(f);c=runCp(u);g=runGtl(u);
                        System.out.println("SHORT "+seed+" CP="+c+" CGE="+g);
                        if(!c.status().equals("INFEASIBLE")||g.status().startsWith("FEASIBLE"))throw new AssertionError("Incorrect shortage acceptance");
                    }
                }
                System.out.println("LONG_MAX "+runGtl(cycle(Long.MAX_VALUE)));
            }
            default -> throw new IllegalArgumentException(mode);
        }
        System.out.println("INDEPENDENT_PREFIX_AND_GOAL_CHECKS_PASSED");
    }
}
