package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProofTaskProbe {
    static int checks, proved, feasible, cutoffs, scopes, retired;
    static final CountProof.Journal archive=new CountProof.Journal(32L<<20);
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static BigInteger bi(long n){return BigInteger.valueOf(n);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static List<ExactLinearProgram.Constraint> rows(int n,boolean sat){
        var p=new LinkedHashMap<Integer,BigInteger>();var m=new LinkedHashMap<Integer,BigInteger>();
        for(int i=0;i<n;i++){p.put(i,bi(2));m.put(i,bi(-2));}
        long rhs=sat?n/2*2:n/2*2+1;
        return List.of(new ExactLinearProgram.Constraint(p,bi(rhs)),new ExactLinearProgram.Constraint(m,bi(-rhs)));
    }
    static void finish(IntegerCountBranch<String> branch){
        int rounds=0;
        do{
            while(branch.state==IntegerCountBranch.State.OPEN){branch.run(2048,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.PROOF,()->false);ok(++rounds<100000,"branch never yields");}
        }while(branch.state==IntegerCountBranch.State.UNRESOLVED&&branch.resume());
    }
    static void finite()throws Exception{
        for(int sample=0;sample<160;sample++){
            int n=8+sample%12;boolean sat=(sample&1)==0;var low=new BigInteger[n];var high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
            var embedding=new CountBenchmark.Embedding(rows(n,sat),low,high);Collections.shuffle(embedding.recipes,new Random(4321+sample));var budget=budget();budget.proofJournal(archive);
            try(var model=RecipeCountModel.create(new GraphCompiler<>(embedding.recipes),"target",1,embedding.stock,Map.of(),Set.of(),Set.of(),true,budget);var execution=new CountExecution<>(model,budget);var branch=new IntegerCountBranch<>(model,execution,"target",1,embedding.stock,Map.of(),Set.of(),false,true,budget,0,List.of())){
                branch.proveRoot(2_000_000);finish(branch);
                if(sat){ok(branch.state==IntegerCountBranch.State.FOUND,"feasible proof task lost candidate "+sample+" "+branch.state+" "+budget.diagnostics());ok(!branch.proofContradiction,"SAT marked root contradiction");PlanVerifier.verifyRuntimeInventory(branch.plan);feasible++;}
                else {ok(branch.state==IntegerCountBranch.State.DEAD&&branch.proofContradiction,"odd equality not proved "+sample+" "+branch.state+" "+budget.diagnostics());ok(branch.learnedChoices.stream().anyMatch(c->c.assumptions().isEmpty()),"root proof not scope tagged");proved++;}
            }
            ok(budget.reservedBytes()==0,"finite memory leak="+budget.reservedBytes());
        }
    }
    static void cutoffAndScope()throws Exception{
        for(int sample=0;sample<160;sample++){
            var b=budget();var stock=Map.of("A",10L);var recipes=List.of(recipe("r",Map.of("A",1L),Map.of("B",1L)));
            try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"B",1,stock,Map.of(),Set.of(),Set.of(),true,b);var execution=new CountExecution<>(model,b)){
                try(var branch=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of())){
                    branch.proveRoot(1);finish(branch);ok(branch.state==IntegerCountBranch.State.UNRESOLVED,"cutoff not local unknown");ok(!branch.proofContradiction&&branch.limit==null,"local cutoff became global proof or exhaustion");ok(!branch.resume(),"local allowance extended");cutoffs++;
                }
                try(var scoped=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(1)),bi(0))))){
                    try{scoped.proveRoot(100);throw new AssertionError("restricted branch admitted as root");}catch(IllegalArgumentException expected){scopes++;}
                }
                try(var branch=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of())){
                    branch.proveRoot(100000);finish(branch);ok(branch.state==IntegerCountBranch.State.FOUND,"incumbent source not found");
                    try(var obsolete=new IntegerCountBranch<>(model,execution,"B",1,stock,Map.of(),Set.of(),false,true,b,0,List.of())){
                        obsolete.proveRoot(100000);long before=b.nodes();obsolete.run(4096,List.of(),List.of(),List.of(),branch.preference,CountPortfolioPolicy.Mode.PROOF,()->false);
                        ok(obsolete.state==IntegerCountBranch.State.PRUNED,"existing plan did not retire proof task");ok(b.nodes()==before,"obsolete proof consumed work");retired++;
                    }
                }
            }
            ok(b.reservedBytes()==0,"scope/cutoff memory leak");
        }
    }
    static void seedless()throws Exception{
        var b=budget();var recipes=List.of(recipe("grow",Map.of("A",1L),Map.of("B",2L)),recipe("back",Map.of("B",1L),Map.of("A",1L)));
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"B",1,Map.of(),Map.of(),Set.of(),Set.of(),true,b);var execution=new CountExecution<>(model,b);var branch=new IntegerCountBranch<>(model,execution,"B",1,Map.of(),Map.of(),Set.of(),false,true,b,0,List.of())){
            branch.proveRoot(100000);finish(branch);
            ok(branch.state!=IntegerCountBranch.State.FOUND,"seedless counts bypassed execution verification");
            ok(!branch.proofContradiction,"execution rejection became full count contradiction");
        }ok(b.reservedBytes()==0,"seedless memory leak");
    }
    static void interrupt()throws Exception{
        for(int stop=0;stop<120;stop++){
            var cancelled=new AtomicBoolean();var b=new PlanningBudget(0,200000,64L<<20,cancelled::get,System::nanoTime);int n=12;var low=new BigInteger[n];var high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);var e=new CountBenchmark.Embedding(rows(n,true),low,high);
            try(var model=RecipeCountModel.create(new GraphCompiler<>(e.recipes),"target",1,e.stock,Map.of(),Set.of(),Set.of(),true,b);var execution=new CountExecution<>(model,b);var branch=new IntegerCountBranch<>(model,execution,"target",1,e.stock,Map.of(),Set.of(),false,true,b,0,List.of())){
                branch.proveRoot(Long.MAX_VALUE);for(int i=0;i<stop&&branch.state==IntegerCountBranch.State.OPEN;i++)branch.run(1,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.PROOF,()->false);
                cancelled.set(true);try{branch.run(1,List.of(),List.of(),List.of(),null,CountPortfolioPolicy.Mode.PROOF,()->false);}catch(java.util.concurrent.CancellationException expected){checks++;}ok(!branch.proofContradiction,"cancel created proof");
            }ok(b.reservedBytes()==0,"interrupted state leaked at "+stop+" bytes="+b.reservedBytes());
        }
    }
    static void finishing()throws Exception{
        for(boolean cancel:new boolean[]{false,true}){
            var clock=new java.util.concurrent.atomic.AtomicLong();var cancelled=new AtomicBoolean();
            var b=new PlanningBudget(1,1_000_000,64L<<20,cancelled::get,clock::get);var compiler=new GraphCompiler<>(List.of(recipe("r",Map.of("A",1L),Map.of("B",1L))));
            try(var search=new IntegerCountSearch<>(compiler,"B",1,Map.of(),Map.of(),Set.of(),Set.of(),false,true,b,0)){
                var poolField=search.getClass().getDeclaredField("choiceConflicts");poolField.setAccessible(true);
                ((CountConflictPool)poolField.get(search)).add(List.of(new CountConflict(List.of())));
                var done=search.getClass().getDeclaredMethod("finish",boolean.class);done.setAccessible(true);
                if(cancel)cancelled.set(true);else clock.set(2_000_000);
                try{ok((boolean)done.invoke(search,true),"finish not terminal");ok(!cancel,"finish swallowed cancellation");ok(search.infeasible(),"optional cache discarded completed proof");ok(b.diagnostics().contains("finished_result_retained"),"optional deadline not exercised");}
                catch(java.lang.reflect.InvocationTargetException error){if(!cancel||!(error.getCause() instanceof java.util.concurrent.CancellationException))throw error;checks++;}
            }ok(b.reservedBytes()==0,"finish exception leaked "+b.reservedBytes());
        }
    }
    public static void main(String[]args)throws Exception{
        finite();cutoffAndScope();seedless();interrupt();finishing();
        ok(!archive.entries().isEmpty(),"proof tasks emitted no checkable certificate");
        for(var proof:archive.entries())ok(CountProof.verify(proof,2_000_000)==CountProof.Verdict.VERIFIED,"unreplayable proof task certificate");
        archive.write(Path.of(args[0],"proof-task-certificates.bin"));
        String result="{\"assertions\":"+checks+",\"proved\":"+proved+",\"verified\":"+feasible+",\"localCutoffs\":"+cutoffs+",\"restrictedScopeRejected\":"+scopes+",\"retiredForIncumbent\":"+retired+",\"cancellationPoints\":120}";
        Files.writeString(Path.of(args[0],"proof-task-probe.json"),result);System.out.println(result);
    }
}
