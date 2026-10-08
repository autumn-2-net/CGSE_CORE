package org.cgse.core;
import java.util.*;
public class AllocationVisitProbe {
    public static void main(String[] args)throws Exception{
        var b=new PlanningBudget(0,100000,4096,()->false,System::nanoTime);
        var search=new AllocationSearch<>(new GraphCompiler<String>(List.of()),"target",1,Map.of(),Set.of(),Map.of(),true,true,Set.of(),b,0);
        var visit=AllocationSearch.class.getDeclaredMethod("visit",BitSet.class);visit.setAccessible(true);
        for(int n=64;n>=0;n--){
            var sleeping=new BitSet();sleeping.set(0,n);
            if(!(boolean)visit.invoke(search,sleeping))throw new AssertionError("Strictly less restrictive representative must be retained");
            if(b.reservedBytes()>256)throw new AssertionError("Discarded representatives remain memory-charged: "+b.reservedBytes());
        }
        search.discard();if(b.reservedBytes()!=0)throw new AssertionError("Unreleased memory");
        System.out.println("65 increasingly permissive representatives fit in 4 KiB; release returns to zero");
    }
}
