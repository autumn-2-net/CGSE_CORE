package org.cgse.core;
import java.nio.file.*;import java.util.*;
import com.google.ortools.Loader;import com.google.ortools.sat.*;import com.google.ortools.linearsolver.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
public final class NativeCountProbe {
 static Map<String,Object> cp(Fixture f,Map<String,Long> upper,long milliseconds){
  long start=System.nanoTime();CpModel m=new CpModel();IntVar[] x=new IntVar[f.recipes().size()];
  for(int i=0;i<x.length;i++)x[i]=m.newIntVar(0,upper.get(f.recipes().get(i).id()),"x"+i);
  for(String k:FusionProbe.keys(f)){LinearExprBuilder row=LinearExpr.newBuilder();for(int i=0;i<x.length;i++){var r=f.recipes().get(i);row.addTerm(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}m.addGreaterOrEqual(row,(k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L));}
  String validation=m.validate();long built=System.nanoTime();CpSolver solver=new CpSolver();solver.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(milliseconds/1000.0);
  var status=solver.solve(m);long end=System.nanoTime();Map<String,Long> counts=new LinkedHashMap<>();
  if(status==CpSolverStatus.OPTIMAL||status==CpSolverStatus.FEASIBLE)for(int i=0;i<x.length;i++)counts.put(f.recipes().get(i).id(),solver.value(x[i]));
  return Map.of("status",status.name(),"ms",(end-start)/1e6,"build_ms",(built-start)/1e6,"solve_ms",(end-built)/1e6,"counts",counts,"validation",validation,"branches",solver.numBranches());
 }
 static Map<String,Object> scip(Fixture f,Map<String,Long> upper,long milliseconds){
  long start=System.nanoTime();MPSolver solver=MPSolver.createSolver("SCIP");solver.setNumThreads(1);solver.setTimeLimit(milliseconds);MPVariable[] x=new MPVariable[f.recipes().size()];
  for(int i=0;i<x.length;i++)x[i]=solver.makeIntVar(0,upper.get(f.recipes().get(i).id()),"x"+i);
  for(String k:FusionProbe.keys(f)){var row=solver.makeConstraint((k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L),Double.POSITIVE_INFINITY,k);for(int i=0;i<x.length;i++){var r=f.recipes().get(i);row.setCoefficient(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}}
  long built=System.nanoTime();var status=solver.solve();long end=System.nanoTime();Map<String,Long> counts=new LinkedHashMap<>();
  if(status==MPSolver.ResultStatus.OPTIMAL||status==MPSolver.ResultStatus.FEASIBLE)for(int i=0;i<x.length;i++){double v=x[i].solutionValue();long n=Math.round(v);if(Math.abs(v-n)>1e-5)throw new AssertionError("Non-integer native count");counts.put(f.recipes().get(i).id(),n);}
  solver.delete();return Map.of("status",status.name(),"ms",(end-start)/1e6,"build_ms",(built-start)/1e6,"solve_ms",(end-built)/1e6,"counts",counts);
 }
 public static void main(String[] args)throws Exception{
  Loader.loadNativeLibraries();Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);long ms=Long.parseLong(args[2]);Set<String> selected=args.length>3&&!args[3].equals("all")?new HashSet<>(Files.readAllLines(Path.of(args[3]))):null;int repeats=args.length>4?Integer.parseInt(args[4]):1;
  for(int i=0;i<10;i++){var f=ContrastProbe.dag(10);cp(f,Map.of("R1",10L),ms);scip(f,Map.of("R1",10L),ms);}
  for(Path path:Files.list(input).sorted().toList()){
   var d=FusionProbe.map(new FusionProbe.Json(Files.readString(path)).value());var f=FusionProbe.fixture(d);if(selected!=null&&!selected.contains(f.name()))continue;var upper=FusionProbe.amounts(d.get("count_upper"));
   for(int rep=0;rep<repeats;rep++){
    Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("rep",rep);try{row.put("cp_sat",cp(f,upper,ms));}catch(Throwable e){row.put("cp_sat",Map.of("status","ERROR","ms",0,"error",e.toString()));}try{row.put("scip",scip(f,upper,ms));}catch(Throwable e){row.put("scip",Map.of("status","ERROR","ms",0,"error",e.toString()));}
    Files.writeString(output.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" CP="+((Map<?,?>)row.get("cp_sat")).get("status")+" SCIP="+((Map<?,?>)row.get("scip")).get("status"));System.out.flush();
   }
  }
 }
}
