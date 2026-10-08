package org.cgse.core;

import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.*;

public final class WorkAccountingProbe {
    static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static PlanningBudget budget(){return new PlanningBudget(0,100_000_000,128L<<20,()->false,System::nanoTime);}
    static String source(boolean noise)throws Exception {
        var b=budget();
        var recipes=List.of(recipe("bad",Map.of("A",2L),Map.of("T",1L)),recipe("good",Map.of("B",1L),Map.of("T",1L)));
        var c=new GraphCompiler<>(recipes);var stock=Map.of("A",1L,"B",1L);
        var compilation=c.begin("T",Set.of(),Map.of(),Set.of(),b);while(!compilation.step()){}
        var graph=compilation.result();compilation.close();
        var model=RecipeCountModel.create(c,"T",1,stock,Map.of(),Set.of(),Set.of(),true,b);
        long first=b.nodes(),foreign=0;int steps=0;Map<String,Integer> result;
        try(var proofs=new OrderProofs<>(model,b);var s=new SourceExplanation<>(proofs,c,"T",Set.of(),Set.of(),graph,Map.of(),b)) {
            while(!s.step()) {if(++steps==1&&noise){b.charge(1_000_000);foreign+=1_000_000;}if(steps>100000)throw new AssertionError();}
            result=s.result();
        }
        if(b.reservedBytes()!=0)throw new AssertionError("source leak "+b.reservedBytes());
        return result+":"+(b.nodes()-first-foreign)+":"+steps;
    }
    static String backward(boolean noise)throws Exception {
        var b=budget();var recipes=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<12;i++)recipes.add(recipe("r"+i,Map.of("p"+i,1L),Map.of("p"+(i+1),1L)));
        long foreign=0;int steps=0;String result;
        try(var s=new BackwardCoverability<>(recipes,Map.of("p0",BigInteger.ONE),Map.of("p12",BigInteger.ONE),Set.of(),List.of(),b,20000)){
            while(!s.step()){if(++steps==1&&noise){b.charge(1_000_000);foreign+=1_000_000;}if(steps>1000000)throw new AssertionError();}
            result=s.result()+":"+(s.witness()==null?"none":s.witness());
        }
        if(b.reservedBytes()!=0)throw new AssertionError("backward leak");
        return result+":"+(b.nodes()-foreign)+":"+steps;
    }
    static void startup() {
        for(int quantity=0;quantity<4;quantity++)for(boolean warm:new boolean[]{false,true}) {
            var recipes=List.of(recipe("too_big",Map.of("A",3L),Map.of("A",4L)),recipe("small",Map.of("A",2L),Map.of("B",1L)),
                    recipe("turnover",Map.of("A",1L),Map.of("A",1L)),recipe("finish",Map.of("B",1L),Map.of("T",1L)));
            var c=new GraphCompiler<>(recipes);var b=budget();
            if(warm)try(var builder=new GraphCatalogIndex.Builder<>(recipes,b)){for(int i=0;i<recipes.size();i++)builder.add(recipes.get(i),i);while(!builder.step()){}c.rememberCatalogIndex(builder.result());}
            try(var s=new MissingStockAnalysis<>(c,"T",Map.of("A",(long)quantity),Set.of(),Set.of(),Set.of(),true,b)) {
                while(!s.step()){}
                if(s.blocked()!=(quantity<2))throw new AssertionError("startup "+quantity+" "+warm);
                if(s.unreachableRecipes().contains("too_big")!=(quantity<3))throw new AssertionError("threshold");
            }
            if(b.reservedBytes()!=0)throw new AssertionError("startup leak");
        }
    }
    public static void main(String[] args)throws Exception {
        String a=source(false),b=source(true),c=backward(false),d=backward(true);
        System.out.println("source plain="+a+" noise="+b);System.out.println("backward equal="+c.equals(d));
        if(args.length==0||!args[0].equals("observe")){
            if(!a.equals(b)||!c.equals(d))throw new AssertionError("foreign work changed local strategy");
            startup();System.out.println("work and quantitative startup checks passed");
        }
    }
}
