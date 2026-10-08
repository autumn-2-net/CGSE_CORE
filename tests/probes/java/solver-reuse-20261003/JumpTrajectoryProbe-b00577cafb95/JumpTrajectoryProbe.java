package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class JumpTrajectoryProbe {
    static long assertions, compared;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static PlanningBudget budget(){return new PlanningBudget(0,50_000_000,64L<<20,()->false,System::nanoTime);}
    static void same(Object a,Object b)throws Exception{
        for(String key:List.of("values","residual","scores","weights","jumps","dirty","violated","moves","bumps","pairs","pairMode","last","initialized","work"))
            check(Objects.deepEquals(JumpGainProbe.field(a,key),JumpGainProbe.field(b,key)),"trajectory differs "+key+" sample="+compared);
        compared++;
    }
    public static void main(String[]args)throws Exception{
        Random random=new Random(673684325);
        for(int t=0;t<60;t++){
            int n=3+random.nextInt(12);var lo=new BigInteger[n];var hi=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=BigInteger.valueOf(random.nextInt(7)-3);hi[i]=lo[i].add(BigInteger.ONE);}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<8;r++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++){int c=random.nextInt(10)-5;if(c==0)c=1;terms.put(i,BigInteger.valueOf(c));}rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(random.nextInt(41)-20)));}
            var first=budget();var second=budget();var third=budget();long external=0;
            try(var a=new CountJump(rows,lo,hi,first,1024).retained();var b=new CountJumpReference(rows,lo,hi,second,1024).retained();var c=new CountJump(rows,lo,hi,third,1024).retained()){
                external=(64L<<20)-third.reservedBytes();third.reserve(external);
                for(int k=0;k<1000;k++){
                    boolean doneA=a.step(),doneB=b.step(),doneC=c.step();
                    check(doneA==doneB&&doneA==doneC,"completion changed");
                    // No zero/fixed/dead variables: indexed neighbors retain exactly
                    // the old charged visitation sequence and every floating operation.
                    same(a,b);same(a,c);
                    check(Arrays.equals(a.counts(),b.counts())&&Arrays.equals(a.counts(),c.counts()),"witness changed");
                    if(doneA){if(!a.paused())break;a.resume(4096);b.resume(4096);c.resume(4096);}
                }
            } finally {third.release(external);}
            check(first.reservedBytes()==0&&second.reservedBytes()==0&&third.reservedBytes()==0,"leak");
        }
        System.out.println("PASS samples=60 compared_states="+compared+" assertions="+assertions);
    }
}
