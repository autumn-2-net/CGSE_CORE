package org.cgse.core;
import java.util.*;
public final class BudgetSmokeProbe {
    public static void main(String[]args){
        for(int round=0;round<10;round++){
            var b=new PlanningBudget(0,100_000_000,1<<20,()->false,System::nanoTime);long begin=b.threadWork(),start=System.nanoTime();
            for(int i=0;i<1_000_000;i++){if((i&3)==0)b.check();else b.operation(PlanningBudget.Operation.INTEGER,i&1023);}
            long nanos=System.nanoTime()-start,delta=b.threadWork()-begin;
            if(delta!=b.nodes())throw new AssertionError("charge change");
            System.out.println(round+","+nanos+","+b.nodes());
        }
    }
}
