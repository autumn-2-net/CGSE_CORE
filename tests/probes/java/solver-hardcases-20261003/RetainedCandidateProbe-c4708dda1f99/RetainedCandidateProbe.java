package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Real schedule/force-proof counterexample; only entry into a strategy's candidate handoff is controlled. */
public final class RetainedCandidateProbe {
    static int assertions, rejected, found, rescued;
    static long workLimit=4_000_000;
    static int cancelAt=Integer.MAX_VALUE;
    static final class Declined extends RuntimeException {}
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static GraphRecipe<String> recipe(String id, Map<String,Long> inputs, long output) {
        return new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),Map.of("C",output));
    }
    static List<GraphRecipe<String>> recipes(int order) {
        var recipes = new ArrayList<>(List.of(recipe("turn",Map.of("C",1L,"fuel",1L),1),
                recipe("small",Map.of("raw",1L),1),recipe("fresh",Map.of("raw",1L),3)));
        Collections.rotate(recipes,order); return recipes;
    }
    static void run(String kind, int order, boolean preserve) {
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        var budget = new PlanningBudget(0,workLimit,128L<<20,()->checks.incrementAndGet()>=cancelAt,System::nanoTime);
        var stock=Map.of("C",100L,"fuel",100L,"raw",1L);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes(order)),"C",3,stock,Map.of(),Set.of(),Set.of(),true,budget);
            var execution=new CountExecution<>(model,budget);
            var branch=new IntegerCountBranch<>(model,execution,"C",3,stock,Map.of(),Set.of(),preserve,true,budget,System.nanoTime(),List.of())) {
            int n=model.recipes.size();
            branch.initialized=true; branch.compiled=true;
            branch.lower=new BigInteger[n]; branch.upper=new BigInteger[n];
            Arrays.fill(branch.lower,BigInteger.ZERO);Arrays.fill(branch.upper,BigInteger.ONE);
            for(int i=0;i<n;i++) if(model.recipes.get(i).id().equals("turn")) branch.lower[i]=branch.upper[i]=BigInteger.TWO;
            branch.linearConstraints=new ArrayList<>(model.constraints);
            branch.reduction=new CountReduction(branch.linearConstraints,branch.lower,branch.upper,budget);
            while(!branch.reduction.step()) {}
            branch.counts=new BigInteger[n];Arrays.fill(branch.counts,BigInteger.ZERO);
            for(int i=0;i<n;i++) branch.counts[i]=switch(model.recipes.get(i).id()) {
                case "turn" -> BigInteger.TWO; case "small" -> BigInteger.ONE; default -> BigInteger.ZERO;
            };
            var originalCounts=branch.counts.clone();
            if(!kind.equals("rescue")) {
                branch.modelViews=CountModelViews.create(branch.linearConstraints,branch.lower,branch.upper,budget);
                if(branch.modelViews==null) throw new Declined();
                branch.modelViews.compileLight();
                branch.viewSearch=new CountViewSearch(branch.modelViews,budget);
                branch.viewSearch.resume(32768);
            }
            if(kind.equals("structural")) branch.structuralCandidate=true;
            if(kind.equals("view2")) branch.viewCandidateStage=2;
            if(kind.equals("view4")) branch.viewCandidateStage=4;
            branch.scheduling=new CountSchedule<>(model,branch.counts,budget);
            AllocationSearch.Candidate<String> candidate=null;
            boolean proofReached=false;
            for(int step=0;step<10000;step++) {
                branch.run(1,List.of(),List.of(),List.of(),null,()->false);
                if(branch.limit!=null) throw branch.limit;
                if(branch.assembling!=null) candidate=branch.assembling;
                if(candidate!=null && candidate.stage==6) { proofReached=true; break; }
                if(branch.state!=IntegerCountBranch.State.OPEN) break;
            }
            check(candidate!=null,kind+" did not schedule the bad count vector");
            check(proofReached,kind+" did not reach real ForceCraftProof rejection");
            check(candidate.plan==null,kind+" accepted gross-only turnover");
            check(candidate.summary.delta("C").equals(BigInteger.ONE),kind+" incorrect positive gain");
            rejected++;
            if(kind.equals("structural") || kind.equals("view2") || kind.equals("view4")) {
                check(branch.assembling==null && branch.verifying==null && branch.scheduling==null && branch.program==null,
                        kind+" retained rejected candidate pipeline");
                check(branch.counts==null,kind+" retained rejected counts");
                check(!branch.partitioned && branch.children.isEmpty(),kind+" partitioned speculative count domain");
                if(kind.equals("structural")) check(branch.sourceFace!=null || branch.packing!=null,"structural continuation lost");
                if(kind.equals("view2")) check(branch.congruence!=null,"view stage 2 continuation lost");
                if(kind.equals("view4")) check(branch.state==IntegerCountBranch.State.UNRESOLVED,"view stage 4 continuation lost");
            } else {
                check(Arrays.equals(originalCounts,branch.counts),"ordinary rejection discarded rescue counts");
                check(branch.partitioned,"ordinary rejection lost disjoint sibling domains");
                check(branch.state==IntegerCountBranch.State.UNRESOLVED,"ordinary rejection did not suspend");
                check(branch.resume(),"ordinary rejected candidate could not resume");
                check(branch.assembling==null && branch.verifying==null && branch.program==null && branch.scheduling==null,
                        "resume retained rejected candidate pipeline");
                if(kind.equals("rescue")) {
                    check(branch.rescue && branch.retried,"ordinary count rescue was not retained");
                    check(Arrays.equals(originalCounts,branch.counts),"rescue changed counts");
                    rescued++;
                    System.out.println("RESCUE order="+order+" preserve="+preserve+" work="+budget.nodes());
                    return;
                }
                check(branch.viewStage==4,"ordinary rejection did not resume retained views");
            }
            for(int step=0;step<50000 && branch.state!=IntegerCountBranch.State.FOUND;step++) {
                if(branch.state!=IntegerCountBranch.State.OPEN && !branch.resume()) break;
                branch.run(256,List.of(),List.of(),List.of(),null,()->false);
                if(branch.limit!=null) throw branch.limit;
            }
            check(branch.state==IntegerCountBranch.State.FOUND,kind+" lost valid alternate path: "+branch.state+" limit="+branch.limit);
            PlanVerifier.verifyRuntimeInventory(branch.plan);
            check(branch.plan.patternTimesExact().getOrDefault("fresh",BigInteger.ZERO).signum()>0,kind+" invalid alternate witness");
            try(var verification=new PlanVerification<>(branch.plan,budget)) {
                while(!verification.step()) {}
                try(var proof=new ForceCraftProof<>(branch.plan,verification,Map.of(),budget)) {
                    while(!proof.step()) {}
                    check(proof.proved(),kind+" alternate failed independent force proof");
                }
            }
            found++;
            System.out.println("FOUND kind="+kind+" order="+order+" preserve="+preserve+" counts="+branch.plan.patternTimesExact()+" work="+budget.nodes());
        } finally {
            check(budget.reservedBytes()==0,"reservation leak: "+budget.reservedBytes());
        }
    }
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("quota")) {
            int exhausted=0,cancelled=0,completed=0,declined=0;
            for(int cutoff=1;cutoff<=3000;cutoff+=47) for(boolean cancel:new boolean[]{false,true}) {
                workLimit=cancel?4_000_000:cutoff;cancelAt=cancel?cutoff:Integer.MAX_VALUE;
                try {run("view4",1,false);completed++;}
                catch(PlanningBudget.Exhausted expected) {exhausted++;}
                catch(java.util.concurrent.CancellationException expected) {cancelled++;}
                catch(Declined expected) {declined++;}
            }
            System.out.println("QUOTA exhausted="+exhausted+" cancelled="+cancelled+" completed="+completed+" optional_declined="+declined+" assertions="+assertions+" leaks=0");
            return;
        }
        int failed=0;
        for(String kind:List.of("structural","view2","view4","ordinary","rescue")) for(int order=0;order<3;order++) for(boolean preserve:new boolean[]{false,true}) {
            try {run(kind,order,preserve);} catch(AssertionError error) {failed++;System.out.println("FAIL kind="+kind+" order="+order+" preserve="+preserve+" "+error.getMessage());}
        }
        System.out.println("RESULT rejected="+rejected+" alternate_found="+found+" ordinary_rescued="+rescued+" failed="+failed+" assertions="+assertions);
        if(failed>0) throw new AssertionError("failed="+failed);
    }
}
