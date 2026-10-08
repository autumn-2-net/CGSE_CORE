package org.cgse.core;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
public final class RetainedStructureProbe {
 static long checks,cases,pauses; static void ok(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
 static BigInteger b(long v){return BigInteger.valueOf(v);}
 record Model(String id,List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,boolean sat){}
 static PlanningBudget budget(long work){return new PlanningBudget(0,work,128L<<20,()->false,System::nanoTime);}
 static void solve(Model m,long initial,long quantum){var budget=budget(8_000_000);try(var s=new CountStructureSearch(m.rows,m.lo,m.hi,budget,initial).retained()){
  while(true){while(!s.step()){}if(!s.paused())break;pauses++;s.resume(quantum);}
  ok(s.infeasible()||s.counts()!=null,"undecided "+m.id+" "+budget.diagnostics());ok((s.counts()!=null)==m.sat,"wrong "+m.id);if(s.counts()!=null)CountBenchmark.verify(m.rows,m.lo,m.hi,s.counts());cases++;
 }ok(budget.reservedBytes()==0,"leak "+m.id);}
 static List<Model> hard()throws Exception{var result=new ArrayList<Model>();for(var raw:JsonParser.parseString(Files.readString(Path.of(".local/solver-stage-20261006/hard-unsat.json"))).getAsJsonArray()){
  var c=raw.getAsJsonObject();if(c.get("id").getAsString().contains("lseu"))continue;int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var rawRow:c.getAsJsonArray("rows")){var r=rawRow.getAsJsonObject();var a=new LinkedHashMap<Integer,BigInteger>();for(var e:r.getAsJsonObject("terms").entrySet())a.put(Integer.valueOf(e.getKey()),e.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(a,r.get("upper").getAsBigInteger()));}result.add(new Model(c.get("id").getAsString(),rows,lo,hi,false));
 }return result;}
 public static void main(String[]args)throws Exception{
  var random=new Random(1061637);for(int sample=0;sample<200;sample++){
   int n=8+sample%4;var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=sample%3==0?BigInteger.TEN.pow(40).add(b(i)):b(random.nextInt(9)-4);hi[i]=lo[i].add(BigInteger.ONE);}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int j=0;j<22;j++){var a=new LinkedHashMap<Integer,BigInteger>();while(a.size()<3)a.put(random.nextInt(n),b(-1));var u=b(-1);for(var e:a.entrySet())u=u.add(e.getValue().multiply(lo[e.getKey()]));rows.add(new ExactLinearProgram.Constraint(a,u));}
   var a=new LinkedHashMap<Integer,BigInteger>();var u=b(sample%(n+1));for(int i=0;i<n;i++){a.put(i,BigInteger.ONE);u=u.add(lo[i]);}rows.add(new ExactLinearProgram.Constraint(a,u));
   if(sample%2==0){a=new LinkedHashMap<>();for(int i=0;i<n;i++)a.put(i,b(random.nextInt(7)-3));u=b(random.nextInt(9)-3);for(var e:a.entrySet())u=u.add(e.getValue().multiply(lo[e.getKey()]));rows.add(new ExactLinearProgram.Constraint(a,u));}
   boolean sat=false;for(int mask=0;mask<(1<<n);mask++)if(PbImportProbe.valid(rows,lo,mask)){sat=true;break;}Collections.shuffle(rows,random);solve(new Model("random-"+sample,rows,lo,hi,sat),2048,1+sample%97);
  }
  for(var m:hard()){for(int q:new int[]{1,17,4096,65536})solve(m,16384,q);
   var budget=budget(20_000);boolean exhausted=false;try(var s=new CountStructureSearch(m.rows,m.lo,m.hi,budget,16384).retained()){
    while(true){while(!s.step()){}if(!s.paused())break;if(m.id.contains("stein")){while(budget.remainingWork()>0)budget.check();s.resume(4096);}else s.resume(4096);}
   }catch(PlanningBudget.Exhausted e){ok(e.limit()==PlanningBudget.Limit.SEARCH_LIMIT,"wrong limit");exhausted=true;}if(m.id.contains("stein"))ok(exhausted,"missing exhaustion");ok(budget.reservedBytes()==0,"limit leak");
   budget=budget(8_000_000);try(var s=new CountStructureSearch(m.rows,m.lo,m.hi,budget,16384).retained()){for(int k=0;k<100;k++){if(s.step())break;}budget.cancel();boolean cancelled=false;try{if(s.paused())s.resume(4096);while(!s.step()){};}catch(java.util.concurrent.CancellationException e){cancelled=true;}if(m.id.contains("stein"))ok(cancelled,"cancel ignored");}ok(budget.reservedBytes()==0,"cancel leak");
  }
  ok(pauses>100,"no meaningful continuations");var report=Map.of("assertions",checks,"cases",cases,"pauses",pauses);Files.writeString(Path.of(args[0],"retained-structure.json"),new Gson().toJson(report));System.out.println(report);
 }
}
