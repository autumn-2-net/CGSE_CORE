package org.cgse.core;
import java.math.BigInteger;import java.util.*;
public final class ReductionBenefitProbe {
    static void run(String name,List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high){
        var budget=new PlanningBudget(0,10_000_000,()->false);try(var r=new CountReduction(rows,low,high,budget)){while(!r.step()){}System.out.println(name+" vars="+r.variables()+" rows="+r.rows().size()+" lower="+Arrays.toString(r.lower())+" upper="+Arrays.toString(r.upper())+" work="+budget.nodes()+" diagnostics="+budget.diagnostics());}if(budget.reservedBytes()!=0)throw new AssertionError("memory");
    }
    public static void main(String[]args){var rows=new ArrayList<ExactLinearProgram.Constraint>();ResidueProbe.equality(rows,ResidueProbe.row(16,6,10));run("nonunit",rows,new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO},new BigInteger[]{BigInteger.TEN,BigInteger.ONE});rows.clear();ResidueProbe.equality(rows,ResidueProbe.row(1,1,-4,0));ResidueProbe.equality(rows,ResidueProbe.row(3,1,0,-6));run("crt",rows,new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO},new BigInteger[]{BigInteger.TEN,BigInteger.TWO,BigInteger.TWO});}
}
