package org.cgse.core;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
public final class PbImportProbe {
 static long checks, cases, imports, midDecision;
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static void ok(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
 static ExactLinearProgram.Constraint row(Map<Integer,BigInteger>a,BigInteger u){return new ExactLinearProgram.Constraint(a,u);}
 static ExactLinearProgram.Constraint atom(int i,BigInteger x,boolean min){return row(Map.of(i,b(min?-1:1)),min?x.negate():x);}
 static boolean valid(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,int bits){for(var r:rows){var s=BigInteger.ZERO;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(lo[e.getKey()].add(b((bits>>e.getKey())&1))));if(s.compareTo(r.upper())>0)return false;}return true;}
 static void finish(CountCdcl s){while(true){while(!s.step()){}if(!s.paused())return;s.resume(4096);}}
 static void randomCases(Path out)throws Exception{
  var random=new Random(213223);
  var journal=new CountProof.Journal(128L<<20);
  var level=CountCdcl.class.getDeclaredField("level");level.setAccessible(true);
  for(int sample=0;sample<600;sample++){
   int n=8+sample%4;var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=sample%4==0?b(-7):sample%3==0?BigInteger.TEN.pow(35).add(b(i)):b(i);hi[i]=lo[i].add(BigInteger.ONE);}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int j=0;j<8+sample%13;j++){var a=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int c=random.nextInt(7)-3;if(c!=0)a.put(i,b(c));}var u=b(random.nextInt(12)-3);for(var e:a.entrySet())u=u.add(e.getValue().multiply(lo[e.getKey()]));rows.add(row(a,u));}
   var forbidden=new ArrayList<Integer>();boolean expected=false;for(int bits=0;bits<(1<<n);bits++)if(valid(rows,lo,bits))expected=true;else forbidden.add(bits);
   Collections.shuffle(forbidden,random);var budget=budget();if(sample%4!=0)budget.proofJournal(journal);
   try(var s=new CountCdcl(rows,lo,hi,budget,1024).retained()){
    boolean done=false;if(sample%2==0){for(int i=0;i<rows.size()+4;i++)if(s.step()){done=true;break;}}
    if(!done||s.paused()){
     if(level.getInt(s)>0)midDecision++;
     for(int bits:forbidden.subList(0,Math.min(6,forbidden.size()))){var a=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<n;i++)a.add(atom(i,lo[i].add(b((bits>>i)&1)),((bits>>i)&1)==1));if(s.learn(new CountConflict(a)))imports++;}
     if(s.paused())s.resume(4096);finish(s);
    }
    ok(s.counts()!=null||s.infeasible(),"undecided "+sample+" "+budget.diagnostics());ok((s.counts()!=null)==expected,"wrong "+sample+" "+budget.diagnostics());if(s.counts()!=null)CountBenchmark.verify(rows,lo,hi,s.counts());cases++;
   }ok(budget.reservedBytes()==0,"random leak");
  }
  ok(midDecision>0,"no live decision imports");ok(imports>1000,"no imports");
  journal.write(out.resolve("pb-imports.bin"));for(var p:CountProof.read(out.resolve("pb-imports.bin")).entries())ok(CountProof.verify(p,2_000_000)==CountProof.Verdict.VERIFIED,"invalid proof "+p.scope());
 }
 static void guarded(Path out)throws Exception{
  var archive=new CountProof.Journal(64L<<20);
  for(int seed=0;seed<200;seed++){
   var budget=budget();budget.proofJournal(archive);var lo=new BigInteger[]{b(0),b(0)};var hi=new BigInteger[]{b(1),b(1)};
   var rows=List.of(row(Map.of(0,b(2),1,b(2)),b(3)),atom(1,b(1),true));
   var root=new CountModelViews.View("root",rows,lo,hi,new CountModelViews.Shape(2,3,1,2),null,CountModelViews.Semantics.EQUIVALENT,null);
   // Pull back to x=1-z (negative scale) and leave the second coordinate unchanged.
   boolean reversed=seed%2==0;var map=reversed?new CountMapping(List.of(new CountMapping.Expression(Map.of(0,b(-1)),b(1)),CountMapping.Expression.variable(1))):null;
   var targetRows=new ArrayList<ExactLinearProgram.Constraint>();for(var r:rows)targetRows.add(map==null?r:map.row(r,budget));
   var target=new CountModelViews.View("target",targetRows,lo,hi,new CountModelViews.Shape(2,3,1,2),null,CountModelViews.Semantics.EQUIVALENT,map);
   try(var pool=CountViewConflicts.create(budget,root);var s=new CountCdcl(targetRows,lo,hi,budget,4096).retained()){
    // In the exporting local x=1 domain, y=1 is impossible. The receiver must keep x=0,y=1.
    pool.publish(root,new BigInteger[]{b(1),b(0)},hi,List.of(new CountConflict(List.of(atom(1,b(1),true)))),0,new Object());
    ok(pool.transfer(target,0,s,s::learn)==1,"guarded import refused");ok(!s.learn(new CountConflict(List.of(atom(1,b(1),true)))),"unproved exclusion accepted");
    finish(s);ok(s.counts()!=null,"guard dropped");CountBenchmark.verify(targetRows,lo,hi,s.counts());ok(s.counts()[0].equals(b(reversed?1:0)),"wrong guarded witness");
   }ok(budget.reservedBytes()==0,"guarded leak");
  }
  archive.write(out.resolve("pb-guarded.bin"));var loaded=CountProof.read(out.resolve("pb-guarded.bin"));for(var p:loaded.entries())ok(CountProof.verify(p,2_000_000)==CountProof.Verdict.VERIFIED,"invalid guarded proof");for(var p:loaded.affineConflicts())ok(CountAffineConflictProof.verify(p,2_000_000)==CountProof.Verdict.VERIFIED,"invalid mapped proof");
 }
 public static void main(String[]args)throws Exception{var out=Path.of(args[0]);randomCases(out);guarded(out);var report=Map.of("assertions",checks,"randomCases",cases,"imports",imports,"midDecisionImports",midDecision,"guardedCases",200);Files.writeString(out.resolve("pb-import-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
