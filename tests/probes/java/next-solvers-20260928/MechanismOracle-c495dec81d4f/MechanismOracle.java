package org.cgse.core;
import java.math.*;import java.util.*;import java.nio.file.*;
public final class MechanismOracle {
 static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
 static boolean valid(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){var s=Z;for(var e:r.terms().entrySet())s=s.add(e.getValue().multiply(x[e.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 static ExactLinearProgram.Constraint b(int i,BigInteger v,boolean lo){return new ExactLinearProgram.Constraint(Map.of(i,lo?O.negate():O),lo?v.negate():v);}
 static PlanningBudget budget(long work){return new PlanningBudget(0,work,256L<<20,()->false,System::nanoTime);}
 public static void main(String[] args)throws Exception{var rng=new Random(812215);long assignments=0;int cuts=0,neighborhoods=0,probes=0;CountProof.Rounding saved=null;
  for(int sample=0;sample<3000;sample++){
   int n=2+rng.nextInt(6),total=1;BigInteger[] low=new BigInteger[n],high=new BigInteger[n],plant=new BigInteger[n];int[] sizes=new int[n];ExactRational[] point=new ExactRational[n];
   BigInteger scale=sample%11==0?O.shiftLeft(140):O;
   for(int i=0;i<n;i++){low[i]=sample%13==0?O.shiftLeft(90):BigInteger.valueOf(rng.nextInt(3));sizes[i]=1+rng.nextInt(4);high[i]=low[i].add(BigInteger.valueOf(sizes[i]-1));plant[i]=low[i].add(BigInteger.valueOf(rng.nextInt(sizes[i])));point[i]=ExactRational.of(low[i]).add(new ExactRational(BigInteger.valueOf(rng.nextInt((sizes[i]-1)*4+1)),BigInteger.valueOf(4)));total*=sizes[i];}
   var rows=new ArrayList<ExactLinearProgram.Constraint>();
   for(int r=0;r<2+rng.nextInt(10);r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.valueOf(rng.nextInt(3)-1).multiply(scale);for(int i=0;i<n;i++){var c=BigInteger.valueOf(rng.nextInt(9)-4).multiply(scale);if(c.signum()!=0)terms.put(i,c);rhs=rhs.add(c.multiply(plant[i]));}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
   var budget=budget(20000000);var journal=new CountProof.Journal(16L<<20);budget.proofJournal(journal);List<ExactLinearProgram.Constraint> generated;
   try(var p=new CountRoundingCuts(rows,low,point,budget)){while(!p.step()){}generated=p.cuts();cuts+=generated.size();}
   for(int code=0;code<total;code++){int z=code;var x=low.clone();for(int i=0;i<n;i++){x[i]=x[i].add(BigInteger.valueOf(z%sizes[i]));z/=sizes[i];}assignments++;if(valid(rows,x)&&!valid(generated,x))throw new AssertionError("excluded legal count "+sample);}
   for(var proof:journal.rounding()){if(CountProof.verify(proof,2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad rounding certificate "+sample);saved=proof;var broken=new CountProof.Rounding(proof.scope(),proof.variables(),proof.axioms(),proof.multipliers(),proof.divisor(),proof.lower(),new CountProof.Row(proof.consequence().terms(),proof.consequence().upper().subtract(O)));if(CountProof.verify(broken,2000000)!=CountProof.Verdict.INVALID)throw new AssertionError("tampered cut accepted");}
   try(var p=new CountNeighborhood(rows,low,high,point,budget)){while(!p.step()){}var x=p.counts();if(x!=null){neighborhoods++;if(!valid(rows,x))throw new AssertionError("bad neighborhood witness");for(int i=0;i<n;i++)if(x[i].compareTo(low[i])<0||x[i].compareTo(high[i])>0)throw new AssertionError("domain");}}
   for(var proof:journal.entries())if(CountProof.verify(proof,2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad local certificate");
   for(int i=0;i<n;i++)if(!point[i].integral()){try(var p=new CountBranchProbe(rows,low,high,point,i,budget)){while(!p.step()){}if(p.chosen()<0||p.chosen()>=n||point[p.chosen()].integral())throw new AssertionError("not a fractional decision");probes++;}break;}
   if(budget.reservedBytes()!=0)throw new AssertionError("memory leak "+sample+" "+budget.reservedBytes());
  }
  if(saved==null||cuts==0||neighborhoods==0)throw new AssertionError("untested mechanism");var archive=new CountProof.Journal(1<<20);archive.add(saved);var file=Path.of(args[0]);archive.write(file);var read=CountProof.read(file);if(read.rounding().size()!=1||CountProof.verify(read.rounding().get(0),2000000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("archive");
  var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.valueOf(3),1,BigInteger.valueOf(3)),BigInteger.valueOf(5)),new ExactLinearProgram.Constraint(Map.of(0,O.negate(),1,O.negate()),O.negate()));
  var low=new BigInteger[]{Z,Z};var high=new BigInteger[]{O,O};var point=new ExactRational[]{new ExactRational(O,BigInteger.TWO),new ExactRational(O,BigInteger.TWO)};
  for(int kind=0;kind<4;kind++)for(int limit=1;limit<512;limit++){int[] checkpoints={0};int stop=limit;var budget=new PlanningBudget(0,20000000,256L<<20,()->++checkpoints[0]>=stop,System::nanoTime);try{if(kind==0)try(var p=new CountCdcl(rows,low,high,budget,2000000)){while(!p.step()){}}else if(kind==1)try(var p=new CountRoundingCuts(rows,low,point,budget)){while(!p.step()){}}else if(kind==2)try(var p=new CountNeighborhood(rows,low,high,point,budget)){while(!p.step()){}}else try(var p=new CountBranchProbe(rows,low,high,point,0,budget)){while(!p.step()){}}}catch(PlanningBudget.Exhausted | java.util.concurrent.CancellationException expected){}if(budget.reservedBytes()!=0)throw new AssertionError("cutoff leak "+kind+" "+limit+" "+budget.reservedBytes());}
  System.out.println("PASS 3000 independently enumerated signed finite-domain models; assignments="+assignments+" rounding_cuts="+cuts+" neighborhood_witnesses="+neighborhoods+" branching_probes="+probes+"; 90-bit offsets, 140-bit coefficients, independent proofs, tampering rejection, CGP4 roundtrip, 2044 active cancellation boundaries");
 }
}
