package org.cgse.core;
import java.util.*;import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;
public final class MassRandomProbe {
 static Fixture generate(int seed){
  Fixture f=FuzzWide.generate(seed);return new Fixture("mass_"+seed,f.recipes(),f.stock(),f.target(),f.amount(),false);
 }
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);out=root.resolve("fixtures");Files.createDirectories(out);
  int first=Integer.parseInt(args[1]),count=Integer.parseInt(args[2]);
  for(int i=0;i<10;i++)run(ContrastProbe.dag(30),1000,false);
  int bad=0,limits=0,miss=0;
  for(int seed=first;seed<first+count;seed++){
   Fixture f=generate(seed);Oracle oracle=null;String truth="UNKNOWN";
   try{oracle=bfs(f);truth=oracle.feasible()?"SAT":"UNSAT";}catch(AssertionError e){if(!e.getMessage().contains("oracle unexpectedly too large"))throw e;limits++;}
   Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("seed",seed);row.put("truth",truth);
   if(oracle!=null){row.put("states",oracle.states());row.put("witness",oracle.witness());}
   boolean interesting=false;
   try{
    var checked=run(f,100,false);var g=checked.result();row.put("screen_ms",g.ms());row.put("screen_status",g.status());
    if(Set.of("TIMEOUT","SEARCH_LIMIT","UNKNOWN").contains(g.status())||truth.equals("SAT")&&!feasible(g)){g=run(f,3000,false).result();row.put("retried",true);}
    row.put("cgse",BatchProbe.result(g));
    boolean wrong=truth.equals("UNSAT")&&feasible(g)||truth.equals("SAT")&&Set.of("MISSING_INPUT","MISSING_SEED","INFEASIBLE").contains(g.status());
    if(wrong){bad++;row.put("mismatch",true);}if(truth.equals("SAT")&&!feasible(g))miss++;
    interesting=wrong||!Set.of("FEASIBLE","FEASIBLE_NOT_PROVEN_OPTIMAL","MISSING_INPUT","MISSING_SEED","INFEASIBLE").contains(g.status())||g.ms()>100;
   }catch(Throwable e){row.put("error",e.toString());bad++;interesting=true;}
   // A predeclared 1/40 systematic sample plus every inconclusive or slow case.
   if((seed-first)%40==0||truth.equals("UNKNOWN")||interesting){save(f,json(row));row.put("saved",true);row.put("systematic_sample",(seed-first)%40==0);}
   Files.writeString(root.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
   if((seed-first+1)%100==0){System.out.println("PROGRESS "+(seed-first+1)+" bad="+bad+" misses="+miss+" oracle_limits="+limits);System.out.flush();}
  }
 }
}
