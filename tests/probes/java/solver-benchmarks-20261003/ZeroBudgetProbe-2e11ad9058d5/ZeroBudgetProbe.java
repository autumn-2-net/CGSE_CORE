package org.cgse.core;
public final class ZeroBudgetProbe {
    public static void main(String[]args){var b=new PlanningBudget(0,8_000_000,()->false);try(var s=new CountLcg(LcgLifecycleProbe.ROWS,LcgLifecycleProbe.LO,LcgLifecycleProbe.HI,b,1024)){
        while(!s.step()){}if(!s.paused())throw new AssertionError("expected retained search");b.charge(b.remainingWork());long before=b.nodes();
        try{for(int i=0;i<3;i++){s.resume(1024);if(!s.step()||!s.paused())throw new AssertionError("not paused");}System.out.println("OBSERVED zero-work repeated pause; nodes before="+before+" after="+b.nodes());}
        catch(PlanningBudget.Exhausted expected){System.out.println("PASS exhausted continuation throws "+expected.limit());}
    }if(b.reservedBytes()!=0)throw new AssertionError("leak");}
}
