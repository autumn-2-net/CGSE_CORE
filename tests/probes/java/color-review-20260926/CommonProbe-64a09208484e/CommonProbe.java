package org.cgse.core;
import java.util.*;import java.nio.file.*;import com.google.ortools.Loader;
import static org.cgse.core.ContrastProbe.*;import static org.cgse.core.AdversarialProbe.*;
public final class CommonProbe {
 static void truth(Fixture f,Map<String,Object>d){
  Map<String,GraphRecipe<String>> rs=new LinkedHashMap<>();for(var r:f.recipes())rs.put(r.id(),r);PlanStep p;long n=f.amount();
  if(f.name().startsWith("two_step"))p=new PlanStep.Sequence(List.of(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("r1",1),new PlanStep.Batch("r2",1))),n-2),new PlanStep.Batch("r1",1)));
  else if(f.name().startsWith("three_step"))p=new PlanStep.Sequence(List.of(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("r1",1),new PlanStep.Batch("r2",1),new PlanStep.Batch("r3",1))),n-2),new PlanStep.Batch("r1",1)));
  else if(f.name().startsWith("recovery"))p=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("start",1),new PlanStep.Batch("return",1))),n);
  else{var counts=FusionProbe.amounts(d.get("counts"));p=new PlanStep.Sequence(f.recipes().stream().<PlanStep>map(r->new PlanStep.Batch(r.id(),counts.getOrDefault(r.id(),0L))).toList());}
  verify(f,summary(p,rs));
 }
 static Result commonRun(Fixture f){
  long start=System.nanoTime();PlanningBudget b=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
  var p=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).plan(f.target(),f.amount(),f.stock(),false,false,b);
  double ms=(System.nanoTime()-start)/1e6;if(p.feasible())verify(f,summary(p.steps(),p.recipes()));
  return new Result(p.result().name(),ms,"forceCraft=false checks="+b.nodes()+" missing="+p.missingExact()+" trace="+b.diagnostics());
 }
 public static void main(String[] args)throws Exception{
  Loader.loadNativeLibraries();Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);int repeats=args.length>2?Integer.parseInt(args[2]):3;
  for(int i=0;i<20;i++){run(ContrastProbe.dag(100),3000,false);cpDag(ContrastProbe.dag(100),3);scip(ContrastProbe.dag(100),3);}
  for(Path path:Files.list(input).sorted().toList()){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(path)).value());var f=FusionProbe.fixture(d);truth(f,d);
   for(int rep=0;rep<repeats;rep++){
    Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("rep",rep);row.put("recipes",f.recipes().size());row.put("truth","SAT");
    if(!f.name().equals("long_max_1to1")){
     if(f.dag()){row.put("cp_sat",BatchProbe.result(cpDag(f,3)));row.put("scip",BatchProbe.result(scip(f,3)));}
     else if(rep==0&&((Number)d.get("horizon")).intValue()>0)row.put("cp_sat",BatchProbe.result(BatchProbe.timedCp(new BatchProbe.Test("common",f,"SAT",List.of(),((Number)d.get("horizon")).intValue()),3)));
    }
    row.put("cgse",BatchProbe.result(commonRun(f)));Files.writeString(output.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+json(row));
   }
  }
 }
}
