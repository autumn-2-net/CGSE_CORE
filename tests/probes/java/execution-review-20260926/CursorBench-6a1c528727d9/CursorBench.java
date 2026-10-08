package org.cgse.core;
import java.util.*;

public class CursorBench {
    static long run(PlanStep root,boolean legacy,boolean remaining){
        long start=System.nanoTime();
        if(legacy){var c=new LegacyPlanCursor(root);c.current();if(remaining)c.remainingCountsExact();}
        else{var c=new PlanCursor(root);c.current();if(remaining)c.remainingCountsExact();}
        return System.nanoTime()-start;
    }
    static void compare(String name,PlanStep root,boolean count){
        long[][] times=new long[2][11];
        for(int i=0;i<11;i++)for(int j=0;j<2;j++){int version=(i+j)%2;times[version][i]=run(root,version==0,count);}
        for(int version=0;version<2;version++){Arrays.sort(times[version]);System.out.printf(Locale.ROOT,"%s %s median_ms=%.4f%n",name,version==0?"old":"new",times[version][5]/1e6);}
    }
    public static void main(String[]args){
        PlanStep small=new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        for(int i=0;i<8;i++)small=new PlanStep.Sequence(List.of(small,small));
        for(int i=0;i<32;i++){run(small,true,true);run(small,false,true);}
        PlanStep mixed=new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        for(int i=0;i<512;i++)mixed=new PlanStep.Sequence(List.of(mixed,mixed));
        compare("mixed512_create_current_counts",mixed,true);
        PlanStep empty=new PlanStep.Sequence(List.of());for(int i=0;i<24;i++)empty=new PlanStep.Sequence(List.of(empty,empty));
        compare("shared_empty24_create",empty,false);
    }
}
