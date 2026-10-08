package org.cgse.core;
import java.nio.file.*;import java.util.*;import java.math.BigInteger;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
public final class CounterexampleProbe {
 static PlanStep program(Object obj){var m=FusionProbe.map(obj);long n=((Number)m.getOrDefault("n",1L)).longValue();
  if(m.containsKey("batch"))return new PlanStep.Batch((String)m.get("batch"),n);
  if(m.containsKey("repeat"))return new PlanStep.Repeat(program(m.get("repeat")),n);
  return new PlanStep.Sequence(((List<?>)m.get("sequence")).stream().map(CounterexampleProbe::program).toList());
 }
 public static void main(String[] args)throws Exception{
  Path input=Path.of(args[0]),output=Path.of(args[1]);long budget=Long.parseLong(args[2]);int repeats=args.length>4?Integer.parseInt(args[4]):1;
  Set<String> selected=args.length>3&&!args[3].equals("all")?new HashSet<>(Files.readAllLines(Path.of(args[3]))):null;Files.createDirectories(output);
  for(int i=0;i<8;i++)run(ContrastProbe.dag(30),1000,false);
  for(Path file:Files.list(input).sorted().toList())if(file.toString().endsWith(".json")){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(file)).value());var f=FusionProbe.fixture(d);if(selected!=null&&!selected.contains(f.name()))continue;
   if(d.containsKey("witness")){Map<String,GraphRecipe<String>> rs=new HashMap<>();f.recipes().forEach(r->rs.put(r.id(),r));verify(f,summary(program(d.get("witness")),rs));}
   for(int rep=0;rep<repeats;rep++){
    Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("truth",d.get("truth"));row.put("recipes",f.recipes().size());row.put("rep",rep);row.put("budget_ms",budget);
    try{var checked=run(f,budget,false);var g=checked.result();row.put("cgse",BatchProbe.result(g));row.put("mismatch",feasible(g)&&d.get("truth").equals("UNSAT")||d.get("truth").equals("SAT")&&Set.of("INFEASIBLE","MISSING_INPUT","MISSING_SEED").contains(g.status()));
     if(!checked.plan().feasible()&&!checked.plan().missingExact().isEmpty()){
      var p=checked.plan();Map<String,Long> funded=new LinkedHashMap<>(f.stock());boolean fits=true;
      for(var e:p.missingExact().entrySet()){BigInteger q=e.getValue().add(BigInteger.valueOf(f.stock().getOrDefault(e.getKey(),0L)));if(q.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0){fits=false;break;}funded.put(e.getKey(),q.longValueExact());}
      if(fits){var ff=new Fixture(f.name(),f.recipes(),funded,f.target(),f.amount(),f.dag());verify(ff,summary(p.steps(),p.recipes()));row.put("funded_preview_verified",true);}
     }
    }catch(Throwable e){row.put("error",e.toString());e.printStackTrace();}
    Files.writeString(output.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(json(row));System.out.flush();
   }
  }
 }
}
