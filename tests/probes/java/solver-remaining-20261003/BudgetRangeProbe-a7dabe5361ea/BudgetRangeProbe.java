package org.cgse.core;
import java.util.*;
public final class BudgetRangeProbe {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static PlanningBudget make(long limit){return new PlanningBudget(0,limit,64L<<20,()->false,System::nanoTime);}
    public static void main(String[]args){
        long limit=PlanningBudget.parallelWorkLimit(Integer.MAX_VALUE,4,true);
        check(limit==4294967294L,"int expansion");
        var b=make(limit);long before=b.threadWork();b.charge(limit-1);
        check(b.nodes()==limit-1&&b.remainingWork()==1,"large cumulative work");
        check(b.threadWork()-before==limit-1,"large thread work");b.check();
        check(b.nodes()==limit&&b.remainingWork()==0,"exact boundary");
        try{b.check();throw new AssertionError("missing exhaustion");}catch(PlanningBudget.Exhausted expected){}
        check(b.nodes()==limit+1&&b.remainingWork()==0,"overshoot must stay nonnegative");
        System.out.println("INT_MAX_PASS limit="+limit+" nodes="+b.nodes());
        var recipe=new GraphRecipe<String>("r","r",List.of(new GraphRecipe.Slot<>("A",1)),Map.of("T",1L));
        b=make(limit);b.charge((long)Integer.MAX_VALUE+12345);
        var compiler=new GraphCompiler<>(List.of(recipe));
        try(var search=new IntegerCountSearch<>(compiler,"T",5,Map.of("A",5L),Map.of(),Set.of(),Set.of(),false,false,b,System.nanoTime())){
            while(!search.step()){}var result=search.result();check(result!=null&&result.feasible(),"integer path after INT_MAX");
        }check(b.reservedBytes()==0,"integer path memory");
        System.out.println("INTEGER_SEARCH_PASS nodes="+b.nodes()+" remaining="+b.remainingWork());
        b=make(limit);b.charge((long)Integer.MAX_VALUE+12345);
        var plan=new GraphPlanner<>(new GraphCompiler<>(List.of(recipe))).plan("T",5,Map.of("A",5L),false,false,b);
        check(plan.feasible(),"graph path after INT_MAX");
        var normal=make(limit);new GraphPlanner<>(new GraphCompiler<>(List.of(recipe))).plan("T",5,Map.of("A",5L),false,false,normal);
        check(b.reservedBytes()==normal.reservedBytes(),"graph compiled retention changed");
        System.out.println("GRAPH_PASS nodes="+b.nodes()+" remaining="+b.remainingWork()+" compiled_retention="+b.reservedBytes());
        // A separate extreme API diagnostic, not reachable from the int config.
        b=make(Long.MAX_VALUE);long bulk=Long.MAX_VALUE/PlanningBudget.WORK_SCALE;
        for(int i=0;i<4;i++){
            String state="accepted";try{if(i==1)b.check();else b.charge(bulk);}catch(PlanningBudget.Exhausted e){state=e.limit().name();}
            System.out.println("LONG_API iteration="+i+" state="+state+" nodes="+b.nodes()+" remaining="+b.remainingWork());
        }
        System.out.println("PASS checked_int_chain_assertions="+checks);
    }
}
