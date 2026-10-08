package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
public final class RewriteReview {
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static void flatten(PlanStep step,List<String> out){
        if(step instanceof PlanStep.Batch b){for(long n=0;n<b.runs();n++)out.add(b.recipe());}
        else if(step instanceof PlanStep.Repeat r){for(long n=0;n<r.times();n++)flatten(r.body(),out);}
        else for(var child:((PlanStep.Sequence)step).children())flatten(child,out);
    }
    static PlanStep random(Random rng,int depth,List<PlanStep> pool){
        if(!pool.isEmpty()&&rng.nextInt(5)==0)return pool.get(rng.nextInt(pool.size()));
        PlanStep result;
        if(depth==0||rng.nextInt(3)==0)result=new PlanStep.Batch("r"+rng.nextInt(4),rng.nextInt(4));
        else if(rng.nextBoolean())result=new PlanStep.Repeat(random(rng,depth-1,new ArrayList<>()),rng.nextInt(3));
        else{var children=new ArrayList<PlanStep>();for(int n=rng.nextInt(4);n>0;n--)children.add(random(rng,depth-1,pool));result=new PlanStep.Sequence(children);}
        if(pool.size()<8)pool.add(result);
        return result;
    }
    public static void main(String[] args){
        Random rng=new Random(6927001);
        Function<PlanStep.Batch,PlanStep> replace=b->b.recipe().equals("r0")?new PlanStep.Sequence(List.of(new PlanStep.Batch("A",b.runs()),new PlanStep.Batch("B",b.runs()))):b.recipe().equals("r1")?new PlanStep.Sequence(List.of()):b;
        for(int trial=0;trial<2000;trial++){
            PlanStep source=random(rng,5,new ArrayList<>());
            var expected=new ArrayList<String>();oracle(source,expected);
            var b=budget();PlanStep actual=PlanRewrite.batches(source,replace,b);
            var flat=new ArrayList<String>();flatten(actual,flat);
            if(!expected.equals(flat))throw new AssertionError("Changed firing order");
            long memory=b.reservedBytes();
            if(PlanRewrite.batches(actual,x->x,b)!=actual||b.reservedBytes()!=memory)throw new AssertionError("No-op changed identity or leaked traversal memory");
        }
        PlanStep shared=new PlanStep.Batch("r0",1);
        for(int i=0;i<70;i++)shared=new PlanStep.Sequence(List.of(shared,shared));
        int[] visits={0};
        PlanStep huge=PlanRewrite.batches(shared,b->{visits[0]++;return replace.apply(b);},budget());
        if(visits[0]!=1||!PlanCountComputation.of(huge).equals(Map.of("A",BigInteger.ONE.shiftLeft(70),"B",BigInteger.ONE.shiftLeft(70))))throw new AssertionError("Lost sharing");
        PlanStep deep=new PlanStep.Batch("r0",1);
        for(int i=0;i<20000;i++)deep=new PlanStep.Sequence(List.of(deep));
        if(!PlanCountComputation.of(PlanRewrite.batches(deep,replace,budget())).equals(Map.of("A",BigInteger.ONE,"B",BigInteger.ONE)))throw new AssertionError("Deep rewrite");
        var limited=new PlanningBudget(0,20_000_000,1024,()->false,System::nanoTime);
        try{PlanRewrite.batches(deep,x->x,limited);throw new AssertionError("Expected local memory limit");}
        catch(PlanningBudget.Exhausted expected){if(expected.limit()!=PlanningBudget.Limit.MEMORY_LIMIT)throw expected;}
        if(limited.reservedBytes()!=0)throw new AssertionError("Abandoned rewrite leaked workspace");
        PlanStep first=new PlanStep.Batch("r0",1);
        for(int i=0;i<50;i++)first=new PlanStep.Sequence(List.of(first));
        var partial=new PlanningBudget(0,20_000_000,32768,()->false,System::nanoTime);
        try{PlanRewrite.batches(new PlanStep.Sequence(List.of(first,deep)),replace,partial);throw new AssertionError("Expected partial rewrite limit");}
        catch(PlanningBudget.Exhausted expected){if(expected.limit()!=PlanningBudget.Limit.MEMORY_LIMIT)throw expected;}
        if(partial.reservedBytes()!=0)throw new AssertionError("Abandoned partial result retained memory");
        System.out.println("PASS: 2000 independent firing-order rewrites; shared 2^70 counts, 20000 depth, no-op identities and failed workspace release");
    }
    static void oracle(PlanStep source,List<String> out){
        if(source instanceof PlanStep.Batch b){if(b.recipe().equals("r0")){for(long i=0;i<b.runs();i++)out.add("A");for(long i=0;i<b.runs();i++)out.add("B");}else if(!b.recipe().equals("r1")){for(long i=0;i<b.runs();i++)out.add(b.recipe());}}
        else if(source instanceof PlanStep.Repeat r){for(long i=0;i<r.times();i++)oracle(r.body(),out);}
        else for(var s:((PlanStep.Sequence)source).children())oracle(s,out);
    }
}
