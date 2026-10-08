package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class DivingProbe {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
 static ExactLinearProgram.Constraint row(int a,int b,int c){return new ExactLinearProgram.Constraint(Map.of(0,BigInteger.valueOf(a),1,BigInteger.valueOf(b)),BigInteger.valueOf(c));}
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,BigInteger[] p){
  for(int i=0;i<p.length;i++)if(p[i].compareTo(lo[i])<0||hi[i]!=null&&p[i].compareTo(hi[i])>0)return false;
  for(var r:rows){var s=Z;for(var t:r.terms().entrySet())s=s.add(t.getValue().multiply(p[t.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;
 }
 public static void main(String[]args){
  Random rng=new Random(1032026);int solved=0,feasible=0,unknown=0,attempted=0,nontrivial=0;
  for(int sample=0;sample<1200;sample++){
   int n=2+rng.nextInt(5);var rows=new ArrayList<ExactLinearProgram.Constraint>();
   BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],witness=new BigInteger[n];
   for(int i=0;i<n;i++){lo[i]=Z;hi[i]=sample%3==0?null:BigInteger.valueOf(6);witness[i]=BigInteger.valueOf(rng.nextInt(7));}
   for(int r=0;r<n+4;r++){Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=Z;for(int i=0;i<n;i++){int a=rng.nextInt(13)-6;if(a==0)continue;terms.put(i,BigInteger.valueOf(a));rhs=rhs.add(BigInteger.valueOf(a).multiply(witness[i]));}rows.add(new ExactLinearProgram.Constraint(terms,rhs.add(BigInteger.valueOf(rng.nextInt(4)))));}
   var b=new PlanningBudget(0,2000000,64L<<20,()->false,System::nanoTime);
   var constraints=new ArrayList<>(rows);for(int i=0;i<n;i++)if(hi[i]!=null)constraints.add(new ExactLinearProgram.Constraint(Map.of(i,O),hi[i]));
   BigInteger[] objective=new BigInteger[n];Arrays.fill(objective,O.negate());ExactRational[] point;
   try(var lp=new ExactLinearProgram(n,constraints,objective,b)){while(!lp.step()){}check(lp.result()==ExactLinearProgram.Result.OPTIMAL,"funded lp");point=lp.point();}
   feasible++;
   if(Arrays.stream(point).allMatch(ExactRational::integral))continue;
   attempted++;long before=b.reservedBytes();
   try(var dive=new CountDiving(rows,lo,hi,point,b)){while(!dive.step()){}var v=dive.counts();if(v!=null){check(valid(rows,lo,hi,v),"invalid witness "+sample);solved++;if(b.diagnostics().contains("relaxations=")&&!b.diagnostics().contains("relaxations=0"))nontrivial++;}else unknown++;}
   check(b.reservedBytes()==before,"memory leaked "+sample);
  }
  System.out.println("PASS funded="+feasible+" fractional="+attempted+" solved="+solved+" unknown="+unknown+" nontrivial="+nontrivial+" false_witness=0 memory_leaks=0");
 }
}

