package org.cgse.core;
import java.math.BigInteger;import java.nio.file.*;import java.util.*;import com.google.gson.Gson;
public final class PbDecisionImportProbe {
 static long checks,imports,pausedImports,liveImports;static BigInteger b(long v){return BigInteger.valueOf(v);}static void ok(boolean p,String w){checks++;if(!p)throw new AssertionError(w);}
 static ExactLinearProgram.Constraint row(Map<Integer,BigInteger>a,long u){return new ExactLinearProgram.Constraint(a,b(u));}
 public static void main(String[]args)throws Exception{var archive=new CountProof.Journal(128L<<20);var level=CountCdcl.class.getDeclaredField("level");level.setAccessible(true);
  for(int sample=0;sample<300;sample++){int n=24;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));var rows=new ArrayList<ExactLinearProgram.Constraint>();
   rows.add(row(Map.of(0,b(1),1,b(-1)),0));rows.add(row(Map.of(0,b(1),1,b(1)),1));
   var a=new LinkedHashMap<Integer,BigInteger>();for(int i=2;i<n;i++)a.put(i,b(-1));rows.add(row(a,-8));a=new LinkedHashMap<>();for(int i=2;i<n;i++)a.put(i,b(1));rows.add(row(a,15));Collections.shuffle(rows,new Random(sample));
   var budget=new PlanningBudget(0,2_000_000,32L<<20,()->false,System::nanoTime);budget.proofJournal(archive);
   try(var s=new CountCdcl(rows,lo,hi,budget,1024).retained()){
    for(int k=0;level.getInt(s)==0;k++){ok(k<1000,"no decision");if(s.step()){ok(s.paused(),"completed before decision");s.resume(1);}}
    liveImports++;
    if(sample%2==0){while(!s.step()){}ok(s.paused(),"expected paused decision");pausedImports++;}
    var fact=new CountConflict(List.of(row(Map.of(0,b(-1)),-1)));
    ok(s.learn(fact),"valid root implication refused");imports++;ok(!s.learn(fact),"duplicate imported");
    if(s.paused())s.resume(1);int hops=0;while(true){while(!s.step()){}if(!s.paused())break;ok(hops++<100000,"continuation stuck");s.resume(1+sample%23);}
    ok(s.counts()!=null,"SAT excluded");ok(s.counts()[0].signum()==0,"imported unit lost");CountBenchmark.verify(rows,lo,hi,s.counts());
   }ok(budget.reservedBytes()==0,"leak");
  }
  archive.write(Path.of(args[0],"pb-decision-imports.bin"));var report=Map.of("assertions",checks,"imports",imports,"liveImports",liveImports,"pausedImports",pausedImports);Files.writeString(Path.of(args[0],"pb-decision-imports.json"),new Gson().toJson(report));System.out.println(report);
 }
}
