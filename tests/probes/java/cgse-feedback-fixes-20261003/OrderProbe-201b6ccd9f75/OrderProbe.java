package org.cgse.core;
import java.util.*;
public final class OrderProbe {
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static List<GraphRecipe<String>> recipes(boolean deadFirst){
        var rs=new ArrayList<GraphRecipe<String>>();
        rs.add(recipe("main",Map.of("A",2L,"B",3L),Map.of("A",1L,"C",3L)));
        rs.add(recipe("recycle",Map.of("C",1L),Map.of("A",1L)));
        for(int i=0;i<8;i++)rs.add(recipe("dead"+i,Map.of("X"+i,1L),Map.of("C",1L)));
        if(deadFirst)Collections.rotate(rs,-2);return rs;
    }
    static GraphPlan<String> solve(List<GraphRecipe<String>> rs,long amount,Map<String,Long> stock,boolean force){
        var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);
        var plan=new GraphPlanner<>(new GraphCompiler<>(rs)).plan("C",amount,stock,false,force,budget);
        System.out.println("force="+force+" amount="+amount+" first="+rs.get(0).id()+" result="+plan.result()+" missing="+plan.missing()+" counts="+plan.patternTimes()+" initial="+plan.initial());
        System.out.println("diagnostics="+budget.diagnostics());return plan;
    }
    public static void main(String[]args){for(boolean force:new boolean[]{false,true})for(boolean dead:new boolean[]{false,true})solve(recipes(dead),3,Map.of("A",1L,"B",3L,"C",1L),force);}
}
