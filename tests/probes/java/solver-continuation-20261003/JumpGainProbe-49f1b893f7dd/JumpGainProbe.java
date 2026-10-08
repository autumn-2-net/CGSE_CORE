package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;

public final class JumpGainProbe {
    static long assertions, cacheChecks, systems, witnesses, oracleSat, oracleUnsat, declined;
    static final BigInteger Z=BigInteger.ZERO,O=BigInteger.ONE;
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static ExactLinearProgram.Constraint row(long upper,long...a){var m=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<a.length;i++)if(a[i]!=0)m.put(i,b(a[i]));return new ExactLinearProgram.Constraint(m,b(upper));}
    static boolean holds(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){BigInteger sum=Z;for(var e:r.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;}
    static boolean oracle(List<ExactLinearProgram.Constraint> rows,BigInteger[]lo,BigInteger[]hi){return enumerate(rows,lo,hi,lo.clone(),0);}
    static boolean enumerate(List<ExactLinearProgram.Constraint> rows,BigInteger[]lo,BigInteger[]hi,BigInteger[]x,int i){if(i==x.length)return holds(rows,x);for(x[i]=lo[i];x[i].compareTo(hi[i])<=0;x[i]=x[i].add(O))if(enumerate(rows,lo,hi,x,i+1))return true;return false;}
    @SuppressWarnings("unchecked") static void caches(CountJump jump)throws Exception{
        var values=(BigInteger[])field(jump,"values");if(values.length==0)return;
        var lo=(BigInteger[])field(jump,"lower");var hi=(BigInteger[])field(jump,"upper");
        var residual=(BigInteger[])field(jump,"residual");var proposed=(BigInteger[])field(jump,"jumps");
        var flags=(boolean[])field(jump,"cacheGains");var dirty=(BitSet)field(jump,"dirty");
        var neighbors=(List<List<?>>)field(jump,"neighbors");var rows=(List<ExactLinearProgram.Constraint>)field(jump,"rows");
        int initialized=(int)field(jump,"initialized");
        for(int r=0;r<initialized;r++){
            BigInteger actual=rows.get(r).upper().negate(), maximum=Z;
            for(var e:rows.get(r).terms().entrySet()){
                int i=e.getKey();actual=actual.add(e.getValue().multiply(values[i]));
                if(maximum!=null&&e.getValue().signum()!=0){BigInteger bound=e.getValue().signum()>0?hi[i]:lo[i];maximum=bound==null?null:maximum.add(e.getValue().multiply(bound));}
            }
            if(maximum!=null&&maximum.compareTo(rows.get(r).upper())<=0)actual=Z;
            check(actual.equals(residual[r]),"residual cache");
            for(Object edge:neighbors.get(r)){
                int id=(int)field(edge,"variable");BigInteger coefficient=(BigInteger)field(edge,"coefficient");BigInteger gain=(BigInteger)field(edge,"gain");
                check(!lo[id].equals(hi[id])&&coefficient.signum()!=0,"fixed/zero edge retained");
                if(!flags[r]){check(gain==null,"uncached row has gain");declined++;}
                if(initialized==rows.size()&&!dirty.get(id)&&hi[id]!=null&&hi[id].subtract(lo[id]).equals(O)&&proposed[id]!=null&&flags[r]){
                    BigInteger exact=residual[r].add(coefficient.multiply(proposed[id].subtract(values[id]))).max(Z).subtract(residual[r].max(Z));
                    check(exact.equals(gain),"stale clean gain row="+r+" var="+id);cacheChecks++;
                }
            }
        }
    }
    static void run(List<ExactLinearProgram.Constraint> rows,BigInteger[]lo,BigInteger[]hi,boolean exact,long memory,int steps)throws Exception{
        boolean sat=exact&&oracle(rows,lo,hi);if(exact){if(sat)oracleSat++;else oracleUnsat++;}systems++;
        var budget=new PlanningBudget(0,10_000_000,memory,()->false,System::nanoTime);
        try(var jump=new CountJump(rows,lo,hi,budget,1024).retained()){
            for(int k=0;k<steps;k++){
                boolean done=jump.step();caches(jump);
                if(done){if(jump.counts()!=null){var x=jump.counts();check(!exact||sat,"oracle UNSAT witness");check(holds(rows,x),"bad witness");for(int i=0;i<x.length;i++)check(x[i].compareTo(lo[i])>=0&&(hi[i]==null||x[i].compareTo(hi[i])<=0),"outside domain");witnesses++;break;}if(!jump.paused())break;jump.resume(1024);}
            }
        }
        check(budget.reservedBytes()==0,"leak");
    }
    public static void main(String[]args)throws Exception{
        Random random=new Random(0x634db891L);
        for(int t=0;t<600;t++){
            int n=2+random.nextInt(5);BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=b(random.nextInt(7)-3);hi[i]=lo[i].add(b(t%3==0?random.nextInt(4):random.nextInt(2)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+random.nextInt(8);r++){long[]a=new long[n];for(int i=0;i<n;i++)a[i]=random.nextInt(9)-4;rows.add(row(random.nextInt(21)-10,a));}
            if(t%10==0){var m=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)m.put(i,Z);rows.add(new ExactLinearProgram.Constraint(m,b(3)));}
            run(rows,lo,hi,true,64L<<20,180);
        }
        var unsat=List.of(row(-1,-1,-1,0),row(-1,0,-1,-1),row(-1,-1,0,-1),row(1,1,1,1));
        BigInteger[]lo={Z,Z,Z},hi={O,O,O};
        run(unsat,lo,hi,true,64L<<20,6000); // Pair restart, tentative first moves and rollback.
        for(int bytes=5000;bytes<12000;bytes+=17)run(unsat,lo,hi,true,bytes,110);
        BigInteger huge=O.shiftLeft(2048),base=O.shiftLeft(160);
        run(List.of(new ExactLinearProgram.Constraint(Map.of(0,huge,1,huge.negate()),huge.negate())),new BigInteger[]{base,base},new BigInteger[]{base.add(O),base.add(O)},true,64L<<20,300);
        run(List.of(row(-7,-2,1),row(-4,1,-2)),new BigInteger[]{Z,Z},new BigInteger[]{null,null},false,64L<<20,500);
        check(cacheChecks>1000&&declined>1000&&oracleSat>0&&oracleUnsat>0,"missing coverage");
        System.out.println("PASS systems="+systems+" oracle_sat="+oracleSat+" oracle_unsat="+oracleUnsat+" witnesses="+witnesses+" exact_gain_checks="+cacheChecks+" declined_checks="+declined+" assertions="+assertions);
    }
}
