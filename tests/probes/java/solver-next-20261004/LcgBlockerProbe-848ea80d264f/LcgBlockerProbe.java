package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
public final class LcgBlockerProbe {
    static final Constructor<?> literal, nogood;
    static final Method propagate;
    static long checks, units, conflicts, skipped;
    static {try{
        var l=Class.forName(CountLcg.class.getName()+"$Literal"); var n=Class.forName(CountLcg.class.getName()+"$Nogood");
        literal=l.getDeclaredConstructor(int.class,boolean.class,BigInteger.class);literal.setAccessible(true);
        nogood=n.getDeclaredConstructor(List.class,int.class,long.class);nogood.setAccessible(true);
        propagate=CountLcg.class.getDeclaredMethod("propagate",n);propagate.setAccessible(true);
    }catch(Exception e){throw new RuntimeException(e);}}
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static PlanningBudget budget(){return new PlanningBudget(0,Long.MAX_VALUE,64L<<20,()->false,()->0L);}
    public static void main(String[]args)throws Exception{
        var random=new Random(219042);
        for(int sample=0;sample<1200;sample++){
            int n=2+random.nextInt(20), length=1+random.nextInt(n);
            var terms=new ArrayList<Object>();var vars=new ArrayList<Integer>();for(int i=0;i<n;i++)vars.add(i);Collections.shuffle(vars,random);
            var up=new boolean[length];var threshold=new BigInteger[length];
            for(int i=0;i<length;i++){up[i]=random.nextBoolean();threshold[i]=b(random.nextInt(9)-4);terms.add(literal.newInstance(vars.get(i),up[i],threshold[i]));}
            Object clause=nogood.newInstance(List.copyOf(terms),2,0L);
            for(int step=0;step<40;step++){
                var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=b(random.nextInt(11)-5);hi[i]=step%7==0?null:lo[i].add(b(random.nextInt(6)));}
                int unknown=0,unknownIndex=-1;boolean falsified=false;
                for(int i=0;i<length;i++){
                    int id=vars.get(i);int truth=up[i]?(lo[id].compareTo(threshold[i])>=0?1:hi[id]!=null&&hi[id].compareTo(threshold[i])<0?0:-1):
                        (hi[id]!=null&&hi[id].compareTo(threshold[i])<=0?1:lo[id].compareTo(threshold[i])>0?0:-1);
                    if(truth==0)falsified=true;if(truth<0){unknown++;unknownIndex=i;}
                }
                var budget=budget();try(var solver=new CountLcg(List.of(),lo,hi,budget,100000)){
                    propagate.invoke(solver,clause);
                    var actualLo=(BigInteger[])field(solver,"low");var actualHi=(BigInteger[])field(solver,"high");
                    boolean actualConflict=field(solver,"conflict")!=null;
                    check(actualConflict==(!falsified&&unknown==0),"stale blocker missed conflict");
                    if(actualConflict)conflicts++;
                    else if(!falsified&&unknown==1){
                        units++;int id=vars.get(unknownIndex);BigInteger bound=threshold[unknownIndex].add(up[unknownIndex]?b(-1):b(1));
                        check(bound.equals(up[unknownIndex]?actualHi[id]:actualLo[id]),"stale blocker missed unit");
                    }else {skipped++;check(Arrays.equals(actualLo,lo)&&Arrays.equals(actualHi,hi),"false propagation");}
                }check(budget.reservedBytes()==0,"memory leak");
            }
        }
        int n=128;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(1));Arrays.fill(hi,b(2));lo[n-1]=lo[n-2]=b(0);
        var terms=new ArrayList<Object>();for(int i=0;i<n;i++)terms.add(literal.newInstance(i,true,b(1)));
        var budget=budget();long work;
        try(var solver=new CountLcg(List.of(),lo,hi,budget,10_000_000)){
            var clause=nogood.newInstance(List.copyOf(terms),2,0L);propagate.invoke(solver,clause);long before=budget.threadWork();
            for(int i=0;i<20000;i++)propagate.invoke(solver,clause);work=budget.threadWork()-before;
            check(field(solver,"conflict")==null,"false benchmark conflict");
        }check(budget.reservedBytes()==0,"benchmark leak");
        System.out.println("LcgBlockerProbe states=48000 units="+units+" conflicts="+conflicts+" skipped="+skipped+" checks="+checks+" blockedClauseVisits=20000 work="+work+" errors=0 leaks=0");
    }
}
