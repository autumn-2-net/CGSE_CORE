package org.cgse.core;
import java.nio.file.*;import java.util.*;
import com.google.ortools.Loader;import com.google.ortools.sat.*;import com.google.ortools.linearsolver.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
public final class TemporalNativeProbe {
 static void validate(Fixture f,List<String> path){Map<String,GraphRecipe<String>> rs=new HashMap<>();f.recipes().forEach(r->rs.put(r.id(),r));verify(f,summary(new PlanStep.Sequence(path.stream().<PlanStep>map(r->new PlanStep.Batch(r,1)).toList()),rs));}
 static Map<String,Object> cp(Fixture f,int h,long ms){
  long start=System.nanoTime();CpModel m=new CpModel();List<String> ks=new ArrayList<>(new TreeSet<>(FusionProbe.keys(f)));IntVar[][] q=new IntVar[h+1][ks.size()];BoolVar[][] a=new BoolVar[h][f.recipes().size()];
  for(int k=0;k<ks.size();k++){String key=ks.get(k);long inc=0;for(var r:f.recipes())inc=Math.max(inc,r.outputs().getOrDefault(key,0L));long max=f.stock().getOrDefault(key,0L)+h*inc;for(int t=0;t<=h;t++)q[t][k]=m.newIntVar(0,max,"q"+t+"_"+k);m.addEquality(q[0][k],f.stock().getOrDefault(key,0L));}
  for(int t=0;t<h;t++){
   for(int i=0;i<f.recipes().size();i++)a[t][i]=m.newBoolVar("a"+t+"_"+i);m.addLessOrEqual(LinearExpr.sum(a[t]),1);
   for(int k=0;k<ks.size();k++){String key=ks.get(k);var bal=LinearExpr.newBuilder().add(q[t][k]);
    for(int i=0;i<f.recipes().size();i++){var r=f.recipes().get(i);long in=r.inputs().getOrDefault(key,0L),out=r.outputs().getOrDefault(key,0L);if(in>0)m.addGreaterOrEqual(q[t][k],LinearExpr.term(a[t][i],in));if(out!=in)bal.addTerm(a[t][i],out-in);}m.addEquality(q[t+1][k],bal);
   }
  }
  m.addGreaterOrEqual(q[h][ks.indexOf(f.target())],f.amount());long built=System.nanoTime();CpSolver s=new CpSolver();s.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(ms/1000.0);var status=s.solve(m);long end=System.nanoTime();List<String> path=new ArrayList<>();
  if(status==CpSolverStatus.OPTIMAL||status==CpSolverStatus.FEASIBLE){for(int t=0;t<h;t++)for(int i=0;i<f.recipes().size();i++)if(s.booleanValue(a[t][i]))path.add(f.recipes().get(i).id());validate(f,path);}
  return Map.of("status",status.name(),"ms",(end-start)/1e6,"build_ms",(built-start)/1e6,"solve_ms",(end-built)/1e6,"actions",path,"validation",m.validate());
 }
 static Map<String,Object> scip(Fixture f,int h,long ms){
  long start=System.nanoTime();var s=MPSolver.createSolver("SCIP");s.setNumThreads(1);s.setTimeLimit(ms);List<String> ks=new ArrayList<>(new TreeSet<>(FusionProbe.keys(f)));MPVariable[][] q=new MPVariable[h+1][ks.size()];MPVariable[][] a=new MPVariable[h][f.recipes().size()];
  for(int k=0;k<ks.size();k++){String key=ks.get(k);long inc=0;for(var r:f.recipes())inc=Math.max(inc,r.outputs().getOrDefault(key,0L));long max=f.stock().getOrDefault(key,0L)+h*inc;for(int t=0;t<=h;t++)q[t][k]=s.makeIntVar(t==0?f.stock().getOrDefault(key,0L):0,t==0?f.stock().getOrDefault(key,0L):max,"q"+t+"_"+k);}
  for(int t=0;t<h;t++){
   var atMost=s.makeConstraint(0,1);for(int i=0;i<f.recipes().size();i++){a[t][i]=s.makeBoolVar("a"+t+"_"+i);atMost.setCoefficient(a[t][i],1);}
   for(int k=0;k<ks.size();k++){String key=ks.get(k);var bal=s.makeConstraint(0,0);bal.setCoefficient(q[t+1][k],1);bal.setCoefficient(q[t][k],-1);
    for(int i=0;i<f.recipes().size();i++){var r=f.recipes().get(i);long in=r.inputs().getOrDefault(key,0L),out=r.outputs().getOrDefault(key,0L);if(in>0){var c=s.makeConstraint(0,Double.POSITIVE_INFINITY);c.setCoefficient(q[t][k],1);c.setCoefficient(a[t][i],-in);}if(out!=in)bal.setCoefficient(a[t][i],in-out);}
   }
  }
  var goal=s.makeConstraint(f.amount(),Double.POSITIVE_INFINITY);goal.setCoefficient(q[h][ks.indexOf(f.target())],1);long built=System.nanoTime();var status=s.solve();long end=System.nanoTime();List<String> path=new ArrayList<>();
  if(status==MPSolver.ResultStatus.OPTIMAL||status==MPSolver.ResultStatus.FEASIBLE){for(int t=0;t<h;t++)for(int i=0;i<f.recipes().size();i++){double v=a[t][i].solutionValue();if(Math.abs(v-Math.rint(v))>1e-5)throw new AssertionError("Fractional action");if(v>0.5)path.add(f.recipes().get(i).id());}validate(f,path);}s.delete();
  return Map.of("status",status.name(),"ms",(end-start)/1e6,"build_ms",(built-start)/1e6,"solve_ms",(end-built)/1e6,"actions",path);
 }
 public static void main(String[]args)throws Exception{
  Loader.loadNativeLibraries();Path input=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);long ms=Long.parseLong(args[2]);Set<String> select=args.length>3&&!args[3].equals("all")?new HashSet<>(Files.readAllLines(Path.of(args[3]))):null;
  for(Path p:Files.list(input).sorted().toList()){
   var f=FusionProbe.fixture(FusionProbe.map(new FusionProbe.Json(Files.readString(p)).value()));if(select!=null&&!select.contains(f.name()))continue;int h=Math.toIntExact(f.stock().get("F"));for(var r:f.recipes())if(r.inputs().getOrDefault("F",0L)!=1||r.outputs().getOrDefault("F",0L)!=0)throw new AssertionError("Horizon not complete");
   Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("horizon",h);row.put("cp_sat",cp(f,h,ms));row.put("scip",scip(f,h,ms));Files.writeString(out.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println(f.name()+" "+((Map<?,?>)row.get("cp_sat")).get("status")+" "+((Map<?,?>)row.get("scip")).get("status"));System.out.flush();
  }
 }
}
