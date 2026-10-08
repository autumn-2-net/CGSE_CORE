package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;

public class PbFrontierProbe {
    static long checks;
    static BigInteger z(long x){return BigInteger.valueOf(x);}
    static void ok(boolean x,String message){checks++;if(!x)throw new AssertionError(message);}
    static PlanningBudget budget(){return new PlanningBudget(0,100_000_000,512L<<20,()->false,System::nanoTime);}
    static boolean holds(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var row:rows){BigInteger total=z(0);for(var t:row.terms().entrySet())total=total.add(t.getValue().multiply(x[t.getKey()]));if(total.compareTo(row.upper())>0)return false;}return true;}
    static boolean oracle(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi){for(int mask=0;mask<(1<<lo.length);mask++){BigInteger[] x=lo.clone();for(int i=0;i<x.length;i++)if((mask&(1<<i))!=0)x[i]=hi[i];if(holds(rows,x))return true;}return false;}
    static void randomModels(){
        Random r=new Random(3404103);long oldWork=0,newWork=0;int sat=0,unsat=0;long proofs=0,weighted=0;
        for(int run=0;run<4000;run++){
            int n=3+r.nextInt(7);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],planted=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=z(r.nextInt(9)-4);hi[i]=lo[i].add(z(r.nextInt(10)==0?0:1));planted[i]=r.nextBoolean()?lo[i]:hi[i];}
            List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
            for(int j=0,m=3+r.nextInt(30);j<m;j++){
                Map<Integer,BigInteger> terms=new LinkedHashMap<>();BigInteger limit=z(0);
                for(int i=0;i<n;i++)if(r.nextInt(4)!=0){BigInteger c=z(r.nextInt(41)-20);if(run%13==0)c=c.shiftLeft(130).add(z(r.nextInt(3)-1));if(c.signum()!=0){terms.put(i,c);limit=limit.add(c.multiply(planted[i]));}}
                limit=limit.add(z(r.nextInt(7)-(run%2==0?0:3)));
                rows.add(new ExactLinearProgram.Constraint(terms,limit));
            }
            boolean possible=oracle(rows,lo,hi);var before=budget();var after=budget();
            var oldJournal=new CountProof.Journal(32L<<20);var journal=new CountProof.Journal(32L<<20);before.proofJournal(oldJournal);after.proofJournal(journal);
            try(var old=new BaselineCountCdcl(rows,lo,hi,before,8_000_000);var now=new CountCdcl(rows,lo,hi,after,8_000_000)){
                while(!old.step()){}while(!now.step()){}
                BigInteger[] a=old.counts(),b=now.counts();
                ok(Arrays.equals(a,b),"witness changed");ok(old.infeasible()==now.infeasible(),"status changed");
                ok((b!=null)==possible,"oracle SAT mismatch");ok(now.infeasible()==!possible,"oracle UNSAT mismatch");
                if(b!=null){sat++;ok(holds(rows,b),"witness violates original");}else unsat++;
                ok(old.learnedConflicts().equals(now.learnedConflicts()),"learned clause sequence changed");
            }
            ok(oldJournal.entries().equals(journal.entries()),"clause certificate changed");ok(oldJournal.derivations().equals(journal.derivations()),"PB derivation changed");
            for(var p:journal.entries()){proofs++;ok(CountProof.verify(p,8_000_000)==CountProof.Verdict.VERIFIED,"invalid independent clause proof");}
            for(var p:journal.derivations()){weighted++;ok(CountProof.verify(p,8_000_000)==CountProof.Verdict.VERIFIED,"invalid independent PB proof");}
            ok(before.reservedBytes()==0&&after.reservedBytes()==0,"random-model memory leak");oldWork+=before.nodes();newWork+=after.nodes();
        }
        System.out.println("random={\"cases\":4000,\"sat\":"+sat+",\"unsat\":"+unsat+",\"proofs\":"+proofs+",\"weighted\":"+weighted+",\"beforeWork\":"+oldWork+",\"afterWork\":"+newWork+"}");
    }
    static class Harness implements AutoCloseable {
        final Object solver;final PlanningBudget budget;final Field values,trail,level;final Method compile,assign,propagate,backtrack,reason;final Object row;
        Harness(boolean baseline,int n)throws Exception{
            budget=budget();Class<?> type=baseline?BaselineCountCdcl.class:CountCdcl.class;
            BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(1));
            Map<Integer,BigInteger> terms=new LinkedHashMap<>();for(int i=0;i<n;i++)terms.put(i,z(i<n/2?10000:1));
            var input=new ExactLinearProgram.Constraint(terms,z(n/2));
            var constructor=type.getDeclaredConstructor(List.class,BigInteger[].class,BigInteger[].class,PlanningBudget.class,long.class);constructor.setAccessible(true);
            solver=constructor.newInstance(List.of(input),lo,hi,budget,8_000_000L);
            compile=type.getDeclaredMethod("compile",ExactLinearProgram.Constraint.class,int.class);compile.setAccessible(true);compile.invoke(solver,input,0);
            Field rows=type.getDeclaredField("rows");rows.setAccessible(true);row=((List<?>)rows.get(solver)).get(0);
            assign=type.getDeclaredMethod("assign",int.class,int[].class);assign.setAccessible(true);
            propagate=type.getDeclaredMethod("propagate",row.getClass());propagate.setAccessible(true);
            backtrack=type.getDeclaredMethod("backtrack",int.class);backtrack.setAccessible(true);
            reason=type.getDeclaredMethod("reason",int.class);reason.setAccessible(true);
            values=type.getDeclaredField("values");values.setAccessible(true);
            trail=type.getDeclaredField("trail");trail.setAccessible(true);
            level=type.getDeclaredField("level");level.setAccessible(true);
            propagate.invoke(solver,row);
        }
        void point(int variable)throws Exception{level.setInt(solver,1);assign.invoke(solver,2*variable+1,null);propagate.invoke(solver,row);}
        void undo()throws Exception{backtrack.invoke(solver,0);propagate.invoke(solver,row);}
        @Override public void close()throws Exception{((AutoCloseable)solver).close();ok(budget.reservedBytes()==0,"harness leak");}
    }
    static void incremental()throws Exception{
        long oldWork=0,newWork=0;
        for(int n:new int[]{64,128,256})try(var before=new Harness(true,n);var after=new Harness(false,n)){
            for(int round=0;round<32;round++){
                int steps=n/2-(round%11);
                for(int k=n/2;k<n/2+steps;k++){
                    before.point(k);after.point(k);
                    ok(Arrays.equals((int[])before.values.get(before.solver),(int[])after.values.get(after.solver)),"incremental assignments mismatch");
                    ok(before.trail.get(before.solver).equals(after.trail.get(after.solver)),"incremental trail mismatch");
                }
                for(int k=0;k<n;k++)ok(Arrays.equals((int[])before.reason.invoke(before.solver,k),(int[])after.reason.invoke(after.solver,k)),"lazy reason changed");
                before.undo();after.undo();
                ok(Arrays.equals((int[])before.values.get(before.solver),(int[])after.values.get(after.solver)),"undo lost propagation");
            }
            oldWork+=before.budget.nodes();newWork+=after.budget.nodes();
        }
        System.out.println("incremental={\"beforeWork\":"+oldWork+",\"afterWork\":"+newWork+"}");
    }
    public static void main(String[] args)throws Exception{incremental();randomModels();System.out.println("PASS checks="+checks);}
}
