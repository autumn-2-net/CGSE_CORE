package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ContinuationProbe {
    static long checks, resumes, saved, continuationWork, uninterruptedWork;
    static int stress, integrated, lifecycle, scopes;
    static final List<Map<String,Object>> rows = new ArrayList<>();
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static BigInteger bi(long value) { return BigInteger.valueOf(value); }
    static PlanningBudget budget() { return new PlanningBudget(0, 40_000_000, 256L<<20, ()->false, System::nanoTime); }
    static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    record Example(List<GraphRecipe<String>> recipes, Map<String,Long> stock, BigInteger[] counts) {}
    static Example example(int seed) {
        var random=new Random(seed*7919L); int n=6+random.nextInt(12), resources=3+random.nextInt(4);
        var recipes=new ArrayList<GraphRecipe<String>>(); var stock=new LinkedHashMap<String,Long>();
        for(int k=0;k<resources;k++)stock.put("k"+k,3L+random.nextInt(6));
        BigInteger[] counts=new BigInteger[n]; Arrays.fill(counts,BigInteger.ZERO);
        for(int i=0;i<n;i++) {
            var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();
            for(int j=0;j<1+random.nextInt(2);j++)in.merge("k"+random.nextInt(resources),1L+random.nextInt(3),Long::sum);
            for(int j=0;j<1+random.nextInt(2);j++)out.merge("k"+random.nextInt(resources),1L+random.nextInt(4),Long::sum);
            recipes.add(recipe("r"+i,in,out));
        }
        var held=new HashMap<>(stock);
        for(int step=0;step<180;step++) {
            var ready=new ArrayList<Integer>();
            for(int i=0;i<n;i++){boolean enabled=true;for(var e:recipes.get(i).inputs().entrySet())enabled&=held.getOrDefault(e.getKey(),0L)>=e.getValue();if(enabled)ready.add(i);}
            if(ready.isEmpty())break;
            int chosen=ready.get(random.nextInt(ready.size())); counts[chosen]=counts[chosen].add(BigInteger.ONE);
            recipes.get(chosen).inputs().forEach((k,v)->held.merge(k,-v,Long::sum));recipes.get(chosen).outputs().forEach((k,v)->held.merge(k,v,Long::sum));
        }
        return new Example(recipes,stock,counts);
    }
    static void tally(PlanStep p, Map<String,BigInteger> out, BigInteger times) {
        if(p instanceof PlanStep.Batch b)out.merge(b.recipe(),times.multiply(bi(b.runs())),BigInteger::add);
        else if(p instanceof PlanStep.Repeat r)tally(r.body(),out,times.multiply(bi(r.times())));
        else for(var child:((PlanStep.Sequence)p).children())tally(child,out,times);
    }
    static void verify(Example e, PlanStep witness, PlanningBudget b) {
        var actual=new HashMap<String,BigInteger>();tally(witness,actual,BigInteger.ONE);
        var byId=new LinkedHashMap<String,GraphRecipe<String>>();e.recipes.forEach(r->byId.put(r.id(),r));
        for(int i=0;i<e.counts.length;i++)check(e.counts[i].equals(actual.getOrDefault(e.recipes.get(i).id(),BigInteger.ZERO)),"count mismatch");
        try(var summary=new SummaryComputation<>(witness,byId,b)) {
            while(!summary.step()){}
            for(var input:summary.result().required().entrySet())check(input.getValue().compareTo(bi(e.stock.getOrDefault(input.getKey(),0L)))<=0,"unfunded prefix "+input);
        }
    }
    static void stress(int seed, long quantum) {
        Example e=example(seed);var b=budget();long baseWork, reusedWork;int handoffs=0;
        try(var model=RecipeCountModel.forShell(e.recipes,Map.of(),e.stock,Set.of(),b)) {
            PlanStep expected;
            long before=b.nodes();
            try(var schedule=new CountSchedule<>(model,e.counts,b)){while(!schedule.step()){}check(schedule.result()==CountSchedule.Result.WITNESS,"baseline not witness "+seed);expected=schedule.witness();}
            baseWork=b.nodes()-before;
            try(var pool=new CountScheduleContinuations<>(model,b)) {
                CountSchedule<String> schedule=pool.acquire(e.counts);long slice=b.nodes();before=slice;
                try {
                    while(!schedule.step())if(b.nodes()-slice>=quantum) {
                        long reserved=b.reservedBytes();
                        check(pool.retain(schedule),"retention refused under ample memory");
                        check(b.reservedBytes()==reserved,"released live continuation reservation");
                        CountSchedule<String> previous=schedule;
                        schedule=pool.acquire(e.counts.clone());
                        check(schedule==previous,"equal counts rebuilt schedule");
                        slice=b.nodes();handoffs++;
                    }
                    reusedWork=b.nodes()-before;
                    check(schedule.result()==CountSchedule.Result.WITNESS,"resumed not witness "+seed);
                    check(schedule.witness().equals(expected),"different witness after suspension "+seed);
                    verify(e,schedule.witness(),b);
                    check(!pool.retain(schedule),"cached completed witness");
                } finally {schedule.close();}
            }
        }
        check(b.reservedBytes()==0,"stress memory leak "+seed);
        stress++;resumes+=handoffs;continuationWork+=reusedWork;uninterruptedWork+=baseWork;
        rows.add(Map.of("seed",seed,"quantum",quantum,"uninterruptedWork",baseWork,"resumedWork",reusedWork,"handoffs",handoffs));
    }
    static void branch(int seed) throws Exception {
        Example original=example(seed);var recipes=new ArrayList<>(original.recipes);
        recipes.add(recipe("finish",Map.of(),Map.of("goal",1L)));
        BigInteger[] counts=Arrays.copyOf(original.counts,original.counts.length+1);counts[counts.length-1]=BigInteger.ONE;
        Example e=new Example(recipes,original.stock,counts);var b=budget();
        var start=IntegerCountBranch.class.getDeclaredMethod("beginScheduling");start.setAccessible(true);
        try(var model=RecipeCountModel.forShell(e.recipes,Map.of("goal",BigInteger.ONE),e.stock,Set.of(),b);
            var execution=new CountExecution<>(model,b);
            var branch=new IntegerCountBranch<>(model,execution,"goal",1,e.stock,Map.of(),Set.of(),false,true,b,System.nanoTime(),List.of())) {
            branch.initialized=true;branch.linearConstraints=new ArrayList<>(model.constraints);branch.failureNeighborhoodTried=true;
            branch.lower=new BigInteger[counts.length];Arrays.fill(branch.lower,BigInteger.ZERO);branch.upper=counts.clone();
            int proposals=0;long schedulingBefore=b.nodes();
            do {
                branch.counts=e.counts.clone();branch.jumpCandidate=true;branch.jumpLate=true;branch.state=IntegerCountBranch.State.OPEN;
                start.invoke(branch);
                for(int rounds=0;rounds<10000&&branch.state==IntegerCountBranch.State.OPEN;rounds++)branch.run(1024,List.of(),List.of(),List.of(),null,()->false);
                check(branch.state==IntegerCountBranch.State.UNRESOLVED||branch.state==IntegerCountBranch.State.FOUND,"unexpected branch state "+branch.state);
                check(++proposals<600,"duplicate continuation never finishes");
            } while(branch.state!=IntegerCountBranch.State.FOUND);
            check(proposals>1,"integration did not cross candidate cutoff "+seed);
            PlanVerifier.verifyRuntimeInventory(branch.plan);
            check(branch.plan.patternTimesExact().entrySet().stream().allMatch(x->x.getValue().signum()>=0),"negative counts");
            integrated++;saved+=(proposals-1L)*8192;
            rows.add(Map.of("branchSeed",seed,"proposals",proposals,"totalPipelineWork",b.nodes()-schedulingBefore));
        }
        check(b.reservedBytes()==0,"branch leak");
    }
    static void scopes() {
        var recipes=List.of(recipe("a",Map.of("raw",1L),Map.of("goal",1L)),recipe("b",Map.of("raw",1L),Map.of("other",1L)));
        var b=budget();var counts=new BigInteger[]{bi(0),bi(31)};var collision=new BigInteger[]{bi(1),bi(0)};
        check(Arrays.hashCode(counts)==Arrays.hashCode(collision),"not a hash collision");
        try(var model=RecipeCountModel.forShell(recipes,Map.of(),Map.of("raw",100L),Set.of(),b);
            var foreign=RecipeCountModel.forShell(recipes,Map.of(),Map.of("raw",99L),Set.of(),b);
            var pool=new CountScheduleContinuations<>(model,b)) {
            CountSchedule<String> original=pool.acquire(counts);check(pool.retain(original),"cannot retain fresh");
            counts[1]=bi(32);
            try(var next=pool.acquire(counts)){check(next!=original,"mutable caller counts altered key");}
            try(var next=pool.acquire(collision)){check(next!=original,"hash collision reused");}
            try(var found=pool.acquire(new BigInteger[]{bi(0),bi(31)})){check(found==original,"exact original key lost");}
            try(var other=new CountSchedule<>(foreign,collision,b)){check(!pool.retain(other),"foreign stock scope accepted");}
            try(var balanced=new CountSchedule<>(model,collision,b,true)){check(!pool.retain(balanced),"different scheduler semantics accepted");}
            var huge=BigInteger.TEN.pow(100);var big=new BigInteger[]{huge,huge.add(BigInteger.ONE)};
            CountSchedule<String> large=pool.acquire(big);check(pool.retain(large),"big counts not retained");
            try(var wrong=pool.acquire(new BigInteger[]{huge.add(BigInteger.ONE),huge})){check(wrong!=large,"large counts truncated");}
            try(var exact=pool.acquire(big.clone())){check(exact==large,"big exact counts lost");}
            var zero=new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO};CountSchedule<String> completed=pool.acquire(zero);
            try(completed){while(!completed.step()){}check(!pool.retain(completed),"terminal outcome cached");}
            try(var fresh=pool.acquire(zero)){check(fresh!=completed,"terminal result reused");}
        }
        check(b.reservedBytes()==0,"scope leak");scopes+=9;
        for(boolean reusable:List.of(false,true)) {
            var slots=List.of(new GraphRecipe.Slot<>("raw",1),new GraphRecipe.Slot<>("circuit",1,0,true,reusable));
            var r=new GraphRecipe<>("configured","configured",slots,reusable?Map.of("goal",1L,"circuit",1L):Map.of("goal",1L));
            var budget=budget();
            try(var model=RecipeCountModel.forShell(List.of(r),Map.of(),Map.of("raw",100L,"circuit",1L),Set.of(),budget);var pool=new CountScheduleContinuations<>(model,budget)) {
                var active=pool.acquire(new BigInteger[]{bi(10)});
                if(reusable)check(pool.retain(active),"read arc should be safe to resume");else{check(!pool.retain(active),"batch-sensitive counts pooled");active.close();}
            }
            check(budget.reservedBytes()==0,"configuration leak");scopes++;
        }
    }
    static void lifecycle() {
        var r=recipe("one",Map.of("raw",1L),Map.of("goal",1L));BigInteger[] counts={bi(10)};
        for(int i=0;i<80;i++) {
            var cancel=new AtomicBoolean();var b=new PlanningBudget(0,100_000,32L<<20,cancel::get,System::nanoTime);
            try(var model=RecipeCountModel.forShell(List.of(r),Map.of(),Map.of("raw",100L),Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)) {
                var active=pool.acquire(counts);check(pool.retain(active),"initial retain failed");
                long bytes=b.availableBytes()-((i%2==0)?(1L<<20):(8L<<20));b.reserve(bytes);
                pool.trim();
                check(b.reservedBytes()<bytes+(1L<<20),"pressure retained large scheduler");
                b.release(bytes);var next=pool.acquire(counts);check(next!=active,"evicted object resumed");check(pool.retain(next),"second retention failed");
                cancel.set(true);boolean caught=false;try{pool.acquire(counts);}catch(java.util.concurrent.CancellationException expected){caught=true;}check(caught,"cancel ignored during lookup");
            }
            check(b.reservedBytes()==0,"cancel cleanup leak");lifecycle++;
        }
        for(int mib:new int[]{1,2,4,6,8,12,16}) {
            var b=new PlanningBudget(0,100_000,(long)mib<<20,()->false,System::nanoTime);
            try(var model=RecipeCountModel.forShell(List.of(r),Map.of(),Map.of("raw",100L),Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)) {
                var active=pool.acquire(counts);boolean retained=pool.retain(active);
                if(mib<=8)check(!retained,"small-memory optional retention admitted");
                if(!retained)active.close();
            }
            check(b.reservedBytes()==0,"small memory leak");lifecycle++;
        }
        for(int cutoff=1;cutoff<=48;cutoff++) {
            var b=new PlanningBudget(0,cutoff,32L<<20,()->false,System::nanoTime);
            try(var model=RecipeCountModel.forShell(List.of(r),Map.of(),Map.of("raw",100L),Set.of(),b);var pool=new CountScheduleContinuations<>(model,b)) {
                CountSchedule<String> active=null;
                try {active=pool.acquire(counts);while(!active.step()){if(pool.retain(active)){active=null;active=pool.acquire(counts);}}}
                finally{if(active!=null)active.close();}
            }catch(PlanningBudget.Exhausted expected){check(expected.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"wrong budget reason");}
            check(b.reservedBytes()==0,"work cap leak "+cutoff);lifecycle++;
        }
    }
    static void macroPrograms() {
        var rs=List.of(recipe("ab",Map.of("a",1L),Map.of("b",1L)),recipe("ba",Map.of("b",1L),Map.of("a",1L)),recipe("finish",Map.of(),Map.of("goal",1L)));
        BigInteger[] counts={BigInteger.ONE,BigInteger.ONE,BigInteger.ONE};
        for(boolean reverse:List.of(false,true)) {
            var b=budget();var stock=Map.of("a",1L,"b",1L);
            try(var model=RecipeCountModel.forShell(rs,Map.of("goal",BigInteger.ONE),stock,Set.of(),b);
                var execution=new CountExecution<>(model,b);
                var branch=new IntegerCountBranch<>(model,execution,"goal",1,stock,Map.of(),Set.of(),false,true,b,System.nanoTime(),List.of())) {
                var witness=new PlanStep.Sequence(List.of(new PlanStep.Batch(reverse?"ba":"ab",1),new PlanStep.Batch(reverse?"ab":"ba",1),new PlanStep.Batch("finish",1)));
                branch.compiledCandidate(counts.clone(),witness);
                for(int step=0;step<10000&&branch.state==IntegerCountBranch.State.OPEN;step++)branch.run(128,List.of(),List.of(),List.of(),null,()->false);
                check(branch.state==IntegerCountBranch.State.FOUND,"macro witness lost");
                PlanVerifier.verifyRuntimeInventory(branch.plan);
                check(branch.plan.initial().getOrDefault(reverse?"b":"a",0L)==1L,"macro order replaced by counts");
            }
            check(b.reservedBytes()==0,"macro cleanup leak");scopes++;
        }
    }
    public static void main(String[] args) throws Exception {
        scopes();lifecycle();macroPrograms();
        for(int seed=0;seed<1200;seed++)stress(seed,seed%4==0?128:8192);
        for(int seed:new int[]{2992,3520,3104,1439,2800,1977,2166,3406,2747})stress(seed,8192);
        for(int seed:new int[]{2992,3520,468,105,628,3104,141,1439,784,2800})branch(seed);
        var examples=new ArrayList<Map<String,Object>>();for(int seed:new int[]{2992,3520,468,105,628,3104,141,1439,784,2800})examples.add(Map.of("seed",seed,"example",example(seed)));
        Files.writeString(Path.of(".local/review-continuations-20261004/handoff-cases.json"),new Gson().toJson(examples));
        var report=Map.of("stress",stress,"resumptions",resumes,"integration",integrated,"lifecycle",lifecycle,"scopes",scopes,"assertions",checks,"uninterruptedWork",uninterruptedWork,"resumedWork",continuationWork,"avoidedRestartLowerBound",saved);
        Files.writeString(Path.of(args[0],"continuation-probe.json"),new Gson().toJson(report));Files.writeString(Path.of(args[0],"continuation-cases.json"),new Gson().toJson(rows));System.out.println(report);
    }
}
