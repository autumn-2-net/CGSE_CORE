package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScoutSafetyProbe {
    record Model(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi) {}
    static int assertions, models, exactSat, exactUnsat, found, proved, declined, cancellations, quotaCases, memoryCases, guardCases, allowanceViolations;
    static long assignments;
    static void require(boolean yes,String message){assertions++;if(!yes)throw new AssertionError(message);}
    static PlanningBudget budget(long work,long memory){return new PlanningBudget(30000,work,memory,()->false,System::nanoTime);}
    static ExactLinearProgram.Constraint row(Map<Integer,BigInteger> terms,BigInteger rhs){return new ExactLinearProgram.Constraint(new LinkedHashMap<>(terms),rhs);}
    static List<ExactLinearProgram.Constraint> eq(Map<Integer,BigInteger> terms,BigInteger rhs){var neg=new LinkedHashMap<Integer,BigInteger>();terms.forEach((i,v)->neg.put(i,v.negate()));return List.of(row(terms,rhs),row(neg,rhs.negate()));}
    static Model tiny(){return new Model(eq(Map.of(0,BigInteger.TWO,1,BigInteger.valueOf(3)),BigInteger.valueOf(3)),new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO},new BigInteger[]{BigInteger.ONE,BigInteger.ONE});}
    static boolean check(Model m,BigInteger[] x){for(int i=0;i<x.length;i++)if(x[i].compareTo(m.lo[i])<0||m.hi[i]!=null&&x[i].compareTo(m.hi[i])>0)return false;for(var r:m.rows){BigInteger sum=BigInteger.ZERO;for(var t:r.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(r.upper())>0)return false;}return true;}
    static boolean enumerate(Model m,BigInteger[] x,int at){if(at==x.length){assignments++;return check(m,x);}boolean sat=false;for(BigInteger v=m.lo[at];v.compareTo(m.hi[at])<=0;v=v.add(BigInteger.ONE)){x[at]=v;sat|=enumerate(m,x,at+1);}return sat;}
    static void run(Model m,PlanningBudget b,long cap,boolean oracle){
        try(var search=new CountMeetInMiddle(m.rows,m.lo,m.hi,b,cap)){
            while(!search.step()){}
            var x=search.counts();if(x!=null){found++;require(oracle,"false SAT");require(check(m,x),"invalid witness");}
            if(search.infeasible()){proved++;require(!oracle,"false UNSAT");}
            if(x==null&&!search.infeasible())declined++;
        }
    }
    static void guard(Model m){var b=budget(20_000_000,256L<<20);require(CountMeetInMiddle.scoutWork(m.rows,m.lo,m.hi,b)==0,"expected scout decline");require(b.reservedBytes()==0,"guard leak");guardCases++;}
    public static void main(String[] args){
        var random=new Random(202610040419L);
        for(int iteration=0;iteration<400;iteration++){
            int n=2+random.nextInt(6);var lo=new BigInteger[n];var hi=new BigInteger[n];var point=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=BigInteger.valueOf(random.nextInt(5)-2);hi[i]=lo[i].add(BigInteger.valueOf(1+random.nextInt(2)));point[i]=lo[i].add(BigInteger.valueOf(random.nextInt(hi[i].subtract(lo[i]).intValue()+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int j=0;j<1+random.nextInt(4);j++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=BigInteger.ZERO;for(int i=0;i<n;i++){BigInteger coefficient=BigInteger.valueOf(random.nextInt(15)-7);if(coefficient.signum()!=0){terms.put(i,coefficient);rhs=rhs.add(coefficient.multiply(point[i]));}}if(iteration%2==1)rhs=rhs.add(BigInteger.valueOf(random.nextInt(21)-10));if(j==0)rows.addAll(eq(terms,rhs));else rows.add(row(terms,rhs));}
            var m=new Model(rows,lo,hi);boolean oracle=enumerate(m,new BigInteger[n],0);models++;if(oracle)exactSat++;else exactUnsat++;
            var b=budget(20_000_000,256L<<20);long cap=CountMeetInMiddle.scoutWork(rows,lo,hi,b);if(cap>0){require(cap<=b.remainingWork()/16,"normal scout > share");run(m,b,cap,oracle);}else declined++;require(b.reservedBytes()==0,"oracle leak");
        }
        var base=tiny();
        for(long work:new long[]{1,2,4,8,16,32,64,128,256,512,1024,2000,4096,8192,16384,32768,20_000_000}){
            var b=budget(work,256L<<20);try{long cap=CountMeetInMiddle.scoutWork(base.rows,base.lo,base.hi,b);if(cap>b.remainingWork()/16){allowanceViolations++;System.out.println("SCOUT_SHARE_VIOLATION work="+work+" remaining="+b.remainingWork()+" cap="+cap);}if(cap>0)run(base,b,cap,true);}catch(PlanningBudget.Exhausted expected){}require(b.reservedBytes()==0,"quota leak");quotaCases++;
        }
        for(long memory:new long[]{1,32,127,128,143,144,255,512,1024,4096,16384,65536,1L<<20,256L<<20}){
            var b=budget(20_000_000,memory);try{long cap=CountMeetInMiddle.scoutWork(base.rows,base.lo,base.hi,b);if(cap>0)run(base,b,cap,true);else require(b.reservedBytes()==0,"decline reserve");run(base,b,4096,true);}catch(PlanningBudget.Exhausted expected){}require(b.reservedBytes()==0,"memory leak");memoryCases++;
        }
        for(int position=0;position<200;position++){
            int at=position;var calls=new AtomicInteger();var b=new PlanningBudget(30000,20_000_000,256L<<20,()->calls.getAndIncrement()>=at,System::nanoTime);
            try{long cap=CountMeetInMiddle.scoutWork(base.rows,base.lo,base.hi,b);if(cap>0)run(base,b,cap,true);}catch(CancellationException expected){cancellations++;}require(b.reservedBytes()==0,"cancel leak at "+position);
        }
        for(long cap:new long[]{0,1,16,128,1023}){var b=budget(20_000_000,256L<<20);try(var matcher=new CountMeetInMiddle(base.rows,base.lo,base.hi,b,cap)){while(!matcher.step()){}require(matcher.counts()==null&&!matcher.infeasible(),"local decline became UNSAT");}require(b.reservedBytes()==0,"local cap leak");quotaCases++;}
        BigInteger huge=BigInteger.ONE.shiftLeft(65536);
        guard(new Model(eq(Map.of(0,huge,1,BigInteger.ONE),huge),base.lo,base.hi));
        guard(new Model(base.rows,new BigInteger[]{huge,BigInteger.ZERO},new BigInteger[]{huge.add(BigInteger.ONE),BigInteger.ONE}));
        guard(new Model(eq(Map.of(0,BigInteger.TWO,1,BigInteger.ONE),huge),base.lo,base.hi));
        guard(new Model(base.rows,base.lo,new BigInteger[]{huge,BigInteger.ONE}));
        guard(new Model(base.rows,base.lo,new BigInteger[]{null,BigInteger.ONE}));
        guard(new Model(base.rows,new BigInteger[]{BigInteger.ONE,BigInteger.ONE},base.hi));
        guard(new Model(List.of(row(Map.of(0,BigInteger.ONE,1,BigInteger.ONE),BigInteger.ONE)),base.lo,base.hi));
        guard(new Model(List.of(row(Map.of(0,BigInteger.TWO,1,BigInteger.valueOf(3)),BigInteger.TEN)),base.lo,base.hi));
        for(int bits:new int[]{31,63,127,511,1000}){
            BigInteger shift=BigInteger.ONE.shiftLeft(bits).negate(),w=BigInteger.ONE.shiftLeft(bits).add(BigInteger.valueOf(3));
            var lo=new BigInteger[]{shift,BigInteger.valueOf(-2)};var hi=new BigInteger[]{shift.add(BigInteger.ONE),BigInteger.ZERO};
            var point=new BigInteger[]{shift.add(BigInteger.ONE),BigInteger.ONE.negate()};var terms=Map.of(0,w,1,BigInteger.valueOf(-7));
            var m=new Model(eq(terms,w.multiply(point[0]).add(BigInteger.valueOf(7))),lo,hi);var b=budget(20_000_000,256L<<20);long cap=CountMeetInMiddle.scoutWork(m.rows,lo,hi,b);
            if(bits<=511){require(cap>0,"bounded wide coefficient should admit");run(m,b,cap,true);}else require(cap==0,"wide RHS guard");require(b.reservedBytes()==0,"wide integer leak");guardCases++;
        }
        System.out.println("PASS assertions="+assertions+" exactModels="+models+" exhaustiveAssignments="+assignments+" SAT="+exactSat+" UNSAT="+exactUnsat+" found="+found+" proved="+proved+" declines="+declined+" cancellations="+cancellations+" quotaCases="+quotaCases+" memoryCases="+memoryCases+" guardCases="+guardCases+" shareViolations="+allowanceViolations);
        if(args.length>0&&args[0].equals("strict"))require(allowanceViolations==0,"scout share exceeds remaining/16");
    }
}
