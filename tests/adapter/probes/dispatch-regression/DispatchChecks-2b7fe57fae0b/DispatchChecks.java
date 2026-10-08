import java.math.BigInteger;
import java.util.*;
import org.cgse.core.*;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphRuntimeTest;

public class DispatchChecks {
    static int checks;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static PlanStep random(Random r,int d){
        if(d==0||r.nextInt(3)==0)return new PlanStep.Batch("r"+r.nextInt(3),r.nextInt(5));
        if(r.nextBoolean())return new PlanStep.Repeat(random(r,d-1),r.nextInt(5));
        var a=new ArrayList<PlanStep>();for(int i=0,n=r.nextInt(4);i<n;i++)a.add(random(r,d-1));return new PlanStep.Sequence(a);
    }
    static void flat(PlanStep s,List<String> out){
        if(s instanceof PlanStep.Batch b)for(long i=0;i<b.runs();i++)out.add(b.recipe());
        else if(s instanceof PlanStep.Repeat r)for(long i=0;i<r.times();i++)flat(r.body(),out);
        else for(var child:((PlanStep.Sequence)s).children())flat(child,out);
    }
    public static void main(String[] args){
        GraphRuntimeTest.run();
        var random=new Random(6159);
        for(int t=0;t<2000;t++){
            PlanStep s=random(random,5);var reference=new ArrayList<String>();flat(s,reference);
            var cursor=new PlanCursor(s);int pos=0;
            while(cursor.current()!=null){var b=cursor.current();long n=1+random.nextInt((int)b.runs());
                for(int i=0;i<n;i++)check(reference.get(pos++).equals(b.recipe()),"cursor reordered different recipes");
                cursor.dispatched(n);var counts=cursor.remainingCountsExact();cursor=new PlanCursor(s,cursor.snapshot());
                check(counts.equals(cursor.remainingCountsExact()),"reload counts");
            }check(pos==reference.size(),"cursor dropped runs");
        }
        BigInteger max=BigInteger.valueOf(Long.MAX_VALUE);
        for(var count:List.of(max.add(BigInteger.valueOf(17)),max.multiply(max).add(BigInteger.TEN))){
            var step=PlanStep.batch("r",count);var cursor=new PlanCursor(step);
            check(cursor.current().runs()==Long.MAX_VALUE,"wide count lost batching");
            cursor.dispatched(1_000_000);cursor=new PlanCursor(step,cursor.snapshot());
            check(cursor.remainingCountsExact().get("r").equals(count.subtract(BigInteger.valueOf(1_000_000))),"wide exact count");
        }
        var literal=new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("r",2),new PlanStep.Repeat(new PlanStep.Batch("r",3),5))),Long.MAX_VALUE);
        var cursor=new PlanCursor(literal);
        check(cursor.current().runs()==Long.MAX_VALUE,"legacy repeat aggregation");
        cursor.dispatched(Long.MAX_VALUE);cursor=new PlanCursor(literal,cursor.snapshot());
        check(cursor.remainingCountsExact().get("r").equals(max.multiply(BigInteger.valueOf(16))),"legacy partial iteration exact");
        System.out.println("DISPATCH_PASS checks="+checks);
    }
}
