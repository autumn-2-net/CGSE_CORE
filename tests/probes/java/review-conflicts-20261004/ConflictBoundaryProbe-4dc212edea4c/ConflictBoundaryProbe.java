package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class ConflictBoundaryProbe {
    static long checks;
    static BigInteger bi(long n){return BigInteger.valueOf(n);}
    static void check(boolean p,String label){checks++;if(!p)throw new AssertionError(label);}
    static ExactLinearProgram.Constraint row(int id,BigInteger coefficient,BigInteger rhs){return new ExactLinearProgram.Constraint(Map.of(id,coefficient),rhs);}
    static boolean violates(CountConflict c,BigInteger[] point){for(var row:c.assumptions()){var value=BigInteger.ZERO;for(var term:row.terms().entrySet())value=value.add(term.getValue().multiply(point[term.getKey()]));if(value.compareTo(row.upper())>0)return false;}return true;}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static CountModelViews.View view(String name,CountMapping mapping,BigInteger[] lo,BigInteger[] hi){return new CountModelViews.View(name,List.of(),lo,hi,new CountModelViews.Shape(lo.length,0,1,lo.length),null,CountModelViews.Semantics.EQUIVALENT,mapping);}
    static void hugeUnbounded(){
        for(int k=0;k<64;k++){
            var scale=BigInteger.ONE.shiftLeft(120+k*7).add(bi(17));if(k%2!=0)scale=scale.negate();var shift=bi(k-5);var threshold=BigInteger.valueOf(Long.MAX_VALUE).multiply(bi(100)).add(bi(k));
            var cutoff=threshold.divide(bi(3));var low=new BigInteger[]{bi(0),bi(0)};var high=new BigInteger[2];
            var mapping=new CountMapping(List.of(new CountMapping.Expression(Map.of(0,scale),shift),CountMapping.Expression.variable(1)));
            var source=view("stride",mapping,low,high);var original=view("original",null,low,high);var b=budget();
            try(var pool=CountViewConflicts.create(b)){
                // Under z <= cutoff, z+w >= threshold forbids w <= threshold-cutoff-1.
                var conflict=new CountConflict(List.of(row(1,bi(1),threshold.subtract(cutoff).subtract(bi(1)))));
                pool.publish(source,low,new BigInteger[]{cutoff,null},List.of(conflict),0,new Object());check(pool.version()==1,"unbounded fact omitted");
                var lifted=new ArrayList<CountConflict>();pool.transfer(original,0,new Object(),c->{lifted.add(c);return true;});
                var pulled=new ArrayList<CountConflict>();pool.transfer(source,0,new Object(),c->{pulled.add(c);return true;});
                for(int delta=-2;delta<=2;delta++)for(int side=-2;side<=2;side++){
                    var z=cutoff.add(bi(delta));var w=threshold.subtract(cutoff).add(bi(side));boolean expected=delta<=0&&side<=-1;
                    check(violates(lifted.get(0),new BigInteger[]{scale.multiply(z).add(shift),w})==expected,"huge lift rounded");
                    check(violates(pulled.get(0),new BigInteger[]{z,w})==expected,"huge pull rounded");
                }
            }check(b.reservedBytes()==0,"unbounded leak");
        }
    }
    static void unsupportedAndConstants(){
        var b=budget();var lo=new BigInteger[]{bi(0),bi(0)};var hi=new BigInteger[]{bi(10),bi(10)};
        try(var pool=CountViewConflicts.create(b)){
            var original=view("original",null,lo,hi);var c=new CountConflict(List.of(row(0,bi(1),bi(3)),row(1,bi(1),bi(2))));pool.publish(original,lo,hi,List.of(c),0,new Object());check(pool.version()==1,"missing original");
            var constants=new CountMapping(List.of(new CountMapping.Expression(Map.of(),bi(4)),CountMapping.Expression.variable(0)));
            var fixed=view("fixed",constants,new BigInteger[]{bi(0)},new BigInteger[]{bi(10)});var received=new ArrayList<CountConflict>();pool.transfer(fixed,0,new Object(),v->{received.add(v);return true;});check(received.isEmpty(),"false fixed atom silently dropped");
            constants=new CountMapping(List.of(new CountMapping.Expression(Map.of(),bi(3)),CountMapping.Expression.variable(0)));fixed=view("fixed",constants,new BigInteger[]{bi(0)},new BigInteger[]{bi(10)});pool.transfer(fixed,0,new Object(),v->{received.add(v);return true;});check(received.size()==1&&received.get(0).assumptions().size()==1,"true fixed atom not simplified");
            var coupled=view("coupled",new CountMapping(List.of(new CountMapping.Expression(Map.of(0,bi(1),1,bi(1)),bi(0)),CountMapping.Expression.variable(1))),lo,hi);received.clear();pool.transfer(coupled,0,new Object(),v->{received.add(v);return true;});check(received.isEmpty(),"multivariate atom weakened to unary");
            int before=pool.version();var unsupported=new CountConflict(List.of(new ExactLinearProgram.Constraint(Map.of(0,bi(1),1,bi(1)),bi(3))));pool.publish(original,lo,hi,List.of(unsupported),0,new Object());check(pool.version()==before,"unsupported clause entered");
            var giant=new CountConflict(List.of(row(0,bi(1),BigInteger.ONE.shiftLeft(2048))));pool.publish(original,lo,hi,List.of(giant),0,new Object());check(pool.version()==before,"giant export entered");
            var contradictory=new CountConflict(List.of(row(0,bi(-1),bi(-4)),row(0,bi(1),bi(3))));pool.publish(original,lo,hi,List.of(contradictory),0,new Object());check(pool.version()==before,"vacuous conjunction entered");
        }check(b.reservedBytes()==0,"unsupported leak");
    }
    static void boundedPool(){var b=budget();var lo=new BigInteger[]{bi(0)};var hi=new BigInteger[]{bi(10000)};try(var p=CountViewConflicts.create(b)){var view=view("original",null,lo,hi);for(int i=0;i<1000;i++)p.publish(view,lo,hi,List.of(new CountConflict(List.of(row(0,bi(1),bi(i))))),0,new Object());check(p.version()==64,"unbounded fact pool");}check(b.reservedBytes()==0,"bounded pool leak");}
    public static void main(String[]args)throws Exception{hugeUnbounded();unsupportedAndConstants();boundedPool();var result=Map.of("huge_unbounded_cases",64,"assertions",checks,"fixed_and_unsupported",8,"bounded_pool",1000);Files.writeString(Path.of(args[0],"conflict-boundaries.json"),new Gson().toJson(result));System.out.println(result);}
}
