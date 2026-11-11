package org.cgse.core;

import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;

/** Local-only oracle for the deliberately incomplete ordinary fallback. */
public final class GraphFallbackTest {
    static int assertions;
    // Frozen old-fallback funded positives, Java Random2992026 family below.
    // Original outcomes and full inputs are retained in the local registry.
    static final Set<Integer> BASELINE_FUNDED = Set.of(
            2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 15, 17, 18, 20, 22, 23,
            26, 28, 29, 30, 31, 32, 34, 35, 36, 39, 41, 42, 43, 45, 46, 47,
            48, 49, 50, 53, 54, 57, 58, 60, 62, 66, 67, 68, 70, 72, 73, 74,
            75, 76, 77, 78, 80, 82, 83, 84, 85, 86, 89, 90, 91, 94, 95, 96,
            98, 99, 100, 101, 102, 105, 107, 108, 110, 111, 112, 113, 114, 116, 117, 118,
            119, 120, 121, 122, 125, 126, 127, 128, 129, 131, 132, 133, 134, 135, 138, 140,
            142, 144, 145, 146, 147, 148, 149, 152, 153, 157, 158, 159, 160, 161, 162, 163,
            164, 165, 166, 168, 169, 170, 172, 174, 179, 180, 182, 183, 184, 185, 186, 189,
            191, 192, 193, 194, 195, 198, 200, 203, 204, 206, 207, 208, 209, 210, 211, 212,
            213, 214, 215, 216, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 229, 233,
            234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 247, 248, 249, 250, 252, 253,
            256, 258, 260, 263, 264, 266, 269, 270, 274, 275, 276, 277, 279, 280, 282, 285,
            288, 289, 297, 299, 300, 302, 306, 307, 308, 310, 311, 312, 315, 316, 317, 318,
            319, 320, 321, 323, 324, 325, 326, 328, 329, 330, 331, 332, 333, 334, 335, 337,
            338, 340, 341, 343, 345, 346, 347, 348, 349, 350, 353, 354, 355, 362, 363, 367,
            368, 369, 370, 371, 372, 373, 374, 375, 377, 378, 379, 382, 383, 386, 387, 389,
            391, 392, 393, 394, 395, 396, 397, 400, 401, 403, 406, 407, 408, 409, 412, 413,
            416, 417, 419, 420, 421, 422, 423, 424, 425, 426, 427, 429, 430, 431, 433, 434,
            435, 436, 440, 443, 444, 445, 448, 449, 450, 454, 455, 456, 457, 458, 459, 460,
            463, 464, 467, 468, 469, 471, 476, 477, 479, 481, 482, 485, 486, 487, 489, 491,
            492, 494, 495, 496, 498);
    static GraphRecipe<String> r(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static void check(boolean value,Object why) {assertions++;if(!value)throw new AssertionError(why);}
    static GraphPlan<String> plan(List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock) {
        var b=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
        var p=GraphFallback.plan(new GraphCompiler<>(recipes),target,n,stock,Set.of(),Map.of(),true,true,b);
        check(b.reservedBytes()==0,"workspace retained");
        return p;
    }
    static void verify(GraphPlan<String> p) {
        Map<String,BigInteger> inventory=new HashMap<>(p.initialExact());
        ArrayDeque<PlanStep> steps=new ArrayDeque<>();steps.push(p.steps());int fired=0;
        while(!steps.isEmpty()) {
            var step=steps.pop();
            if(step instanceof PlanStep.Sequence seq)for(int i=seq.children().size()-1;i>=0;i--)steps.push(seq.children().get(i));
            else if(step instanceof PlanStep.Repeat repeat) {check(repeat.times()<100000,"oracle repeat bound");for(long i=0;i<repeat.times();i++)steps.push(repeat.body());}
            else {var batch=(PlanStep.Batch)step;var recipe=p.recipes().get(batch.recipe());
                for(long i=0;i<batch.runs();i++) {
                    check(++fired<1000000,"oracle bound");
                    for(var entry:recipe.inputs().entrySet()) {
                        var rest=inventory.getOrDefault(entry.getKey(),BigInteger.ZERO).subtract(BigInteger.valueOf(entry.getValue()));
                        check(rest.signum()>=0,"invalid prefix "+p.recipes()+" "+entry);inventory.put(entry.getKey(),rest);
                    }
                    recipe.outputs().forEach((key,n)->inventory.merge(key,BigInteger.valueOf(n),BigInteger::add));
                }
            }
        }
        inventory.merge(p.target(),BigInteger.valueOf(p.amount()).negate(),BigInteger::add);
        check(inventory.get(p.target()).signum()>=0,"missing delivery");
        p.seeds().forEach((key,n)->check(inventory.getOrDefault(key,BigInteger.ZERO).compareTo(BigInteger.valueOf(n))>=0,"lost restoration"));
    }
    public static void main(String[] args) {run();}
    public static void run() {
        var chain=List.of(r("plate",Map.of("ore",2L),Map.of("plate",3L)),r("p",Map.of("plate",2L),Map.of("p",1L)));
        var p=plan(chain,"p",7,Map.of("ore",9L,"p",100L));
        check(p.missing().equals(Map.of("ore",1L)),p.missing());check(p.patternTimes().equals(Map.of("plate",5L,"p",7L)),p.patternTimes());verify(p);
        var diamond=List.of(r("grind",Map.of("diamond",1L),Map.of("dust",1L)),r("restore",Map.of("dust",4L),Map.of("diamond",3L)));
        p=plan(diamond,"diamond",6,Map.of("diamond",1L));
        check(p.patternTimes().equals(Map.of("restore",2L)),"fallback ran lossy cycle");check(p.missing().equals(Map.of("dust",8L)),p.missing());check(p.seeds().isEmpty(),"invented seed proof");verify(p);
        var grow=r("grow",Map.of("a",1L,"raw",1L),Map.of("a",2L));
        p=plan(List.of(grow),"a",10,Map.of("a",100L,"raw",100L));
        check(!p.patternTimes().containsKey("grow") && p.result()==GraphPlan.Result.INCOMPLETE_MODEL,
                "ACYCLIC admitted physical self-growth or asserted a global proof");
        var fullBudget=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
        var full=new GraphPlanner<>(new GraphCompiler<>(List.of(grow))).plan("a",10,Map.of("a",100L,"raw",100L),true,true,fullBudget);
        check(full.feasible(),"FULL lost formerly funded self-growth");PlanVerifier.verify(full);verify(full);
        p=plan(List.of(grow,r("linear",Map.of("raw",1L),Map.of("a",1L))),"a",10,Map.of("raw",10L));
        check(p.feasible()&&p.patternTimes().equals(Map.of("linear",10L)),"did not skip self-cycle source");verify(p);
        var tool=r("tool",Map.of("hammer",1L,"ore",1L),Map.of("hammer",1L,"p",1L));
        p=plan(List.of(tool),"p",100,Map.of("ore",100L,"hammer",1L));
        check(!p.patternTimes().containsKey("tool") && p.result()==GraphPlan.Result.INCOMPLETE_MODEL,
                "ACYCLIC admitted a physical returned tool");
        fullBudget=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
        full=new GraphPlanner<>(new GraphCompiler<>(List.of(tool))).plan("p",100,Map.of("ore",100L,"hammer",1L),true,true,fullBudget);
        check(full.feasible()&&full.initial().get("hammer")==1,"FULL lost returned-tool witness");PlanVerifier.verify(full);verify(full);
        var virtual=new GraphRecipe<>("virtual","virtual",List.of(new GraphRecipe.Slot<>("lens",1,0,true,true),new GraphRecipe.Slot<>("ore",1)),Map.of("lens",1L,"p",1L));
        p=plan(List.of(virtual),"p",100,Map.of("ore",100L));check(p.missing().equals(Map.of("lens",1L)),p.missing());verify(p);
        var joint=List.of(r("parent",Map.of("b",1L),Map.of("p",1L)),r("child",Map.of("raw",1L),Map.of("b",1L,"p",1L)));
        p=plan(joint,"p",1,Map.of("raw",1L));check(p.feasible()&&p.patternTimes().equals(Map.of("child",1L)),"unneeded parent persisted");verify(p);
        var sibling=List.of(r("parent",new LinkedHashMap<>(Map.of("a",1L,"b",1L)),Map.of("p",1L)),r("a",Map.of("ore",1L),Map.of("a",1L)),r("b",Map.of("a",1L),Map.of("b",1L)));
        p=plan(sibling,"p",4,Map.of("ore",4L));verify(p);
        p=plan(List.of(r("first",Map.of("x",1L),Map.of("p",1L)),r("second",Map.of("y",1L),Map.of("p",1L))),"p",10,Map.of("y",10L));
        check(p.feasible()&&p.patternTimes().equals(Map.of("second",10L)),"fallback did not backtrack to the funded alternative");verify(p);
        var b=new PlanningBudget(0,131072,()->false);
        p=GraphFallback.plan(new GraphCompiler<>(List.of(tool)),"p",10,Map.of("ore",2L),Set.of("ore"),Map.of("hammer",1L),true,true,b);
        check(!p.patternTimes().containsKey("tool") && p.result()==GraphPlan.Result.INCOMPLETE_MODEL,
                "External supply enabled physical self-feedback in ACYCLIC");check(b.reservedBytes()==0,"external leak");
        for(long n:new long[]{Integer.MAX_VALUE,Long.MAX_VALUE}) {
            p=plan(chain,"p",n,Map.of());
            var plates=BigInteger.valueOf(n).multiply(BigInteger.TWO).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
            check(p.patternTimesExact().get("plate").equals(plates),"long rounds");
            check(p.missingExact().equals(Map.of("ore",plates.multiply(BigInteger.TWO))),"long deficit");
        }
        List<GraphRecipe<String>> deep=new ArrayList<>();
        for(int i=0;i<5000;i++)deep.add(r("r"+i,Map.of("k"+(i+1),1L),Map.of("k"+i,1L)));
        var deepBudget=new PlanningBudget(0,1_000_000,16L<<20,()->false,System::nanoTime);
        p=GraphFallback.plan(new GraphCompiler<>(deep),"k0",1,Map.of("k5000",1L),Set.of(),Map.of(),true,true,deepBudget);
        check(p.patternTimes().size()==5000&&p.feasible(),"depth cutoff became a false missing intermediate");verify(p);check(deepBudget.reservedBytes()==0,"deep refill workspace");
        Random random=new Random(2992026);
        for(int trial=0;trial<500;trial++) {
            List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,Long> stock=new HashMap<>();
            for(int i=0;i<6;i++)stock.put("k"+i,(long)random.nextInt(8));
            for(int i=0;i<8;i++) {Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();
                for(int j=0;j<1+random.nextInt(2);j++)in.merge("k"+random.nextInt(6),1L+random.nextInt(3),Long::sum);
                for(int j=0;j<1+random.nextInt(2);j++)out.merge("k"+random.nextInt(6),1L+random.nextInt(3),Long::sum);
                recipes.add(r("r"+i,in,out));
            }
            int quantity=1+random.nextInt(6);
            try { p=plan(recipes,"k0",quantity,stock); }
            catch (PlanningBudget.Exhausted limit) { throw new AssertionError("trial="+trial+"; amount="+quantity+"; stock="+stock+"; recipes="+recipes,limit); }
            if (!p.feasible() && p.missingExact().isEmpty()) {
                check(p.result()==GraphPlan.Result.INCOMPLETE_MODEL && p.patternTimesExact().isEmpty(),
                        "restricted search published a proof or partial word: " + trial);
                if (BASELINE_FUNDED.contains(trial)) {
                    // Every old funded case remains a gate, even when a new
                    // strict domain loses a different case after a later edit.
                    var fullBudgetCase=new PlanningBudget(0,131072,16L<<20,()->false,System::nanoTime);
                    var fullCase=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan("k0",quantity,stock,true,true,fullBudgetCase);
                    check(fullCase.feasible(), "FULL lost old funded fixture: " + trial);
                    check(SequenceSummary.of(fullCase.steps(),fullCase.recipes()).delta("k0")
                            .compareTo(BigInteger.valueOf(quantity))>=0, "FULL manufacturing shortfall: " + trial);
                    PlanVerifier.verify(fullCase);
                    check(fullBudgetCase.reservedBytes()==0,"FULL fixture workspace retained");
                }
                // Old supply-only target previews are not manufactured words.
                // They never supply an invented positive oracle for FULL.
                continue;
            }
            check(SequenceSummary.of(p.steps(),p.recipes()).delta("k0").compareTo(BigInteger.valueOf(quantity))>=0,
                    "Published random bill does not manufacture its target: " + trial);
            verify(p);
            for(var e:p.initialExact().entrySet()) {
                var have=BigInteger.valueOf(e.getKey().equals("k0")?0:stock.getOrDefault(e.getKey(),0L));
                check(p.missingExact().getOrDefault(e.getKey(),BigInteger.ZERO).equals(e.getValue().subtract(have).max(BigInteger.ZERO)),"incorrect shortage");
            }
        }
        for(int limit=1;limit<=160;limit++) {
            var budget=new PlanningBudget(0,limit,16L<<20,()->false,System::nanoTime);
            try{GraphFallback.plan(new GraphCompiler<>(chain),"p",7,Map.of(),Set.of(),Map.of(),true,true,budget);}catch(PlanningBudget.Exhausted expected){}
            check(budget.reservedBytes()==0,"work-limit leak "+limit);
        }
        for(long bytes:new long[]{128,256,1024,4096}) {
            var budget=new PlanningBudget(0,131072,bytes,()->false,System::nanoTime);
            try{GraphFallback.plan(new GraphCompiler<>(chain),"p",7,Map.of(),Set.of(),Map.of(),true,true,budget);}catch(PlanningBudget.Exhausted expected){}
            check(budget.reservedBytes()==0,"memory-limit leak "+bytes);
        }
        var cancelled=new PlanningBudget(0,131072,()->true);
        try{GraphFallback.plan(new GraphCompiler<>(chain),"p",1,Map.of(),Set.of(),Map.of(),true,true,cancelled);throw new AssertionError("cancellation ignored");}catch(java.util.concurrent.CancellationException expected){}
        check(cancelled.reservedBytes()==0,"cancel leak");
        System.out.println("Graph fallback: "+assertions+" assertions; cyclic cuts, bounded alternatives, joint outputs, tools, long, 500 prefix oracles and lifecycle passed");
    }
}
