package org.cgse.core;

import java.lang.reflect.*;
import java.util.*;

public final class SourceChoicesAudit {
    record Key(int id) { public int hashCode() { return id % 7; } }
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static void compare(Map<Key,Integer> a, Map<Key,Integer> b) {
        check(a.size()==b.size(), "size"); check(a.hashCode()==b.hashCode(), "hash");
        check(a.equals(b)&&b.equals(a), "equals"); check(Map.copyOf(a).equals(b), "copy");
        for(int i=0;i<80;i++)check(Objects.equals(a.get(new Key(i)),b.get(new Key(i))),"get");
        if (!a.isEmpty()) {
            try { a.entrySet().iterator().next().setValue(99); throw new AssertionError("entry mutation"); } catch(UnsupportedOperationException good) { checks++; }
            try { a.remove(a.keySet().iterator().next()); throw new AssertionError("remove mutation"); } catch(UnsupportedOperationException good) { checks++; }
        }
        try { a.put(new Key(100),100); throw new AssertionError("put mutation"); } catch(UnsupportedOperationException good) { checks++; }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var random = new Random(173);
        List<Map<Key,Integer>> actual=new ArrayList<>(), expected=new ArrayList<>();
        Map<Key,Integer> seed=new LinkedHashMap<>();for(int i=0;i<50;i++)seed.put(new Key(i),i);
        actual.add(GraphSourceChoices.retain(seed));expected.add(Map.copyOf(seed));seed.clear();
        for(int i=0;i<6000;i++) {
            int parent=i%3==0?random.nextInt(actual.size()):actual.size()-1;
            Key key=new Key(random.nextInt(80));int value=random.nextInt(8);
            var changed=new HashMap<>(expected.get(parent));if(value==0)changed.remove(key);else changed.put(key,value);
            actual.add(GraphSourceChoices.changed(actual.get(parent),key,value));expected.add(Map.copyOf(changed));
            compare(actual.get(actual.size()-1),changed);
            if(i%10==0) {int old=random.nextInt(actual.size());compare(actual.get(old),expected.get(old));}
        }
        for(int i=0;i<actual.size();i+=19)compare(actual.get(i),expected.get(i));
        Map<Key,Integer> chain=actual.get(0), reference=new HashMap<>(expected.get(0));
        List<Map<Key,Integer>> history=new ArrayList<>(), refs=new ArrayList<>();
        for(int i=0;i<300;i++) {
            Key key=new Key(i%80);int value=i%9;
            if(value==0)reference.remove(key);else reference.put(key,value);
            chain=GraphSourceChoices.changed(chain,key,value);
            history.add(chain);refs.add(Map.copyOf(reference));compare(chain,reference);
        }
        for(int i=0;i<history.size();i++)compare(history.get(i),refs.get(i));
        for(int i=0;i<300;i++) {
            var original=history.get(i);var equivalent=GraphSourceChoices.retain(refs.get(i));
            check(original.equals(equivalent)&&equivalent.equals(original),"different root equal");
        }
        var collisionSeed=new HashMap<Key,Integer>();for(int i=0;i<4000;i++)collisionSeed.put(new Key(i),1);
        var collisionRoot=GraphSourceChoices.retain(collisionSeed);
        for(int i=0;i<300;i++) {
            var direct=GraphSourceChoices.changed(collisionRoot,new Key(i),2);
            var indirect=GraphSourceChoices.changed(GraphSourceChoices.changed(direct,new Key(i+500),3),new Key(i+500),1);
            check(direct.equals(indirect)&&indirect.equals(direct),"common ancestor equal");
            check(direct.hashCode()==indirect.hashCode(),"common ancestor hash");
        }
        var collisions=new HashSet<Map<Key,Integer>>();
        long collisionStart=System.nanoTime();
        for(int i=0;i<4000;i++)check(collisions.add(GraphSourceChoices.changed(collisionRoot,new Key(i),2)),"distinct collision");
        for(int i=0;i<4000;i++)check(collisions.contains(GraphSourceChoices.changed(collisionRoot,new Key(i),2)),"same collision");
        System.out.println("COLLISION_MS="+(System.nanoTime()-collisionStart)/1000000);
        Method queue=GraphPlanningWork.class.getDeclaredMethod("queueChoice",Map.class,boolean.class);queue.setAccessible(true);
        Field choices=GraphPlanningWork.class.getDeclaredField("choices");choices.setAccessible(true);
        Field seenField=GraphPlanningWork.class.getDeclaredField("seen");seenField.setAccessible(true);
        Field frontier=GraphPlanningWork.class.getDeclaredField("frontierMemory");frontier.setAccessible(true);
        var budget=new PlanningBudget(0,1000000,8L<<20,()->false,System::nanoTime);budget.reserve(128);
        var work=new GraphPlanningWork<Key>(new GraphCompiler<>(List.of(),Map.of()),new Key(0),1,Map.of(),true,true,budget);
        Map<Key,Integer> wide=new HashMap<>();for(int i=0;i<700;i++)wide.put(new Key(i),1);
        var root=GraphSourceChoices.retain(wide);
        queue.invoke(work,root,false);
        ((Set<Map<Key,Integer>>)seenField.get(work)).add(root);choices.set(work,root);
        for(int i=0;i<4000;i++)queue.invoke(work,GraphSourceChoices.changed(root,new Key(699),i+2),false);
        long retained=frontier.getLong(work);
        check(retained==root.retainedBytes()+4000L*288,"constant sibling charge "+retained);
        check(retained<2L<<20,"bounded sibling memory");
        work.close();check(budget.reservedBytes()==128,"close lease");work.close();check(budget.reservedBytes()==128,"double close");budget.release(128);
        for(int i=0;i<2;i++) {
            var small=new PlanningBudget(0,100000,128,()->false,System::nanoTime);
            var child=new GraphPlanningWork<Key>(new GraphCompiler<>(List.of(),Map.of()),new Key(0),1,Map.of(),true,true,small);
            queue.invoke(child,root,false);check(small.reservedBytes()==0,"declined lease");
            if(i==1) {
                small.cancel();
                try {queue.invoke(child,root,false);throw new AssertionError("cancelled queue");}
                catch(InvocationTargetException expectedCancel) {check(expectedCancel.getCause() instanceof java.util.concurrent.CancellationException,"cancel signal");}
            }
            child.close();check(small.reservedBytes()==0,"decline close");
        }
        System.out.println("SOURCE_CHOICES_AUDIT checks="+checks+" sibling_bytes="+retained);
    }
}
