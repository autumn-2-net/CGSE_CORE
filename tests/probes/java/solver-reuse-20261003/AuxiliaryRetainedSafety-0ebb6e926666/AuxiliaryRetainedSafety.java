package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local lifecycle regression. Production mathematical/scheduling code remains unchanged. */
public final class AuxiliaryRetainedSafety {
    static long assertions, pauses, resumed, checkedProofs, quotaStops, cancelled, fairTurns;
    static void check(boolean value,String text){assertions++;if(!value)throw new AssertionError(text);}
    static Field field(Class<?> c,String name){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new RuntimeException(e);}}
    static Object get(Object o,String name){try{return field(o.getClass(),name).get(o);}catch(Exception e){throw new RuntimeException(e);}}
    static void set(Object o,String name,Object value){try{field(o.getClass(),name).set(o,value);}catch(Exception e){throw new RuntimeException(e);}}
    static Object invoke(Object o,String name,Class<?>[] types,Object...args){try{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof RuntimeException r)throw r;if(t instanceof Error x)throw x;throw new RuntimeException(t);}catch(Exception e){throw new RuntimeException(e);}}
    static List<GraphRecipe<String>> recipes(int n){var r=new ArrayList<GraphRecipe<String>>();for(int i=0;i<n;i++)r.add(new GraphRecipe<>("r"+i,"r"+i,List.of(new GraphRecipe.Slot<>("token"+i,1)),Map.of("T",1L)));return r;}
    static Map<String,Long> stock(int n){var s=new LinkedHashMap<String,Long>();for(int i=0;i<n;i++)s.put("token"+i,1L);return s;}
    static List<ExactLinearProgram.Constraint> pigeon(int p,int h){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int i=0;i<p;i++){var t=new LinkedHashMap<Integer,BigInteger>();for(int j=0;j<h;j++)t.put(i*h+j,BigInteger.ONE.negate());rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE.negate()));}
        for(int j=0;j<h;j++){var t=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<p;i++)t.put(i*h+j,BigInteger.ONE);rows.add(new ExactLinearProgram.Constraint(t,BigInteger.ONE));}
        return rows;
    }
    static IntegerCountBranch<String> prepared(RecipeCountModel<String> model,CountExecution<String> execution,
            Map<String,Long> stock,List<ExactLinearProgram.Constraint> restrictions,PlanningBudget budget,int mode){
        var b=new IntegerCountBranch<>(model,execution,"T",1,stock,Map.of(),Set.of(),false,false,budget,System.nanoTime(),restrictions);
        try {
            int n=model.recipes.size();b.auxiliaryMode=mode;b.partitioned=true;b.initialized=b.compiled=true;
            b.lower=new BigInteger[n];b.upper=new BigInteger[n];Arrays.fill(b.lower,BigInteger.ZERO);Arrays.fill(b.upper,BigInteger.ONE);
            b.linearConstraints=new ArrayList<>(model.constraints);b.linearConstraints.addAll(restrictions);
            b.reduction=new CountReduction(b.linearConstraints,b.lower,b.upper,budget);
            while(!b.reduction.step()){}
            b.auxiliaryLcg=new CountLcg(b.reduction.rows(),b.reduction.lower(),b.reduction.upper(),budget,1024,true);
            return b;
        } catch(RuntimeException|Error error){b.close();throw error;}
    }
    static void runUntilYield(IntegerCountBranch<String> branch){
        for(int i=0;i<100000 && branch.state==IntegerCountBranch.State.OPEN;i++)branch.run(4096,List.of(),List.of(),List.of(),null,()->false);
        check(branch.state!=IntegerCountBranch.State.OPEN,"run did not yield");
    }
    static void proof(CountProof.Certificate c,boolean closed){
        check(c!=null,"missing certificate");check(c.closed()==closed,"incorrect proof closure");
        var verdict=CountProof.verify(c,20_000_000);check(verdict==CountProof.Verdict.VERIFIED,"invalid proof: "+verdict);checkedProofs++;
    }
    static void retained(int p,int h,int mode){
        int n=p*h;var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);var stock=stock(n);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes(n)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,budget);
            var execution=new CountExecution<>(model,budget);
            var b=prepared(model,execution,stock,pigeon(p,h),budget,mode)) {
            CountLcg solver=b.auxiliaryLcg;CountReduction reduction=b.reduction;
            runUntilYield(b);
            while(b.state==IntegerCountBranch.State.UNRESOLVED && b.limit==null && b.auxiliaryLcg!=null){
                check(b.counts==null,"partial count vector escaped");check(!solver.infeasible(),"pause became infeasibility");
                check(b.auxiliaryLcg==solver && b.reduction==reduction,"retained identity changed");
                if(solver.paused()){proof(solver.certificate(),false);pauses++;}
                long memory=budget.reservedBytes(),before=budget.nodes(),spent=b.auxiliaryWork;
                check(b.resume(),"resumable auxiliary declined");resumed++;
                check(b.state==IntegerCountBranch.State.OPEN && !solver.paused(),"did not reopen");
                check(b.auxiliaryUntil==spent+Math.min(262144,budget.remainingWork()),"quota not renewed from cumulative work");
                check(budget.nodes()==before && budget.reservedBytes()==memory,"resume refunded work or rebuilt memory");
                check(solver.certificate()==null,"stale open certificate retained after resume");
                runUntilYield(b);
            }
            if(b.limit!=null)throw b.limit;
            if(p>h){check(b.state==IntegerCountBranch.State.DEAD,"UNSAT model not proved: "+b.state);check(solver.infeasible(),"UNSAT scope lost");proof(solver.certificate(),true);}
            else{check(b.state==IntegerCountBranch.State.FOUND,"SAT model not scheduled: "+b.state);PlanVerifier.verifyRuntimeInventory(b.plan);}
            check(b.auxiliaryLcg==null,"finished solver not disposed");
            System.out.println("RETAINED p="+p+" h="+h+" mode="+mode+" result="+b.state+" work="+budget.nodes()+" pauses="+pauses);
        } finally {check(budget.reservedBytes()==0,"retained leak: "+budget.reservedBytes());}
    }
    static void boundary(int stop){
        var flag=new AtomicBoolean(false);long cap=2_000_000;var budget=new PlanningBudget(0,cap,128L<<20,flag::get,System::nanoTime);var stock=stock(20);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes(20)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,budget);
            var execution=new CountExecution<>(model,budget);
            var b=prepared(model,execution,stock,pigeon(5,4),budget,2)) {
            CountLcg solver=b.auxiliaryLcg;
            runUntilYield(b);check(solver.paused(),"boundary did not obtain real solver pause");proof(solver.certificate(),false);
            if(stop==0){
                check(b.resume(),"first resume declined");
                // Isolate the outer branch cap from the solver's independent quota.
                b.auxiliaryUntil=b.auxiliaryWork;long before=budget.nodes();
                b.run(4096,List.of(),List.of(),List.of(),null,()->false);
                check(b.state==IntegerCountBranch.State.UNRESOLVED && !solver.paused(),"outer cap changed solver state");
                check(budget.nodes()==before && b.limit==null,"local cap charged/global limit confusion");
                check(b.resume() && b.auxiliaryLcg==solver,"outer cap discarded live solver");
                check(b.auxiliaryUntil==b.auxiliaryWork+262144,"outer allowance not cumulative");quotaStops++;
            }else if(stop==1){
                budget.charge(budget.remainingWork());
                try{b.resume();throw new AssertionError("global cap bypassed");}catch(PlanningBudget.Exhausted expected){check(expected.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"incorrect global limit");quotaStops++;}
                check(!solver.infeasible() && !solver.certificate().closed(),"exhaustion made closed proof");
            }else if(stop==2){
                flag.set(true);try{b.resume();b.run(4096,List.of(),List.of(),List.of(),null,()->false);throw new AssertionError("cancel ignored");}catch(java.util.concurrent.CancellationException expected){cancelled++;}
                check(!solver.infeasible(),"cancel made UNSAT");
            }else{
                check(b.resume(),"limit setup resume declined");budget.charge(budget.remainingWork());
                b.run(4096,List.of(),List.of(),List.of(),null,()->false);
                check(b.limit!=null && b.state==IntegerCountBranch.State.UNRESOLVED,"outer global limit not retained");
                check(!b.resume(),"globally exhausted branch renewed");check(!solver.infeasible(),"global exhaustion made UNSAT");quotaStops++;
            }
            b.close();b.close();
        }finally{check(budget.reservedBytes()==0,"boundary leak: "+budget.reservedBytes());}
    }
    @SuppressWarnings("unchecked") static void fairness(){
        var budget=new PlanningBudget(0,1_000_000,128L<<20,()->false,System::nanoTime);var stock=stock(8);
        try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes(8)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,false,budget,System.nanoTime())){
            var model=(RecipeCountModel<String>)get(search,"model");var execution=(CountExecution<String>)get(search,"execution");
            var pending=(Deque<IntegerCountBranch<String>>)get(search,"pending");var deferred=(Deque<IntegerCountBranch<String>>)get(search,"deferred");
            var expected=new ArrayList<IntegerCountBranch<String>>();
            for(int i=0;i<3;i++){var b=new IntegerCountBranch<>(model,execution,"T",1,stock,Map.of(),Set.of(),false,false,budget,System.nanoTime(),List.of());b.initialized=true;deferred.addLast(b);expected.add(b);}
            int visits=0;
            for(int round=0;round<48;round++){
                set(search,"rounds",round);
                var wave=(List<IntegerCountBranch<String>>)invoke(search,"take",new Class<?>[]{int.class,int.class},1,128);
                check(wave.size()==1,"scheduler lost wave");var b=wave.get(0);
                if((round&3)==3){check(b==expected.get(visits%3),"deferred unfair order");visits++;fairTurns++;deferred.addLast(b);}
                else{check(!expected.contains(b),"fresh turn starved");pending.addLast(b);}
            }
            check(visits==12,"missing guaranteed deferred turns");
        }finally{check(budget.reservedBytes()==0,"fair scheduler leak");}
    }
    @SuppressWarnings("unchecked") static void duplicateSafety(){
        var flag=new AtomicBoolean(false);var budget=new PlanningBudget(0,3_000_000,128L<<20,flag::get,System::nanoTime);var stock=stock(20);
        try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes(20)),"T",1,stock,Map.of(),Set.of(),Set.of(),false,false,budget,System.nanoTime())){
            var model=(RecipeCountModel<String>)get(search,"model");var execution=(CountExecution<String>)get(search,"execution");
            var pending=(Deque<IntegerCountBranch<String>>)get(search,"pending");var deferred=(Deque<IntegerCountBranch<String>>)get(search,"deferred");
            try(var candidate=prepared(model,execution,stock,pigeon(5,4),budget,2);
                var peer=prepared(model,execution,stock,pigeon(5,4),budget,3);
                var foreignScope=prepared(model,execution,stock,pigeon(4,5),budget,3)){
                runUntilYield(candidate);runUntilYield(peer);
                check(candidate.auxiliaryLive && peer.auxiliaryLive,"real pause did not mark live");
                check(peer.resume(),"peer did not resume");check(candidate.sameAuxiliarySearch(peer),"exact live peer not recognized");
                long held=budget.reservedBytes();check(candidate.sameAuxiliarySearch(peer),"repeat comparison unstable");check(budget.reservedBytes()==held,"comparison memory leaked");
                for(var state:IntegerCountBranch.State.values())if(state!=IntegerCountBranch.State.OPEN){peer.state=state;check(!candidate.sameAuxiliarySearch(peer),"nonlive peer admitted "+state);}peer.state=IntegerCountBranch.State.OPEN;
                peer.auxiliaryLive=false;check(!candidate.sameAuxiliarySearch(peer),"never-live peer admitted");peer.auxiliaryLive=true;
                long old=peer.workspace;peer.workspace=0;check(!candidate.sameAuxiliarySearch(peer),"released workspace peer admitted");peer.workspace=old;
                old=peer.memory;peer.memory=0;check(!candidate.sameAuxiliarySearch(peer),"closed peer admitted");peer.memory=old;
                peer.limit=budget.exhausted(PlanningBudget.Limit.MEMORY_LIMIT,"synthetic rejected peer");check(!candidate.sameAuxiliarySearch(peer),"limited peer admitted");peer.limit=null;
                int mode=peer.auxiliaryMode;peer.auxiliaryMode=1;check(!candidate.sameAuxiliarySearch(peer),"different algorithm retired");peer.auxiliaryMode=mode;
                check(!candidate.sameAuxiliarySearch(foreignScope),"different scope admitted");
                // Root domain and coordinate identity are additional to current-list equality.
                BigInteger[] upper=(BigInteger[])get(peer.reduction,"upper");BigInteger saved=upper[0];upper[0]=saved.add(BigInteger.ONE);
                check(!candidate.sameAuxiliarySearch(peer),"different root bounds admitted");upper[0]=saved;
                var rows=peer.reduction.rows();var original=rows.get(0);var changed=new ArrayList<>(rows);changed.set(0,new ExactLinearProgram.Constraint(original.terms(),original.upper().add(BigInteger.ONE)));
                set(peer.reduction,"rows",changed);check(!candidate.sameAuxiliarySearch(peer),"different row RHS admitted");set(peer.reduction,"rows",rows);
                int[] roots=(int[])get(peer.reduction,"root");int prior=roots[0];roots[0]=prior+1;check(!candidate.sameAuxiliarySearch(peer),"different recipe mapping admitted");roots[0]=prior;
                check(!candidate.sameAuxiliarySearch(candidate),"self retirement admitted");
                long pressure=budget.availableBytes()-128;budget.reserve(pressure);check(!candidate.sameAuxiliarySearch(peer),"low memory comparison did not decline");budget.release(pressure);
                flag.set(true);try{candidate.sameAuxiliarySearch(peer);throw new AssertionError("comparison ignored cancel");}catch(java.util.concurrent.CancellationException expected){cancelled++;}finally{flag.set(false);}
                check(budget.reservedBytes()==held,"cancelled comparison leaked temporary memory");
                // Only already joined, owned queue peers can retire a newly yielded duplicate.
                deferred.addLast(peer);candidate.auxiliaryCompared=false;
                check((Boolean)invoke(search,"duplicateAuxiliary",new Class<?>[]{IntegerCountBranch.class},candidate),"deferred equivalent was not reused");
                check(candidate.auxiliaryCompared,"dedup comparison not bounded to once");check(!(Boolean)invoke(search,"duplicateAuxiliary",new Class<?>[]{IntegerCountBranch.class},candidate),"dedup rechecked already compared branch");
                deferred.remove(peer);pending.addLast(peer);candidate.auxiliaryCompared=false;
                check((Boolean)invoke(search,"duplicateAuxiliary",new Class<?>[]{IntegerCountBranch.class},candidate),"pending equivalent was not reused");pending.remove(peer);
                peer.close();check(!candidate.sameAuxiliarySearch(peer),"disposed peer remained representative");
                candidate.auxiliaryCompared=false;check(!(Boolean)invoke(search,"duplicateAuxiliary",new Class<?>[]{IntegerCountBranch.class},candidate),"absent peer retired final live search");
                check(candidate.resume(),"surviving candidate could not resume");
            }
        }finally{check(budget.reservedBytes()==0,"dedup safety leak: "+budget.reservedBytes());}
    }
    public static void main(String[]args){
        retained(5,4,2);retained(5,4,3);retained(4,4,2);
        for(int i=0;i<4;i++)boundary(i);fairness();duplicateSafety();
        System.out.println("SAFETY assertions="+assertions+" pauses="+pauses+" resumed="+resumed+" proofs="+checkedProofs+" quota_stops="+quotaStops+" cancelled="+cancelled+" deferred_fair_turns="+fairTurns+" leaks=0");
    }
}
