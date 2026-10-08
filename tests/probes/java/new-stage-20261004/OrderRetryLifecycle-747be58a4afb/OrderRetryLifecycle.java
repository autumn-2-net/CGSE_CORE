package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.*;

public final class OrderRetryLifecycle {
    static long checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var held=IntegerCountBranch.class.getDeclaredField("witnessSchedule");held.setAccessible(true);
        for(int sample=0;sample<400;sample++){
            var cancel=new AtomicBoolean();var clock=new AtomicLong();
            var budget=new PlanningBudget(1000,4_000_000,128L<<20,cancel::get,clock::get);
            var grow=OrderRetryRegression.recipe("grow",Map.of("A",2L,"B",3L),Map.of("A",1L,"C",3L));
            var back=OrderRetryRegression.recipe("back",Map.of("C",2L),Map.of("A",1L,"C",1L));
            var recipes=sample%2==0?List.of(back,grow):List.of(grow,back);
            var stock=Map.of("A",2L,"B",3L,"C",2L);var seeds=Map.of("A",2L);
            try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"C",4,stock,seeds,Set.of(),Set.of(),true,budget);
                var execution=new CountExecution<>(model,budget);
                var branch=new IntegerCountBranch<>(model,execution,"C",4,stock,seeds,Set.of(),true,true,budget,0,List.of())){
                int steps=0;while(held.get(branch)==null&&branch.state==IntegerCountBranch.State.OPEN){branch.run(1,List.of(),List.of(),List.of(),null,()->false);check(++steps<10000,"witness not reached");}
                check(held.get(branch)!=null,"no held witness");
                long extra=0;
                try{
                    switch(sample%4){case 0->cancel.set(true);case 1->clock.set(2_000_000_000L);case 2->budget.charge(budget.remainingWork());case 3->{extra=budget.availableBytes();budget.reserve(extra);}}
                    int rounds=0;
                    try{while(branch.state==IntegerCountBranch.State.OPEN&&rounds++<1000)branch.run(1,List.of(),List.of(),List.of(),null,()->false);}catch(CancellationException expected){check(sample%4==0,"unexpected cancel");}
                    check(sample%4==0||branch.state!=IntegerCountBranch.State.OPEN,"limit not observed");
                }finally{budget.release(extra);}
            }
            check(budget.reservedBytes()==0,"held witness leak "+sample+"="+budget.reservedBytes());
        }
        var b=OrderRetryRegression.budget();
        try(var model=RecipeCountModel.forShell(List.of(OrderRetryRegression.recipe("r",Map.of("A",1L),Map.of("B",1L))),Map.of(),Map.of("A",1L),Set.of(),b)){
            long before=b.reservedBytes();try(var schedule=new CountSchedule<>(model,new BigInteger[]{BigInteger.ONE},b)){while(!schedule.step()){}check(b.reservedBytes()==before,"non-pool caller lost auto-close");}
        }
        check(b.reservedBytes()==0,"plain caller leak");String result="cases=400, assertions="+checks;Files.writeString(Path.of(args[0],"order-retry-lifecycle.txt"),result);System.out.println(result);
    }
}
