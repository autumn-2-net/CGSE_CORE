package org.cgse.core;
import java.math.*;import java.util.*;import java.lang.reflect.*;
public final class TransferOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var s=Z;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(x[e.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 static ExactLinearProgram.Constraint row(long rhs,long...c){var map=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<c.length;i++)if(c[i]!=0)map.put(i,BigInteger.valueOf(c[i]));return new ExactLinearProgram.Constraint(map,BigInteger.valueOf(rhs));}
 static Object field(Object obj,String name)throws Exception{var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
 static void cache(CountJump jump)throws Exception{int initialized=(int)field(jump,"initialized");var rows=(List<?>)field(jump,"rows");if(initialized!=rows.size())return;var dirty=(BitSet)field(jump,"dirty");var jumps=(BigInteger[])field(jump,"jumps");var scores=(double[])field(jump,"scores");var method=CountJump.class.getDeclaredMethod("score",int.class,BigInteger.class);method.setAccessible(true);for(int i=0;i<jumps.length;i++)if(!dirty.get(i)&&jumps[i]!=null){double exact=(double)method.invoke(jump,i,jumps[i]);if(!Double.isFinite(scores[i])||Math.abs(exact-scores[i])>1e-8*Math.max(1,Math.abs(exact)))throw new AssertionError("stale jump score "+scores[i]+" vs "+exact);}}
 public static void main(String[] args)throws Exception{
  var rng=new Random(928171);long assignments=0,solutions=0;int jumped=0,cuts=0,closed=0;
  for(int sample=0;sample<2000;sample++){
   int n=2+rng.nextInt(5);BigInteger[] low=new BigInteger[n],high=new BigInteger[n],plant=new BigInteger[n];int[] widths=new int[n];int total=1;
   BigInteger offset=sample%7==0?O.shiftLeft(90):Z,scale=sample%9==0?O.shiftLeft(160):O;
   for(int i=0;i<n;i++){low[i]=offset.add(BigInteger.valueOf(rng.nextInt(3)));widths[i]=1+rng.nextInt(3);high[i]=low[i].add(BigInteger.valueOf(widths[i]-1));plant[i]=low[i].add(BigInteger.valueOf(rng.nextInt(widths[i])));total*=widths[i];}
   List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
   for(int r=0;r<2+rng.nextInt(8);r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.valueOf(rng.nextInt(5)-(sample%2)).multiply(scale);for(int i=0;i<n;i++){var c=BigInteger.valueOf(rng.nextInt(11)-5).multiply(scale);if(c.signum()!=0)terms.put(i,c);rhs=rhs.add(c.multiply(plant[i]));}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
   var feasible=new ArrayList<BigInteger[]>();for(int code=0;code<total;code++){int z=code;var x=low.clone();for(int i=0;i<n;i++){x[i]=x[i].add(BigInteger.valueOf(z%widths[i]));z/=widths[i];}assignments++;if(valid(rows,x)){feasible.add(x);solutions++;}}
   var budget=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);var journal=new CountProof.Journal(16L<<20);budget.proofJournal(journal);
   try(var p=new CountProbing(rows,low,high,budget)){while(!p.step()){}for(var x:feasible)if(!valid(p.cuts(),x))throw new AssertionError("probe removed valid assignment "+sample);if(p.infeasible()&&!feasible.isEmpty())throw new AssertionError("false proof");if(p.infeasible())closed++;cuts+=p.cuts().size();for(var proof:journal.entries())if(CountProof.verify(proof,1000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("uncertified cut");}
   try(var jump=new CountJump(rows,low,high,budget,32768)){int steps=0;while(!jump.step()){if(++steps%100==0)cache(jump);}var x=jump.counts();if(x!=null){jumped++;if(feasible.isEmpty()||!valid(rows,x))throw new AssertionError("bad witness "+sample);for(int i=0;i<n;i++)if(x[i].compareTo(low[i])<0||x[i].compareTo(high[i])>0)throw new AssertionError("bad domain");}}
   if(budget.reservedBytes()!=0)throw new AssertionError("leaked memory "+sample+": "+budget.reservedBytes());
  }
  // Either binary choice forces x >= 1; ordinary interval propagation does not.
  var rows=List.of(row(-1,-1,1,-1),row(-1,-1,-1,1),row(1,0,1,1),row(-1,0,-1,-1));
  var low=new BigInteger[]{Z,Z,Z};var high=new BigInteger[]{O,O,O};
  var b=new PlanningBudget(0,20000000,256L<<20,()->false,System::nanoTime);
  try(var p=new CountProbing(rows,low,high,b)){while(!p.step()){}if(p.cuts().isEmpty())throw new AssertionError("expected failed-bound exclusion");}
  for(int limit=1;limit<256;limit++){var budget=new PlanningBudget(0,limit,256L<<20,()->false,System::nanoTime);try(var p=new CountProbing(rows,low,high,budget)){while(!p.step()){}}catch(PlanningBudget.Exhausted expected){}try(var p=new CountJump(rows,low,high,budget,100000)){while(!p.step()){}}catch(PlanningBudget.Exhausted expected){}if(budget.reservedBytes()!=0)throw new AssertionError("cutoff leak "+limit);}
  System.out.println("PASS 2000 exact finite-domain models; assignments="+assignments+" feasible="+solutions+" verified_probe_bounds="+cuts+" closed="+closed+" jump_witnesses="+jumped+"; incremental score checks, 90-bit offsets/160-bit coefficients and 255 cancellation boundaries");
 }
}
