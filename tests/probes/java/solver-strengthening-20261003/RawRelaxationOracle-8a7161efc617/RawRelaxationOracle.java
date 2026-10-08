package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public class RawRelaxationOracle {
    public static void main(String[] args)throws Exception {
        var zero=BigInteger.ZERO;var one=BigInteger.ONE;var two=BigInteger.TWO;
        var rows=List.of(new ExactLinearProgram.Constraint(Map.of(0,two,1,two.negate()),one),
                new ExactLinearProgram.Constraint(Map.of(0,two.negate(),1,two),one));
        var recipes=List.of(new GraphRecipe<String>("a","a",List.of(new GraphRecipe.Slot<>("A",1L)),Map.of("T",2L)),
                new GraphRecipe<String>("b","b",List.of(new GraphRecipe.Slot<>("B",1L)),Map.of("T",2L)));
        var budget=new PlanningBudget(0,20_000_000,64L<<20,()->false,System::nanoTime);
        Map<String,Long> stock=Map.of("A",4L,"B",4L);
        try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"T",1,stock,Map.of(),Set.of(),Set.of(),false,budget);
            var execution=new CountExecution<>(model,budget);
            var branch=new IntegerCountBranch<>(model,execution,"T",1,stock,Map.of(),Set.of(),false,false,budget,0,List.of())){
            branch.reduction=new CountReduction(rows,new BigInteger[]{zero,zero},new BigInteger[]{two,two},budget);
            while(!branch.reduction.step()){}
            if(branch.reduction.variables()!=1)throw new AssertionError("integer equality must be recognized");
            // This point satisfies the raw LP, but x=y holds only after integer
            // rounding. x=0 therefore does not certify integral original counts.
            var point=new ExactRational[]{ExactRational.ZERO,new ExactRational(one,two)};
            for(var row:rows){var sum=ExactRational.ZERO;for(var t:row.terms().entrySet())sum=sum.add(point[t.getKey()].multiply(ExactRational.of(t.getValue())));if(sum.compareTo(ExactRational.of(row.upper()))>0)throw new AssertionError("not an LP witness");}
            var choice=IntegerCountBranch.class.getDeclaredMethod("fractionalChoice",ExactRational[].class);choice.setAccessible(true);
            if(!choice.invoke(branch,(Object)point).equals(1))throw new AssertionError("fractional eliminated coordinate was hidden");
            branch.feedbackRegion=true;branch.obbtTried=true;branch.gomoryTried=true;branch.incumbentTried=true;
            var split=IntegerCountBranch.class.getDeclaredMethod("branchPoint",ExactRational[].class);split.setAccessible(true);split.invoke(branch,(Object)point);
            if(branch.state!=IntegerCountBranch.State.SPLIT||branch.children.size()!=2||branch.counts!=null)throw new AssertionError("fractional vector was accepted or dropped");
            for(int x=0;x<=2;x++)for(int y=0;y<=2;y++){
                int accepted=0;for(var child:branch.children)if(StrengtheningOracle.valid(child,new BigInteger[]{zero,zero},new BigInteger[]{two,two},new BigInteger[]{BigInteger.valueOf(x),BigInteger.valueOf(y)}))accepted++;
                if(accepted!=1)throw new AssertionError("branches must partition original integer vectors");
            }
        }
        if(budget.reservedBytes()!=0)throw new AssertionError("workspace leak");
        System.out.println("PASS raw LP fractional eliminated coordinate; both original-coordinate branches partition every integer vector");
    }
}
