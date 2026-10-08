package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
public class ProductionLearningQuota {
    static int checked,cancelled,limited,declined,resumes;
    static List<ExactLinearProgram.Constraint> rows(int n){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int i=0;i<n;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i,BigInteger.ONE,(i+1)%n,BigInteger.ONE),BigInteger.ONE));
        var terms=new TreeMap<Integer,BigInteger>();for(int i=0;i<n;i++)terms.put(i,BigInteger.ONE.negate());
        rows.add(new ExactLinearProgram.Constraint(terms,BigInteger.valueOf(-(n+1)/2)));return rows;
    }
    static void run(int n,long maximum,long memory,int cancelAt,int quantum){
        var checkpoints=new AtomicInteger();var budget=new PlanningBudget(0,maximum,memory,()->checkpoints.incrementAndGet()>=cancelAt,System::nanoTime);
        var low=new BigInteger[n];var high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
        try(var search=new CountLcg(rows(n),low,high,budget,1024,true).learnedRelaxation()) {
            if(!search.learnedRelaxationEnabled())declined++;
            for(int round=0;round<100000;round++) {
                while(!search.step()){}
                if(search.counts()!=null)throw new AssertionError("false odd-cycle witness");
                if(search.infeasible()){
                    if(CountProof.verify(search.certificate(),2_000_000)!=CountProof.Verdict.VERIFIED)throw new AssertionError("bad exact closure");
                    break;
                }
                if(!search.paused())break;
                search.resume(quantum);resumes++;
            }
        }catch(CancellationException expected){cancelled++;}
        catch(PlanningBudget.Exhausted expected){limited++;}
        finally {if(budget.reservedBytes()!=0)throw new AssertionError("leak="+budget.reservedBytes()+" cutoff="+cancelAt);checked++;}
    }
    static void retained() throws Exception {
        var all=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(".local/solver-weighted-20261003/lp-learning/lseu.json"))).getAsJsonArray();
        var fixture=all.get(0).getAsJsonObject();int n=fixture.getAsJsonArray("lower").size();
        var low=new BigInteger[n];var high=new BigInteger[n];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(var element:fixture.getAsJsonArray("rows")){var row=element.getAsJsonObject();var terms=new TreeMap<Integer,BigInteger>();for(var t:row.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(t.getKey()),t.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,row.get("upper").getAsBigInteger()));}
        for(int quantum:new int[]{17,127,4096}) {
            var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);int paused=0;
            try(var canonical=CountCanonicalModel.create(rows,low,high,budget);var search=new CountLcg(canonical.rows(),canonical.lower(),canonical.upper(),budget,1024).learnedRelaxation()) {
                do{while(!search.step()){}if(!search.paused())break;search.resume(quantum);paused++;}while(paused<1000000);
                var point=canonical.restore(search.counts());if(point==null)throw new AssertionError("retained real SAT lost");
                CountBenchmark.verify(rows,low,high,point);if(paused==0)throw new AssertionError("retained test never paused");resumes+=paused;
                System.out.println("RETAINED quantum="+quantum+" resumes="+paused+" work="+budget.nodes());
            }finally{if(budget.reservedBytes()!=0)throw new AssertionError("retained leak");checked++;}
        }
    }
    public static void main(String[]args)throws Exception{
        for(int cutoff=1;cutoff<=8000;cutoff+=11)run(9,1_000_000,64L<<20,cutoff,127);
        for(long memory:new long[]{1,1024,8192,16384,32768,65536,131072,262144,1L<<20,64L<<20})
            for(long maximum:new long[]{1,1024,8192,16384,65536,1_000_000})run(9,maximum,memory,Integer.MAX_VALUE,127);
        for(int n:new int[]{3,5,7,9,11,13,15})for(int quantum:new int[]{1,17,127,1024,4096})run(n,1_000_000,64L<<20,Integer.MAX_VALUE,quantum);
        retained();
        System.out.println("QUOTA checked="+checked+" cancelled="+cancelled+" exhausted="+limited+" optional_declines="+declined+" resumes="+resumes+" leaks=0");
    }
}
