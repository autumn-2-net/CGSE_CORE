package org.cgse.core;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class ForkCopyAudit {
    static int checks;
    static void ok(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static Field field(String name) throws Exception { var field=GraphStockViewWork.class.getDeclaredField(name); field.setAccessible(true); return field; }
    static final Method fork;
    static { try { fork=GraphStockViewWork.class.getDeclaredMethod("forkConsumerRepair", GraphPlan.class); fork.setAccessible(true); } catch(Exception e) { throw new RuntimeException(e); } }

    static void run(int leaves, int excluded, int groups, int tried, int action) throws Exception {
        var stock = new LinkedHashMap<String,Long>();
        for(int i=0;i<leaves;i++) stock.put("s"+i,1L);
        var recipe = new GraphRecipe<>("r", "r", List.of(new GraphRecipe.Slot<>("raw",1)), Map.of("target",1L));
        var compiler = new GraphCompiler<>(List.of(recipe));
        var graph = new GraphCompiler.Compiled<>(Map.of("r",recipe), Map.of("target",recipe), List.of(new GraphCompiler.Region<>(List.of(recipe),false)));
        var budget = new PlanningBudget(0, action==3 ? 8 : 10_000_000, 16L<<20, ()->false, System::nanoTime);
        budget.reserve(128);
        var parent = new GraphStockViewWork<>(compiler,"target",1,stock,Set.of(),Map.of(),Set.of(),true,true,CatalystPolicy.MINIMAL,budget,0,2,()->null);
        @SuppressWarnings("unchecked") Set<String> leafSet=(Set<String>)field("leaves").get(parent);
        leafSet.addAll(stock.keySet());
        @SuppressWarnings("unchecked") Set<String> excludeSet=(Set<String>)field("excluded").get(parent);
        for(int i=0;i<excluded;i++) excludeSet.add("e"+i);
        @SuppressWarnings("unchecked") Map<String,Set<String>> sourceMap=(Map<String,Set<String>>)field("triedSources").get(parent);
        for(int i=0;i<groups;i++) { var values=new HashSet<String>(); for(int j=0;j<tried;j++) values.add("p"+j); sourceMap.put("k"+i,values); }
        field("graph").set(parent,graph);
        var candidate = new GraphPlan<>("target",1,true,new PlanStep.Sequence(List.of()),Map.of("r",recipe),Map.of(),Map.of(),Map.of("raw",BigInteger.ONE),GraphPlan.Result.MISSING_INPUT,0,0);
        GraphStockViewWork<?> child=null; long pressure=0; boolean stopped=false;
        try {
            if(action==1) { pressure=budget.availableBytes()-1; budget.reserve(pressure); }
            if(action==2) budget.cancel();
            long before=budget.nodes();
            try { child=(GraphStockViewWork<?>)fork.invoke(parent,candidate); }
            catch(InvocationTargetException e) {
                Throwable cause=e.getCause();
                if(action==2 && cause instanceof CancellationException || action==3 && cause instanceof PlanningBudget.Exhausted) stopped=true;
                else throw e;
            }
            if(action==0) {
                long expected = groups + 1 + ((long)leaves + excluded + (long)groups*tried + 3)/4;
                ok(child!=null,"copy declined");
                ok(budget.nodes()-before==expected,"wrong work: expected="+expected+" actual="+(budget.nodes()-before));
                ok(field("graph").get(child)==graph,"immutable graph needlessly copied");
                ok(field("leaves").get(child).equals(leafSet)&&field("leaves").get(child)!=leafSet,"leaves not independently copied");
                @SuppressWarnings("unchecked") Map<String,Set<String>> cloned=(Map<String,Set<String>>)field("triedSources").get(child);
                ok(cloned.equals(sourceMap),"tried source values lost");
                for(String key:sourceMap.keySet()) ok(cloned.get(key)!=sourceMap.get(key),"mutable tried source set shared");
                parent.close();
                ok(budget.reservedBytes()>128,"parent released transferred child's lease");
            } else if(action==1) ok(child==null,"memory pressure ignored");
            else ok(stopped,"stop not propagated");
        } finally {
            parent.close(); parent.close();
            if(child!=null) { child.close(); child.close(); }
            budget.release(pressure);
            ok(budget.reservedBytes()==128,"partial fork lease leaked");
            budget.release(128);
        }
    }

    public static void main(String[] args) throws Exception {
        for(int leaves:new int[]{0,1,3,4,5,1024,2049}) for(int groups:new int[]{0,1,3}) run(leaves,5,groups,17,0);
        for(int action=1;action<=3;action++) run(2049,9,3,17,action);
        System.out.println("checks="+checks+"; exact_copy_work=true; partial_failure_leases=0");
    }
}
