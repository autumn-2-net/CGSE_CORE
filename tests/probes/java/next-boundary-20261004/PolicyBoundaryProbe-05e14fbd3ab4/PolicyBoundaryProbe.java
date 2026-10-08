package org.cgse.core;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class PolicyBoundaryProbe {
    static int checks;
    static void check(boolean result, String reason) { checks++; if (!result) throw new AssertionError(reason); }
    static Object get(Object instance,String field) throws Exception { var f=instance.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(instance); }
    static void batchCosts() {
        for (var mode:CountPortfolioPolicy.Mode.values()) for(int spacing=2;spacing<=120;spacing++) {
            var policy=new CountPortfolioPolicy();policy.mode(mode);var arm=policy.add(1);policy.add(1);
            for(int i=0;i<spacing*4;i++) {
                policy.selected(arm);policy.feedback(arm,4096,i%spacing==0?1:0);
                check(policy.quantum(arm,100000)==4096,"sparse progress inflated a 4096-work batch");
            }
            var state=mode==CountPortfolioPolicy.Mode.FIRST_WITNESS?arm:arm.modes.get(mode);
            check(state.work==(long)spacing*4*4096,"cost lost");
            check(state.observations==(long)spacing*4,"completed batches lost");
        }
    }
    static void eligibility() {
        var random=new Random(781295);
        for(var mode:CountPortfolioPolicy.Mode.values()) for(int n=2;n<15;n++) {
            var policy=new CountPortfolioPolicy();policy.mode(mode);var arms=new ArrayList<CountPortfolioPolicy.Arm>();
            for(int i=0;i<n;i++){var arm=policy.add(i);arm.eligible=i%3!=0;arms.add(arm);}
            var visits=new int[n];
            for(int i=0;i<1000;i++) {
                var chosen=policy.select();check(chosen!=null&&chosen.eligible,"ineligible arm selected");
                visits[arms.indexOf(chosen)]++;policy.selected(chosen);policy.feedback(chosen,1+random.nextInt(100000),random.nextInt(8)==0?random.nextInt(1000):0);
                check(policy.quantum(chosen,17)<=17,"overspent caller allowance");
            }
            for(int i=0;i<n;i++) check(arms.get(i).eligible?visits[i]>0:visits[i]==0,"eligible arm starved");
            var restored=arms.get(0);restored.eligible=true;check(policy.select()==restored,"deferred first visit lost");
        }
    }
    static PlanningBudget budget(){return new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);}
    static void capabilities() throws Exception {
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        for(int i=0;i<100;i++)rows.add(new ExactLinearProgram.Constraint(Map.of(i%64,BigInteger.ONE.negate(),(i+1)%64,BigInteger.ONE.negate()),BigInteger.ONE.negate()));
        BigInteger[] low=new BigInteger[64],high=new BigInteger[64];Arrays.fill(low,BigInteger.ZERO);Arrays.fill(high,BigInteger.ONE);
        for(int run=0;run<50;run++){
            var budget=budget();
            try(var models=CountModelViews.create(rows,low,high,budget);var search=new CountViewSearch(models,budget)) {
                search.mode(CountPortfolioPolicy.Mode.PROOF);search.resume(10000);
                var searches=(List<?>)get(search,"searches");check(searches.size()==3,"proof task admitted candidate-only engine");
                for(Object sub:searches)check(!get(sub,"engine").toString().equals("JUMP"),"proof jump");
                search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);search.resume(10000);
                check(searches.size()==4,"witness mode failed to add deferred jump");
                for(Object sub:searches)if(!get(sub,"engine").toString().equals("JUMP")) {
                    ((CountPortfolioPolicy.Arm)get(sub,"scheduling")).retired=true;
                    var completed=sub.getClass().getDeclaredField("done");completed.setAccessible(true);completed.setBoolean(sub,true);
                }
                check(!search.step(),"expected incomplete jump initialization");
                Object source=get(search,"active"),jump=get(source,"jump");check(jump!=null,"jump not started");
                long before=(long)get(jump,"work");
                search.mode(CountPortfolioPolicy.Mode.PROOF);search.resume(10000);check(search.step(),"proof selected disabled jump");
                check((long)get(jump,"work")==before,"proof consumed heuristic work");
                check(!search.retained(),"candidate-only arm kept proof task retained");
                search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);search.resume(10000);search.step();
                check(get(source,"jump")==jump,"goal switch restarted pending batch");
                check((long)get(jump,"work")>before,"pending batch failed to continue");
            }
            check(budget.reservedBytes()==0,"goal switch leaked retained state");
        }
        var budget=budget();
        try(var models=CountModelViews.create(rows,low,high,budget);var search=new CountViewSearch(models,budget)) {
            search.mode(CountPortfolioPolicy.Mode.PROOF);
            search.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);
            check(((List<?>)get(search,"searches")).isEmpty(),"goal change partially initialized a new view");
            search.resume(10000);
            check(((List<?>)get(search,"searches")).size()==4,"new view lost exact engines after goal change");
        }
        check(budget.reservedBytes()==0,"unstarted goal change leaked");
    }
    public static void main(String[]args)throws Exception {
        batchCosts();eligibility();capabilities();String result="{\"assertions\":"+checks+",\"modeTransitions\":50}";
        Files.writeString(Path.of(args[0],"boundary-probe.json"),result);System.out.println(result);
    }
}
