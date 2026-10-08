package org.cgse.core;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public class GmiSafetyProbe {
    static long assignments;static int checks,proofs;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static boolean holds(CountProof.Row row,BigInteger[]x){BigInteger a=b(0);for(var t:row.terms().entrySet())a=a.add(t.getValue().multiply(x[t.getKey()]));return a.compareTo(row.upper())<=0;}
    public static void main(String[]args)throws Exception {
        Files.createDirectories(Path.of("build/test-artifacts/rounding"));
        var random=new Random(1003492);var journal=new CountProof.Journal(64L<<20);
        for(int iteration=0;iteration<2500;iteration++){
            int n=1+random.nextInt(4),m=1+random.nextInt(4);var low=new ArrayList<BigInteger>();for(int i=0;i<n;i++)low.add(b(random.nextInt(7)-3));
            List<CountProof.Row>rows=new ArrayList<>();List<BigInteger>weights=new ArrayList<>();Map<Integer,BigInteger>sum=new TreeMap<>();BigInteger bound=b(0);
            for(int k=0;k<m;k++){Map<Integer,BigInteger>a=new TreeMap<>();for(int i=0;i<n;i++){var v=b(random.nextInt(25)-12);if(v.signum()!=0)a.put(i,v);}var row=new CountProof.Row(a,b(random.nextInt(49)-24));rows.add(row);var w=b(random.nextInt(7)-3);weights.add(w);bound=bound.add(w.multiply(row.upper()));for(var t:a.entrySet())sum.merge(t.getKey(),t.getValue().multiply(w),BigInteger::add);}
            for(int i=0;i<n;i++){rows.add(new CountProof.Row(Map.of(i,b(-1)),low.get(i).negate()));weights.add(b(0));bound=bound.subtract(sum.getOrDefault(i,b(0)).multiply(low.get(i)));}
            var divisor=b(2+random.nextInt(17));if(iteration%17==0){var scale=BigInteger.TEN.pow(40);sum.replaceAll((i,v)->v.multiply(scale));bound=bound.multiply(scale);divisor=divisor.multiply(scale);for(int i=0;i<m;i++)weights.set(i,weights.get(i).multiply(scale));}
            List<ExactLinearProgram.Constraint> shiftedRows=new ArrayList<>();for(var row:rows){var rhs0=row.upper();for(var term:row.terms().entrySet())rhs0=rhs0.subtract(term.getValue().multiply(low.get(term.getKey())));shiftedRows.add(new ExactLinearProgram.Constraint(row.terms(),rhs0));} var shifted=CountMirCuts.tableau(shiftedRows,weights,sum,bound,divisor,()->{});if(shifted==null)continue;
            var rhs=shifted.upper();for(var t:shifted.terms().entrySet())rhs=rhs.add(t.getValue().multiply(low.get(t.getKey())));var consequence=new CountProof.Row(shifted.terms(),rhs);
            var proof=new CountProof.Rounding("mir-fuzz",n,rows,weights,divisor,low,consequence,CountProof.RoundingKind.TABLEAU);proofs++;
            check(CountProof.verify(proof,100000)==CountProof.Verdict.VERIFIED,"valid certificate");
            var bad=new CountProof.Rounding("tampered",n,rows,weights,divisor,low,new CountProof.Row(consequence.terms(),rhs.add(b(1))),CountProof.RoundingKind.TABLEAU);
            check(CountProof.verify(bad,100000)==CountProof.Verdict.INVALID,"tampered consequence accepted");
            check(CountProof.verify(proof,1)==CountProof.Verdict.INCOMPLETE,"cutoff is not proof");
            var x=low.toArray(BigInteger[]::new);while(true){boolean feasible=true;for(var row:rows)if(!holds(row,x)){feasible=false;break;}if(feasible){assignments++;check(holds(consequence,x),"MIR cut removes solution");}int i=0;for(;i<n;i++){if(x[i].compareTo(low.get(i).add(b(5)))<0){x[i]=x[i].add(b(1));break;}x[i]=low.get(i);}if(i==n)break;}
            if(proofs<150)journal.add(proof);
        }
        var old=new CountProof.Rounding("plain",1,List.of(new CountProof.Row(Map.of(0,b(2)),b(3)),new CountProof.Row(Map.of(0,b(-1)),b(0))),List.of(b(1),b(0)),b(2),List.of(b(0)),new CountProof.Row(Map.of(0,b(1)),b(1)));
        check(CountProof.verify(old,100)==CountProof.Verdict.VERIFIED,"legacy rounding");journal.add(old);
        Path archive=Path.of("build/test-artifacts/rounding/gmi-proof.cgp");journal.write(archive);var recovered=CountProof.read(archive);check(recovered.rounding().equals(journal.rounding()),"new archive roundtrip");
        var legacy=new CountProof.Journal(1024*1024);legacy.add(old);Path oldArchive=Path.of("build/test-artifacts/rounding/gmi-legacy.cgp");legacy.write(oldArchive);check(CountProof.read(oldArchive).rounding().equals(legacy.rounding()),"old format roundtrip");
        System.out.println("PASS GMI proofs="+proofs+" feasibleAssignments="+assignments+" assertions="+checks);
    }
}
