package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class StrideProbe {
    static long assertions;static int transformed;
    static void check(boolean test,String message){assertions++;if(!test)throw new AssertionError(message);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static void equation(List<ExactLinearProgram.Constraint> rows,Map<Integer,BigInteger> terms,BigInteger rhs){
        rows.add(new ExactLinearProgram.Constraint(terms,rhs));var reverse=new LinkedHashMap<Integer,BigInteger>();terms.forEach((i,a)->reverse.put(i,a.negate()));rows.add(new ExactLinearProgram.Constraint(reverse,rhs.negate()));
    }
    static boolean accepts(List<ExactLinearProgram.Constraint> rows,BigInteger[] point){
        for(var r:rows){BigInteger sum=BigInteger.ZERO;for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(point[e.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;
    }
    static Set<List<BigInteger>> enumerate(List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high,java.util.function.UnaryOperator<BigInteger[]> lift){
        Set<List<BigInteger>> result=new HashSet<>();walk(0,new BigInteger[low.length],rows,low,high,lift,result);return result;
    }
    static void walk(int id,BigInteger[] point,List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high,java.util.function.UnaryOperator<BigInteger[]> lift,Set<List<BigInteger>> result){
        if(id==point.length){if(accepts(rows,point))result.add(List.of(lift.apply(point.clone())));return;}
        for(BigInteger v=low[id];v.compareTo(high[id])<=0;v=v.add(BigInteger.ONE)){point[id]=v;walk(id+1,point,rows,low,high,lift,result);}
    }
    static void finite(){
        for(int seed=0;seed<900;seed++){
            Random random=new Random(seed*197L);int n=2+seed%3;BigInteger[] low=new BigInteger[n],high=new BigInteger[n],known=new BigInteger[n];
            for(int i=0;i<n;i++){low[i]=BigInteger.valueOf(random.nextInt(5)-3);high[i]=low[i].add(BigInteger.valueOf(random.nextInt(6)));known[i]=low[i].add(BigInteger.valueOf(random.nextInt(high[i].subtract(low[i]).intValue()+1)));}
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int e=0;e<1+seed%3;e++){Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger rhs=BigInteger.ZERO;
                for(int i=0;i<n;i++){BigInteger a=BigInteger.valueOf((random.nextBoolean()?1:-1)*(2+random.nextInt(15)));terms.put(i,a);rhs=rhs.add(a.multiply(known[i]));}
                if(seed%7==0)rhs=rhs.add(BigInteger.ONE);equation(rows,terms,rhs);
            }
            if(seed%2==0)rows.add(new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE,n-1,BigInteger.ONE),known[0].add(known[n-1])));
            var expected=enumerate(rows,low,high,p->p);var b=budget();if(seed%3==0)b.proofJournal(new CountProof.Journal(64));
            try(var residues=new CountResiduePresolve(rows,low,high,b)){
                while(!residues.step()){}try(var stride=CountStride.create(rows,residues,b)){
                    if(stride!=null){transformed++;var actual=enumerate(stride.rows(),stride.lower(),stride.upper(),stride::expand);check(actual.equals(expected),"bijection "+seed+" expected="+expected+" actual="+actual);}
                }
            }
            check(b.reservedBytes()==0,"finite leak "+seed);
        }
        check(transformed>100,"too few transformed cases "+transformed);
    }
    static void unboundedAndLarge(){
        for(BigInteger scale:List.of(BigInteger.ONE,BigInteger.ONE.shiftLeft(200).add(BigInteger.valueOf(7)))){
            var rows=new ArrayList<ExactLinearProgram.Constraint>();equation(rows,Map.of(0,scale.multiply(BigInteger.valueOf(6)),1,scale.multiply(BigInteger.TEN)),scale.multiply(BigInteger.valueOf(100)));
            var b=budget();var low=new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO};var high=new BigInteger[2];
            try(var residues=new CountResiduePresolve(rows,low,high,b)){
                while(!residues.step()){}try(var stride=CountStride.create(rows,residues,b)){
                    check(stride!=null,"missing unbounded stride");check(stride.upper()[0]==null&&stride.upper()[1]==null,"lost unbounded domain");
                    var original=enumerate(rows,low,new BigInteger[]{BigInteger.valueOf(20),BigInteger.valueOf(20)},p->p);
                    var projected=enumerate(stride.rows(),stride.lower(),new BigInteger[]{BigInteger.valueOf(20),BigInteger.valueOf(20)},stride::expand);
                    check(projected.equals(original),"unbounded slice differs");
                }
            }
            check(b.reservedBytes()==0,"unbounded leak");
        }
    }
    static void integration(){
        for(int variant=0;variant<20;variant++){
            var rows=new ArrayList<ExactLinearProgram.Constraint>();equation(rows,Map.of(0,BigInteger.valueOf(6),1,BigInteger.TEN),BigInteger.valueOf(100));
            var low=new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO};var high=new BigInteger[]{BigInteger.valueOf(100),BigInteger.valueOf(100)};
            var b=budget();
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){
                reduction.retainStrideView();while(!reduction.step()){}views.addReduced(reduction);
                var view=views.available().stream().filter(v->v.name().equals("stride")).findFirst().orElseThrow();
                try(var quick=new CountQuickSolve(view.rows(),view.lower(),view.upper(),b)){while(!quick.step()){}var point=views.restoreAndCheck(view,quick.counts());check(point!=null,"stride not solved");check(accepts(rows,point),"bad integrated lift");}
            }
            check(b.reservedBytes()==0,"integrated leak");
        }
    }
    public static void main(String[]args)throws Exception{finite();unboundedAndLarge();integration();var report=Map.of("random_models",900,"transformed",transformed,"assertions",assertions);Files.writeString(Path.of(args[0],"stride-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
