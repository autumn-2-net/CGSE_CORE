package org.cgse.core;

import java.math.BigInteger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class FrontierCacheProbe {
    static int checks, models, feasibleAssignments;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Object field(Object x,String name)throws Exception {Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
    static void set(Object x,String name,Object value)throws Exception {Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value);}
    static Object call(Object x,String name,Class<?>[]types,Object...args)throws Exception {
        Method m=x.getClass().getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(x,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw e;}
    }
    @SuppressWarnings("unchecked") static Deque<IntegerCountBranch<String>> queue(IntegerCountSearch<String>s,String name)throws Exception{return (Deque<IntegerCountBranch<String>>)field(s,name);}
    static Map<?,?> ledger(IntegerCountSearch<String>s)throws Exception{return (Map<?,?>)field(s,"propagationCaches");}
    static long number(Object x,String name)throws Exception{return ((Number)field(x,name)).longValue();}
    static int owners(CountBounds.Seed seed)throws Exception{return ((AtomicInteger)field(seed,"owners")).get();}
    static PlanningBudget budget(long memory){return new PlanningBudget(0,20_000_000,memory,()->false,System::nanoTime);}
    static IntegerCountSearch<String> search(PlanningBudget budget){
        GraphRecipe<String> r=new GraphRecipe<>("produce","produce",List.of(new GraphRecipe.Slot<>("raw",1)),Map.of("target",1L));
        return new IntegerCountSearch<>(new GraphCompiler<>(List.of(r)),"target",1,Map.of("raw",1000L),Map.of(),Set.of(),Set.of(),false,true,budget,System.nanoTime());
    }
    static CountBounds.Seed seed(PlanningBudget b,int variables){
        List<ExactLinearProgram.Constraint>rows=new ArrayList<>();for(int i=0;i<variables;i++)rows.add(row(1000,Map.of(i,BigInteger.ONE)));
        try(var bounds=new CountBounds(variables,rows,b,rows.size())){while(!bounds.step()){}var s=bounds.snapshot();check(s!=null,"seed unavailable");return s;}
    }
    static ExactLinearProgram.Constraint row(long rhs,Map<Integer,BigInteger>terms){return new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(rhs));}
    static void enqueue(IntegerCountSearch<String>s,IntegerCountBranch<String>parent,List<ExactLinearProgram.Constraint>rows)throws Exception{call(s,"enqueue",new Class<?>[]{List.class,IntegerCountBranch.class},rows,parent);}
    @SuppressWarnings("unchecked") static List<IntegerCountBranch<String>> take(IntegerCountSearch<String>s,int width,int resident)throws Exception{return (List<IntegerCountBranch<String>>)call(s,"take",new Class<?>[]{int.class,int.class},width,resident);}
    static void bounded(long memory,int variables,int children)throws Exception{
        PlanningBudget b=budget(memory);CountBounds.Seed snapshot;
        try(var s=search(b)){
            var parent=queue(s,"pending").getFirst();parent.sharedBounds=snapshot=seed(b,variables);
            List<ExactLinearProgram.Constraint> assumptions=List.of(row(37,Map.of(0,BigInteger.ONE)));
            for(int i=0;i<children;i++)enqueue(s,parent,assumptions);
            long expected=Math.min(128,Math.min(children,number(s,"propagationCacheLimit")/snapshot.retainedBytes()));
            check(ledger(s).size()==expected,"cache quota "+expected+" vs "+ledger(s).size());
            check(queue(s,"pending").size()==children+1,"cache rejection dropped branch");
            check(!(boolean)field(s,"unresolved"),"cache rejection made unresolved");
            check(number(s,"propagationCacheBytes")==expected*snapshot.retainedBytes(),"byte account");
            check(owners(snapshot)==expected+1,"sharing owner count");
            for(var child:queue(s,"pending"))if(child!=parent)check(child.current.equals(assumptions),"lost assumptions");
            // A full resident set must leave skipped cache entries registered.
            parent.initialized=true;var selected=take(s,1,1);check(selected.equals(List.of(parent)),"changed take order");
            queue(s,"pending").addLast(parent);check(ledger(s).size()==expected,"skipped entry evicted");
            parent.initialized=false;
            var first=queue(s,"pending").removeFirst();queue(s,"deferred").add(first);set(s,"rounds",3);
            selected=take(s,1,1000);check(selected.equals(List.of(first)),"deferred priority changed");
            check(ledger(s).size()==Math.max(0,expected-1),"deferred cache not removed");
            check(owners(snapshot)==expected+1,"take closed live seed");
            queue(s,"pending").addFirst(first);
            selected=take(s,Math.min(7,children),1000);set(s,"dispatched",selected);
            long still=ledger(s).size();check(number(s,"propagationCacheBytes")==still*snapshot.retainedBytes(),"retake double-untracked");
            s.close();s.close();check(ledger(s).isEmpty()&&number(s,"propagationCacheBytes")==0,"close ledger");
            check(owners(snapshot)==0,"close shared owners "+owners(snapshot));
        }
        check(b.reservedBytes()==0,"bounded leak "+b.reservedBytes());
    }
    static void metadataDeclined()throws Exception{
        PlanningBudget b=budget(1L<<20);
        try(var s=search(b)){
            var parent=queue(s,"pending").getFirst();parent.sharedBounds=seed(b,1);
            long filler=b.availableBytes()-512;check(b.tryReserve(filler),"filler");
            enqueue(s,parent,List.of());
            check(queue(s,"pending").size()==2,"metadata rejection dropped branch");
            check(queue(s,"pending").getLast().inheritedBounds==null,"metadata rejection retained seed");
            check(queue(s,"pending").getLast().state==IntegerCountBranch.State.OPEN,"metadata rejection changed state");
            check(!(boolean)field(s,"unresolved")&&ledger(s).isEmpty(),"metadata rejection unresolved");
            b.release(filler);
        }check(b.reservedBytes()==0,"metadata-decline leak");
    }
    static void cancellation()throws Exception{
        for(int at=1;at<=3;at++){
            AtomicInteger polls=new AtomicInteger();int stop=at;boolean[]armed={false};
            PlanningBudget b=new PlanningBudget(0,20_000_000,32L<<20,()->armed[0]&&polls.incrementAndGet()>=stop,System::nanoTime);
            CountBounds.Seed snapshot;
            try(var s=search(b)){
                var parent=queue(s,"pending").getFirst();parent.sharedBounds=snapshot=seed(b,1);long before=b.reservedBytes();armed[0]=true;
                boolean cancelled=false;try{enqueue(s,parent,List.of());}catch(CancellationException expected){cancelled=true;}
                check(cancelled==(at<=2),"cancel boundary "+at);
                if(cancelled){check(b.reservedBytes()==before,"enqueue exception leaked");check(queue(s,"pending").size()==1,"cancel inserted child");check(owners(snapshot)==1,"cancel retained seed");}
                s.close();check(owners(snapshot)==0,"cancel cleanup ownership");
            }check(b.reservedBytes()==0,"cancellation leak");
        }
    }
    static void independentSeeds()throws Exception{
        PlanningBudget b=budget(1L<<20);List<CountBounds.Seed> snapshots=new ArrayList<>();
        try(var s=search(b)){
            var parent=queue(s,"pending").getFirst();
            for(int i=0;i<40;i++){
                if(parent.sharedBounds!=null)parent.sharedBounds.close();
                parent.sharedBounds=seed(b,128);snapshots.add(parent.sharedBounds);
                enqueue(s,parent,List.of());enqueue(s,parent,List.of());
                check(number(s,"propagationCacheBytes")<=number(s,"propagationCacheLimit"),"independent cache quota");
                check(queue(s,"pending").size()==3+2*i,"unique seed rejection lost branch");
            }
            check(!(boolean)field(s,"unresolved"),"unique seed cache cutoff unresolved");
        }
        for(var seed:snapshots)check(owners(seed)==0,"independent seed owner leak");
        check(b.reservedBytes()==0,"independent seed memory leak");
    }
    static void asynchronousClose()throws Exception{
        PlanningBudget b=budget(32L<<20);CountBounds.Seed snapshot;
        try(var s=search(b)){
            var parent=queue(s,"pending").getFirst();parent.sharedBounds=snapshot=seed(b,1);
            for(int i=0;i<16;i++)enqueue(s,parent,List.of());
            var selected=take(s,2,100);set(s,"dispatched",selected);
            CompletableFuture<List<IntegerCountBranch<String>>> future=new CompletableFuture<>();set(s,"running",future);
            long before=b.reservedBytes();s.close();check(b.reservedBytes()==before,"closed active worker references early");
            future.complete(selected);check(b.reservedBytes()==0,"delayed close leak");
            check(owners(snapshot)==0&&ledger(s).isEmpty(),"async cache cleanup");s.close();
        }
    }
    static boolean satisfies(List<ExactLinearProgram.Constraint> rows,BigInteger[]x){for(var r:rows){BigInteger v=BigInteger.ZERO;for(var t:r.terms().entrySet())v=v.add(t.getValue().multiply(x[t.getKey()]));if(v.compareTo(r.upper())>0)return false;}return true;}
    static void verifyPropagation(CountBounds c,List<ExactLinearProgram.Constraint>rows,int n){
        int count=0;for(int code=0;code<(1<<(2*n));code++){int v=code;BigInteger[]x=new BigInteger[n];for(int i=0;i<n;i++){x[i]=BigInteger.valueOf(v&3);v>>=2;}if(!satisfies(rows,x))continue;count++;feasibleAssignments++;
            check(!c.blocked(),"false conflict");for(int i=0;i<n;i++){check(x[i].compareTo(c.lowerBounds()[i])>=0,"lost lower solution");check(c.upperBounds()[i]==null||x[i].compareTo(c.upperBounds()[i])<=0,"lost upper solution");}}
        if(c.blocked())check(count==0,"false infeasible");
    }
    static void coldWarm()throws Exception{
        Random random=new Random(3770231);
        for(int sample=0;sample<700;sample++){
            int n=2+random.nextInt(3);List<ExactLinearProgram.Constraint>rows=new ArrayList<>();for(int i=0;i<n;i++)rows.add(row(3,Map.of(i,BigInteger.ONE)));
            for(int r=0;r<2+random.nextInt(5);r++){Map<Integer,BigInteger> terms=new LinkedHashMap<>();for(int i=0;i<n;i++){int v=random.nextInt(7)-3;if(v!=0)terms.put(i,BigInteger.valueOf(v));}rows.add(row(random.nextInt(15)-4,terms));}
            PlanningBudget b=budget(32L<<20);
            try(var original=new CountBounds(n,rows,b,rows.size())){
                while(!original.step()){}var snapshot=original.snapshot();
                if(snapshot!=null)try(snapshot){
                    int globals=rows.size();var child=new ArrayList<>(rows);int variable=random.nextInt(n),bound=random.nextInt(4);boolean lower=random.nextBoolean();child.add(row(lower?-bound:bound,Map.of(variable,lower?BigInteger.ONE.negate():BigInteger.ONE)));
                    try(var cold=new CountBounds(n,child,b,globals);var warm=new CountBounds(n,child,b,globals,snapshot)){
                        while(!cold.step()){}while(!warm.step()){}verifyPropagation(cold,child,n);verifyPropagation(warm,child,n);models++;
                        check(cold.blocked()==warm.blocked(),"cold/warm conflict mismatch");
                    }
                }else verifyPropagation(original,rows,n);
            }check(b.reservedBytes()==0,"propagation leak");
        }
    }
    public static void main(String[]args)throws Exception{
        bounded(32L<<20,1,160);
        for(int mib=1;mib<=8;mib++)bounded((long)mib<<20,128,160);
        metadataDeclined();cancellation();independentSeeds();asynchronousClose();coldWarm();
        System.out.println("PASS checks="+checks+" cold_warm_models="+models+" feasible_assignments="+feasibleAssignments+" memory_cases=9 cancel_positions=3 no_leaks=true");
    }
}
