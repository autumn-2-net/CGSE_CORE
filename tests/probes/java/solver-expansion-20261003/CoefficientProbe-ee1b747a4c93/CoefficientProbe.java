package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class CoefficientProbe {
    static int systems, changed, assertions;
    static void check(boolean good,String why){assertions++;if(!good)throw new AssertionError(why);}
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static void sample(ExactLinearProgram.Constraint row,BigInteger[] low,BigInteger[] high){
        systems++;var budget=new PlanningBudget(0,10_000_000,32L<<20,()->false,System::nanoTime);
        var reduced=CountIntegerCoefficients.simplify(row,low,high,budget);if(!row.equals(reduced))changed++;
        for(var point:ResidueProbe.points(low,high))check(ResidueProbe.holds(List.of(row),point)==ResidueProbe.holds(List.of(reduced),point),"non-equivalent row="+row+" new="+reduced+" point="+Arrays.toString(point)+" low="+Arrays.toString(low)+" high="+Arrays.toString(high));
        try(var views=CountModelViews.create(List.of(row),low,high,budget)){
            views.compileLight();for(var view:views.available())for(var point:ResidueProbe.points(low,high)){
                check(ResidueProbe.holds(List.of(row),point)==ResidueProbe.holds(view.rows(),point),"light view equivalence");
                if(ResidueProbe.holds(view.rows(),point))check(Arrays.equals(views.restoreAndCheck(view,point),point),"view changed coordinates");
            }
        }
        check(budget.reservedBytes()==0,"coefficient memory leak");
    }
    public static void main(String[]args){
        sample(ResidueProbe.row(7,4,6),new BigInteger[]{b(0),b(0)},new BigInteger[]{b(1),b(3)});
        sample(ResidueProbe.row(8,2,6),new BigInteger[]{b(0),b(0)},new BigInteger[]{b(1),b(3)});
        sample(ResidueProbe.row(-8,-2,-6),new BigInteger[]{b(0),b(0)},new BigInteger[]{b(1),b(3)});
        sample(ResidueProbe.row(0,5,12,18),new BigInteger[]{b(-2),b(-1),b(-1)},new BigInteger[]{b(-1),b(3),b(3)});
        Random random=new Random(39181003);for(int trial=0;trial<12000;trial++){
            int n=2+random.nextInt(3);var low=new BigInteger[n];var high=new BigInteger[n];long[] coefficients=new long[n];long gcd=2+random.nextInt(15);int exceptional=random.nextInt(n);
            for(int i=0;i<n;i++){low[i]=b(random.nextInt(5)-2);high[i]=low[i].add(b(1+random.nextInt(i==exceptional?4:3)));coefficients[i]=(random.nextInt(11)-5)*(i==exceptional?1:gcd);}
            sample(ResidueProbe.row(random.nextInt(101)-50,coefficients),low,high);
        }
        var budget=new PlanningBudget(0,1_000_000,()->false);var journal=new CountProof.Journal(1_000_000);budget.proofJournal(journal);
        var rows=new ArrayList<ExactLinearProgram.Constraint>();ResidueProbe.equality(rows,ResidueProbe.row(1,1,-4,0));ResidueProbe.equality(rows,ResidueProbe.row(3,1,0,-6));
        try(var p=new CountResiduePresolve(rows,new BigInteger[]{b(0),b(0),b(0)},new BigInteger[]{b(30),b(8),b(8)},budget)){while(!p.step()){}}
        for(var proof:journal.entries())check(CountProof.verify(proof,1_000_000)==CountProof.Verdict.VERIFIED,"residue certificate rejected");
        check(budget.reservedBytes()==0,"proof leak");
        System.out.println("PASS systems="+systems+" strengthened="+changed+" assertions="+assertions+" proof_certificates="+journal.entries().size());
    }
}
