package org.cgse.core;
public final class BudgetMeasure {
    public static void main(String[] args)throws Exception {
        var f=LocalContrastReview.file(args[0]);
        for(long n:new long[]{Long.MAX_VALUE,1}){
            var b=new PlanningBudget(30000,10_000_000,256L<<20,()->false,System::nanoTime);
            var w=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).begin(f.target(),n,f.stock(),true,true,b).catalysts(CatalystPolicy.MINIMAL);
            while(!w.step()){}
            System.out.println("amount="+n+" result="+w.result().result()+" checks="+b.nodes()+" trace="+b.diagnostics());
        }
    }
}
