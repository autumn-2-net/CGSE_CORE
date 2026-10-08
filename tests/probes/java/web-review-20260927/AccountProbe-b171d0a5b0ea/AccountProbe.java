package org.cgse.core;
import java.util.*;import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
/** Component-level trap: ratio-greedy spends 6 of a budget10, exact picks5+5. */
public final class AccountProbe {
 public static void main(String[]args)throws Exception{
  Path out=Path.of(args[0]);Files.createDirectories(out);
  for(int scale:new int[]{1,2,7,1000}){
   long a=6L*scale,b=5L*scale;
   var rs=List.of(r("A_return",Map.of("UA",1L,"CAT",a,"FUEL",a),Map.of("DA",1L,"CAT",a)),r("A_burn",Map.of("UA",1L,"CAT",a),Map.of("DA",1L)),r("B_return",Map.of("UB",1L,"CAT",b,"FUEL",b),Map.of("DB",1L,"CAT",b)),r("B_burn",Map.of("UB",1L,"CAT",b),Map.of("DB",1L)),r("finish",Map.of("DA",1L,"DB",2L),Map.of("GOAL",1L)));
   var stock=Map.of("UA",1L,"UB",2L,"CAT",10L*scale,"FUEL",6L*scale);var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
   try(var model=RecipeCountModel.create(new GraphCompiler<>(rs),"GOAL",1,stock,Map.of(),Set.of(),Set.of(),true,budget)){
    var fuel=CountRecoveryFuel.compile(model,rs,budget);if(fuel==null)throw new AssertionError("No account compilation");var plan=new GraphPlanner<>(new GraphCompiler<>(List.copyOf(fuel.recipes()))).plan("GOAL",1,stock,false,true,budget);if(!plan.feasible())throw new AssertionError("No free-fuel candidate");var lifted=fuel.lift(plan,unused->{});if(lifted==null)throw new AssertionError("No exact account lift");Map<String,GraphRecipe<String>> by=new HashMap<>();rs.forEach(r->by.put(r.id(),r));verify(new Fixture("account_trap_"+scale,rs,stock,"GOAL",1,false),summary(lifted,by));
    String trace=budget.diagnostics().toString();if(!trace.contains("exact_account_allocation"))throw new AssertionError("Exact account branch not exercised");var row=Map.of("case","account_trap_"+scale,"status","VERIFIED_EXECUTABLE","component_only",true,"trace",trace);Files.writeString(out.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(json(row));
   }
  }
 }
}
