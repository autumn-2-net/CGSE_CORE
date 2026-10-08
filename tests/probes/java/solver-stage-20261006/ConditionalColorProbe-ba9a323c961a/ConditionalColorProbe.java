package org.cgse.core;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
public final class ConditionalColorProbe {
 static int checks,cases;static void ok(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 static BigInteger b(long x){return BigInteger.valueOf(x);}
 static void solve(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,boolean expected,long limit){
  var budget=new PlanningBudget(0,limit,64L<<20,()->false,System::nanoTime);
  try(var s=new CountCardinalitySearch(rows,lo,hi,budget,limit)){
   while(!s.step()){};if(s.counts()!=null)CountBenchmark.verify(rows,lo,hi,s.counts());
   ok(s.counts()!=null||s.infeasible(),"undecided "+budget.diagnostics());ok((s.counts()!=null)==expected,"wrong "+budget.diagnostics());cases++;
  }ok(budget.reservedBytes()==0,"leak");
 }
 public static void main(String[]args)throws Exception{
  var random=new Random(672923);
  for(int t=0;t<240;t++){
   int n=8+t%4;var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=t%3==0?BigInteger.TEN.pow(35).add(b(i)):b(random.nextInt(5)-2);hi[i]=lo[i].add(BigInteger.ONE);}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int j=0;j<28;j++){var a=new LinkedHashMap<Integer,BigInteger>();while(a.size()<3){int i=random.nextInt(n);a.put(i,b(-1));}BigInteger upper=b(-1);for(var e:a.entrySet())upper=upper.add(e.getValue().multiply(lo[e.getKey()]));rows.add(new ExactLinearProgram.Constraint(a,upper));}
   var sum=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)sum.put(i,BigInteger.ONE);BigInteger upper=b(t%(n+1));for(var x:lo)upper=upper.add(x);rows.add(new ExactLinearProgram.Constraint(sum,upper));
   if(t%2==0){var a=Map.of(0,b(3),1,b(-2),2,b(2));BigInteger u=b(random.nextInt(6)-2);for(var e:a.entrySet())u=u.add(e.getValue().multiply(lo[e.getKey()]));rows.add(new ExactLinearProgram.Constraint(a,u));}
   boolean sat=false;for(int bits=0;bits<(1<<n);bits++){boolean valid=true;for(var row:rows){BigInteger total=BigInteger.ZERO;for(var e:row.terms().entrySet())total=total.add(e.getValue().multiply(lo[e.getKey()].add(b((bits>>e.getKey())&1))));if(total.compareTo(row.upper())>0){valid=false;break;}}if(valid){sat=true;break;}}
   Collections.shuffle(rows,random);solve(rows,lo,hi,sat,5_000_000);
  }
  var all=JsonParser.parseString(Files.readString(Path.of(".local/solver-stage-20261006/hard-unsat.json"))).getAsJsonArray();
  for(var raw:all){var c=raw.getAsJsonObject();if(!c.get("id").getAsString().contains("stein"))continue;int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var rr:c.getAsJsonArray("rows")){var rr2=rr.getAsJsonObject();var a=new LinkedHashMap<Integer,BigInteger>();for(var e:rr2.getAsJsonObject("terms").entrySet())a.put(Integer.valueOf(e.getKey()),e.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(a,rr2.get("upper").getAsBigInteger()));}long before=System.nanoTime();solve(rows,lo,hi,false,10_000_000);System.out.println("stein_ms="+(System.nanoTime()-before)/1e6);}
  System.out.println("cases="+cases+" assertions="+checks);Files.writeString(Path.of(args[0],"conditional-color-probe.json"),"{\"cases\":"+cases+",\"assertions\":"+checks+"}");
 }
}
