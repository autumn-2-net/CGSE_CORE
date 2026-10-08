package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Local independent sequential-state oracle; no solver propagation code is reused. */
public final class BootstrapOracleProbe {
    record Spec(int needA, int backA, int raw, int outC) {}
    record State(int a, int b, int c) {}
    static int cases, feasible, possibleUnknown, falseMissing, falseFeasible;
    static long work;
    static final Spec ORIGINAL = new Spec(2,1,3,3);
    static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static List<GraphRecipe<String>> recipes(Spec s, int order, long seed) {
        var rs=new ArrayList<GraphRecipe<String>>();
        var outputs=new LinkedHashMap<String,Long>();outputs.put("C",(long)s.outC);if(s.backA>0)outputs.put("A",(long)s.backA);
        rs.add(recipe("main",Map.of("A",(long)s.needA,"B",(long)s.raw),outputs));
        rs.add(recipe("recycle",Map.of("C",1L),Map.of("A",1L)));
        for(int i=0;i<8;i++)rs.add(recipe("dead"+i,Map.of("X"+i,1L),Map.of("C",1L)));
        if(order==1)Collections.rotate(rs,-2);else if(order>1)Collections.shuffle(rs,new Random(seed+order));
        return rs;
    }
    static boolean oracle(Spec s, Map<String,Long> stock, int amount, boolean force) {
        int a=stock.get("A").intValue(),b=stock.get("B").intValue(),c=stock.get("C").intValue();
        var first=new State(a,b,c);var todo=new ArrayDeque<State>();var seen=new HashSet<State>();todo.add(first);seen.add(first);
        while(!todo.isEmpty()) {
            var now=todo.removeFirst();int made=(b-now.b)/s.raw*s.outC;
            if(now.c>=amount&&(!force||made>=amount&&now.c>c))return true;
            if(now.a>=s.needA&&now.b>=s.raw) {
                var next=new State(now.a-s.needA+s.backA,now.b-s.raw,now.c+s.outC);
                if(seen.add(next))todo.addLast(next);
            }
            if(now.c>0){var next=new State(now.a+1,now.b,now.c-1);if(seen.add(next))todo.addLast(next);}
        }
        return false;
    }
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static void verify(GraphPlan<String> p,Map<String,Long> stock,boolean force) {
        var held=new HashMap<String,BigInteger>();p.initialExact().forEach((k,v)->{check(v.compareTo(BigInteger.valueOf(stock.getOrDefault(k,0L)))<=0,"stock overdraw "+k);held.put(k,v);});
        BigInteger initialC=held.getOrDefault("C",BigInteger.ZERO);long gross=0,operations=0;
        var cursor=new PlanCursor(p.steps());
        for(var batch=cursor.current();batch!=null;batch=cursor.current()) {
            var r=p.recipes().get(batch.recipe());
            for(long run=0;run<batch.runs();run++) {
                check(++operations<10000,"unexpected expanded size");
                for(var e:r.inputs().entrySet())check(held.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(BigInteger.valueOf(e.getValue()))>=0,"unfunded prefix "+r.id()+" held="+held);
                r.inputs().forEach((k,v)->held.merge(k,BigInteger.valueOf(-v),BigInteger::add));r.outputs().forEach((k,v)->held.merge(k,BigInteger.valueOf(v),BigInteger::add));
                gross+=r.executionOutputs().getOrDefault("C",0L);
            }
            cursor.dispatched(batch.runs());
        }
        check(held.getOrDefault("C",BigInteger.ZERO).compareTo(BigInteger.valueOf(p.amount()+p.seeds().getOrDefault("C",0L)))>=0,"short final delivery");
        for(var e:p.seeds().entrySet())check(held.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(BigInteger.valueOf(e.getValue()))>=0,"lost seed "+e.getKey());
        check(!force||gross>=p.amount()&&held.getOrDefault("C",BigInteger.ZERO).compareTo(initialC)>0,"force skipped physical/real target production");
    }
    static GraphPlan<String> solve(int id,Spec s,Map<String,Long> stock,int amount,boolean force,int order) {
        boolean ref=oracle(s,stock,amount,force);var budget=new PlanningBudget(0,3_000_000,128L<<20,()->false,System::nanoTime);
        var p=new GraphPlanner<>(new GraphCompiler<>(recipes(s,order,17877L+id*193L))).plan("C",amount,stock,false,force,budget);
        cases++;work+=budget.nodes();
        if(p.feasible()){feasible++;if(!ref)falseFeasible++;verify(p,stock,force);}
        else if(ref) {if(!p.missingExact().isEmpty()||p.result()==GraphPlan.Result.INFEASIBLE)falseMissing++;else possibleUnknown++;}
        System.out.println("CASE|"+id+"|"+force+"|"+order+"|"+ref+"|"+p.result()+"|"+budget.nodes()+"|"+s+"|"+stock+"|"+amount+"|"+p.patternTimesExact());
        if(ref&&!p.feasible())System.out.println("UNRESOLVED_DETAIL|"+id+"|"+force+"|"+order+"|"+budget.diagnostics());
        return p;
    }
    static void focused() {
        for(int which=0;which<2;which++) {
            var stock=Map.of("A",0L,"B",which==0?9L:12L,"C",which==0?2L:4L);int amount=which==0?6:11,n=which==0?3:4;
            var rs=recipes(ORIGINAL,0,1);var byId=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->byId.put(r.id(),r));
            var body=new PlanStep.Sequence(List.of(new PlanStep.Batch("recycle",1),new PlanStep.Batch("main",1)));
            var program=new PlanStep.Sequence(List.of(new PlanStep.Batch("recycle",1),new PlanStep.Repeat(body,n)));
            var witness=new GraphPlan<>("C",amount,false,program,byId,stock,Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);verify(witness,stock,true);
            var budget=new PlanningBudget(0,3_000_000,128L<<20,()->false,System::nanoTime);
            try(var v=new PlanVerification<>(witness,budget)){while(!v.step()){}try(var proof=new ForceCraftProof<>(witness,v,Map.of(),budget)){while(!proof.step()){}check(proof.proved(),"direct prefix witness not certified");}}
            System.out.println("DIRECT|"+which+"|prefix=recycle|steady=(recycle,main)^"+n+"|counts="+witness.patternTimesExact());
            for(int order=0;order<3;order++)solve(which,ORIGINAL,stock,amount,true,order);
        }
    }
    static void randomized() {
        var random=new Random(202610035902L);
        for(int sample=0;sample<480;sample++) {
            int a=1+random.nextInt(4),back=random.nextInt(a),raw=1+random.nextInt(4),produced=a-back+1+random.nextInt(4);
            var spec=sample<240?ORIGINAL:new Spec(a,back,raw,produced);
            var stock=Map.of("A",(long)random.nextInt(6),"B",(long)(spec.raw*random.nextInt(9)+random.nextInt(spec.raw)),"C",(long)random.nextInt(11));int amount=1+random.nextInt(30);
            for(boolean force:new boolean[]{false,true})for(int order=0;order<4;order++)solve(sample,spec,stock,amount,force,order);
            if(sample%20==19)System.out.println("PROGRESS|models="+(sample+1)+"|cases="+cases+"|possibleUnknown="+possibleUnknown+"|falseMissing="+falseMissing);
        }
    }
    public static void main(String[]args) {
        if(args.length>0&&args[0].equals("random"))randomized();else focused();
        System.out.println("TOTAL|cases="+cases+"|feasible="+feasible+"|possibleUnknown="+possibleUnknown+"|falseMissing="+falseMissing+"|falseFeasible="+falseFeasible+"|work="+work);
        check(falseMissing==0&&falseFeasible==0,"soundness mismatches");
    }
}
