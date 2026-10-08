package org.cgse.core;
import java.math.BigInteger;import java.util.*;import java.lang.reflect.*;import java.util.concurrent.CancellationException;import java.util.concurrent.atomic.AtomicInteger;
public final class LazyLcgProbe {
    static int assertions,systems,sat,unsat,unknown,proofs,pauses,conflicts,jumps,restarts;static long work;
    static void check(boolean v,String s){assertions++;if(!v)throw new AssertionError(s);}
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static ExactLinearProgram.Constraint row(long rhs,long...a){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<a.length;i++)if(a[i]!=0)terms.put(i,b(a[i]));return new ExactLinearProgram.Constraint(terms,b(rhs));}
    static boolean holds(List<ExactLinearProgram.Constraint> rows,BigInteger[] p){for(var row:rows){BigInteger sum=b(0);for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(p[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;}
    static boolean oracle(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi){return visit(rows,lo,hi,lo.clone(),0);}
    static boolean visit(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,BigInteger[] p,int i){if(i==p.length)return holds(rows,p);for(p[i]=lo[i];p[i].compareTo(hi[i])<=0;p[i]=p[i].add(b(1)))if(visit(rows,lo,hi,p,i+1))return true;return false;}
    static int value(CountLcg solver,String name)throws Exception{Field f=CountLcg.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(solver);}
    static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void cached(CountLcg solver,List<ExactLinearProgram.Constraint> rows)throws Exception{
        Object[] cache;try{cache=(Object[])field(solver,"rowActivity");}catch(NoSuchFieldException baseline){return;}
        var lo=(BigInteger[])field(solver,"low");var hi=(BigInteger[])field(solver,"high");
        for(int j=0;j<cache.length;j++)if(cache[j]!=null){var sum=b(0);int infinite=0,xor=0;for(var e:rows.get(j).terms().entrySet()){
            if(e.getValue().signum()==0)continue;int i=e.getKey();var p=e.getValue().signum()>0?lo[i]:hi[i];if(p==null){infinite++;xor^=i;}else sum=sum.add(e.getValue().multiply(p));
        }check(sum.equals(field(cache[j],"minimum")),"stale minimum row="+j);check(infinite==(int)field(cache[j],"infinities"),"stale infinity count");check(xor==(int)field(cache[j],"infiniteXor"),"stale infinite variable");}
    }
    static void sample(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,boolean feasible)throws Exception{
        systems++;var budget=new PlanningBudget(0,8_000_000,128L<<20,()->false,System::nanoTime);
        try(var solver=new CountLcg(rows,lo,hi,budget,1024,true).lockBranching().lockBranching()){
            while(true){while(!solver.step())cached(solver,rows);cached(solver,rows);if(solver.paused()){pauses++;solver.resume(1024);}else break;}
            BigInteger[] counts=solver.counts();
            if(counts!=null){sat++;check(feasible,"false SAT rows="+rows);check(holds(rows,counts),"invalid witness");for(int i=0;i<lo.length;i++)check(counts[i].compareTo(lo[i])>=0&&(hi[i]==null||counts[i].compareTo(hi[i])<=0),"outside domain");}
            else if(solver.infeasible()){unsat++;check(!feasible,"false infeasible "+rows+" lo="+Arrays.toString(lo)+" hi="+Arrays.toString(hi));}
            else unknown++;
            if(solver.certificate()!=null){check(CountProof.verify(solver.certificate(),8_000_000)==CountProof.Verdict.VERIFIED,"bad certificate "+rows+" "+budget.diagnostics());proofs++;}
            conflicts+=value(solver,"conflicts");jumps+=value(solver,"jumps");restarts+=value(solver,"restarts");
        }
        check(budget.reservedBytes()==0,"memory leak");work+=budget.nodes();
    }
    static void lifecycle(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,PlanningBudget budget){
        try(var solver=new CountLcg(rows,lo,hi,budget,1024,true).lockBranching().lockBranching()){while(true){while(!solver.step()){}if(solver.paused())solver.resume(1024);else break;}check(!solver.infeasible()||!oracle(rows,lo,hi),"limit false infeasible");}
        catch(PlanningBudget.Exhausted|CancellationException expected){}check(budget.reservedBytes()==0,"lifecycle memory leak "+budget.reservedBytes());
    }
    public static void main(String[]args)throws Exception{
        Random r=new Random(51610303);for(int t=0;t<4000;t++){
            int n=2+r.nextInt(4),m=1+r.nextInt(14);var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=b(r.nextInt(5)-2);hi[i]=lo[i].add(b(1+r.nextInt(4)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int j=0;j<m;j++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=r.nextInt(11)-5;rows.add(row(r.nextInt(41)-20,a));}
            sample(rows,lo,hi,oracle(rows,lo,hi));
        }
        // Boolean pigeonhole: enough conflicts to exercise restarts and trail reuse.
        for(int holes=3;holes<=5;holes++){int n=holes*(holes+1);var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int p=0;p<=holes;p++){var a=new long[n];for(int h=0;h<holes;h++)a[p*holes+h]=-1;rows.add(row(-1,a));}
            for(int h=0;h<holes;h++)for(int p=0;p<=holes;p++)for(int q=p+1;q<=holes;q++){var a=new long[n];a[p*holes+h]=1;a[q*holes+h]=1;rows.add(row(1,a));}
            var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));sample(rows,lo,hi,false);
        }
        sample(List.of(row(-25,-3,-2),row(29,5,1)),new BigInteger[]{b(0),b(0)},new BigInteger[]{null,null},true);
        for(int t=0;t<250;t++){
            int n=4;var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,b(-2));Arrays.fill(hi,b(3));var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int j=0;j<8;j++){var a=new long[n];for(int i=0;i<n;i++)a[i]=r.nextInt(11)-5;rows.add(row(r.nextInt(31)-15,a));}
            for(int i=0;i<n;i++){var a=new long[n];a[i]=1;rows.add(row(3,a));}boolean feasible=oracle(rows,lo,hi);Arrays.fill(hi,null);Collections.shuffle(rows,r);sample(rows,lo,hi,feasible);
        }
        var huge=b(1).shiftLeft(140);sample(List.of(new ExactLinearProgram.Constraint(Map.of(0,huge,1,huge.negate()),huge.negate())),new BigInteger[]{huge,huge},new BigInteger[]{huge.add(b(3)),huge.add(b(3))},true);
        var rows=List.of(row(-4,-2,-3,-1),row(3,1,2,3),row(0,-1,1,0));var lo=new BigInteger[]{b(0),b(0),b(0)};var hi=new BigInteger[]{b(4),b(4),b(4)};
        for(int w=1;w<=300;w++)lifecycle(rows,lo,hi,new PlanningBudget(0,w,()->false));
        for(int limit=1;limit<=500;limit++){int stop=limit;var polls=new AtomicInteger();lifecycle(rows,lo,hi,new PlanningBudget(0,1_000_000,128L<<20,()->polls.incrementAndGet()>stop,System::nanoTime));}
        for(long mem:new long[]{1,128,1024,4096,8192,12000,16000,32000,65536})lifecycle(rows,lo,hi,new PlanningBudget(0,1_000_000,mem,()->false,System::nanoTime));
        check(unknown==0,"unexpected unknown "+unknown);check(conflicts>0,"no conflicts tested");check(pauses>0,"no resume tested");check(restarts>0,"no restart tested");
        System.out.println("PASS systems="+systems+" SAT="+sat+" UNSAT="+unsat+" unknown="+unknown+" proofs="+proofs+" pauses="+pauses+" conflicts="+conflicts+" jumps="+jumps+" restarts="+restarts+" assertions="+assertions+" work="+work);
    }
}
