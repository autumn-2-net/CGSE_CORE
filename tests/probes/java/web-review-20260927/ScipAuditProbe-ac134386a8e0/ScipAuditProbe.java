package org.cgse.core;
import java.nio.file.*;import java.util.*;
import com.google.ortools.Loader;import com.google.ortools.linearsolver.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
public final class ScipAuditProbe {
 static Map<String,Object> solve(Fixture f,Map<String,Long> upper,Map<String,Long> witness,String profile){
  long start=System.nanoTime();MPSolver s=MPSolver.createSolver("SCIP");s.setNumThreads(1);s.setTimeLimit(3000);boolean settings=true;
  if(profile.equals("tight"))settings=s.setSolverSpecificParametersAsString("numerics/feastol = 1e-9\nnumerics/dualfeastol = 1e-9\nnumerics/epsilon = 1e-12");
  if(profile.equals("presolve_off"))settings=s.setSolverSpecificParametersAsString("presolving/maxrounds = 0");
  MPVariable[] x=new MPVariable[f.recipes().size()];double[] hint=new double[x.length];
  for(int i=0;i<x.length;i++){String id=f.recipes().get(i).id();long n=witness.getOrDefault(id,0L);x[i]=s.makeIntVar(profile.equals("fixed_witness")?n:0,profile.equals("fixed_witness")?n:upper.get(id),"x"+i);hint[i]=n;}
  for(String k:FusionProbe.keys(f)){var row=s.makeConstraint((k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L),Double.POSITIVE_INFINITY,k);for(int i=0;i<x.length;i++){var r=f.recipes().get(i);row.setCoefficient(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}}
  if(profile.equals("with_hint"))s.setHint(x,hint);
  String version=s.solverVersion();long built=System.nanoTime();var status=s.solve();long end=System.nanoTime();Map<String,Long> counts=new LinkedHashMap<>();
  if(status==MPSolver.ResultStatus.OPTIMAL||status==MPSolver.ResultStatus.FEASIBLE)for(int i=0;i<x.length;i++)counts.put(f.recipes().get(i).id(),Math.round(x[i].solutionValue()));
  s.delete();return Map.of("status",status.name(),"version",version,"settings_accepted",settings,"ms",(end-start)/1e6,"solve_ms",(end-built)/1e6,"counts",counts);
 }
 public static void main(String[]args)throws Exception{
  Loader.loadNativeLibraries();Path in=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
  for(Path p:Files.list(in).sorted().toList()){
   var m=FusionProbe.map(new FusionProbe.Json(Files.readString(p)).value());var f=FusionProbe.fixture(m);var upper=FusionProbe.amounts(m.get("count_upper"));var witness=FusionProbe.amounts(m.get("audit_count_witness"));
   for(String profile:List.of("default","tight","presolve_off","fixed_witness","with_hint")){
    var result=solve(f,upper,witness,profile);var row=Map.of("case",f.name(),"profile",profile,"scip",result);
    Files.writeString(out.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+profile+" "+result.get("status"));System.out.flush();
   }
  }
 }
}
