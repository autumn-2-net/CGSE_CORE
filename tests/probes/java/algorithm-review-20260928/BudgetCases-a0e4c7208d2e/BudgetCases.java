package org.cgse.core;
import java.io.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

public class BudgetCases {
    public static void main(String[] args) throws Exception {
        int good=0, unresolved=0;
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            int cases=in.readInt();
            for(int c=0;c<cases;c++) {
                String name=GraphFixtureRegression.string(in),target=GraphFixtureRegression.string(in);
                long amount=in.readLong();String truth=GraphFixtureRegression.string(in);var stock=GraphFixtureRegression.amounts(in);
                List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,GraphRecipe<String>> primitive=new LinkedHashMap<>();
                for(int n=in.readInt();n>0;n--) {
                    String id=GraphFixtureRegression.string(in);var input=GraphFixtureRegression.amounts(in);var output=GraphFixtureRegression.amounts(in);
                    var recipe=new GraphRecipe<>(id,id,input.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),output);
                    recipes.add(recipe);primitive.put(id,recipe);
                }
                var budget=new PlanningBudget(Long.parseLong(args[1]),Long.parseLong(args[2]),128L<<20,()->false,System::nanoTime);
                long start=System.nanoTime();String result;
                try {
                    GraphPlan<String> plan;
                    var planning=new GraphPlanner<>(new GraphCompiler<>(recipes)).begin(target,amount,stock,false,true,budget);
                    try {
                        Set<String> notes=new HashSet<>();boolean done;
                        do {done=planning.step();if(Boolean.getBoolean("trace")) for(String note:budget.diagnostics().split(" \\| "))if(notes.add(note))System.out.println("NOTE "+note);}while(!done);
                        plan=planning.result();
                    } finally {planning.close();}
                    result=plan.result().toString();
                    boolean resolved=plan.feasible()||Set.of(GraphPlan.Result.MISSING_INPUT,GraphPlan.Result.MISSING_SEED,GraphPlan.Result.INFEASIBLE).contains(plan.result());
                    if(resolved) {
                        if(truth.equals("SAT")!=plan.feasible())throw new AssertionError(name+" false conclusion "+result);
                        if(plan.feasible()||!plan.missingExact().isEmpty()) {
                            var summary=GraphFixtureRegression.summary(plan.steps(),primitive,new IdentityHashMap<>());
                            Map<String,BigInteger> funded=new HashMap<>();stock.forEach((k,v)->funded.put(k,BigInteger.valueOf(v)));
                            plan.missingExact().forEach((k,v)->funded.merge(k,v,BigInteger::add));
                            for(var e:summary.need().entrySet()) {
                                if(funded.getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(e.getValue())<0)throw new AssertionError(name+" invalid prefix "+e);
                                if(plan.initialExact().getOrDefault(e.getKey(),BigInteger.ZERO).compareTo(e.getValue())<0)throw new AssertionError(name+" invalid reservation "+e);
                            }
                            if(summary.delta().getOrDefault(target,BigInteger.ZERO).add(plan.missingExact().getOrDefault(target,BigInteger.ZERO)).compareTo(BigInteger.valueOf(amount))<0)throw new AssertionError(name+" missing goal");
                        }
                        good++;
                    } else unresolved++;
                } catch(PlanningBudget.Exhausted e) {result=e.toString();unresolved++;}
                System.out.println(name+"\t"+result+"\tms="+(System.nanoTime()-start)/1e6+"\twork="+budget.nodes()+"\tpeak="+budget.peakBytes());
                if(!result.startsWith("FEASIBLE"))System.out.println("TRACE "+budget.diagnostics());
            }
        }
        System.out.println("TOTAL resolved="+good+" unresolved="+unresolved);
    }
}
