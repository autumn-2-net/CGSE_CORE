import java.math.BigInteger;
import java.util.*;
import org.cgse.core.*;

public class LossyConversionRegression {
    static int checks;
    static CatalystPolicy policy;
    static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out) {
        return new GraphRecipe<>(id,id,in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);
    }
    static GraphPlan<String> solve(String material,long n,Map<String,Long> stock,boolean preserve) {
        var recipes=List.of(recipe("compress",Map.of("dust",4L),Map.of("gem",3L)),
                recipe("grind",Map.of("gem",1L),Map.of("dust",1L)),
                recipe("finish",Map.of(material,1L),Map.of("P",1L)));
        var b=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"P",n,stock,preserve,true,b).catalysts(policy);
        while(!w.step()){}
        var p=w.result();
        if(p.feasible())PlanVerifier.verify(p);
        else {
            if(p.missingExact().isEmpty())throw new AssertionError("No missing preview: "+p.result()+" "+b.diagnostics());
            PlanVerifier.verify(new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
        }
        System.out.println("CONVERSION "+material+" n="+n+" stock="+stock+" result="+p.result()+" times="+p.patternTimesExact()+" initial="+p.initialExact()+" missing="+p.missingExact());
        return p;
    }
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[]args) {
        for(CatalystPolicy next:List.of(CatalystPolicy.MINIMAL,CatalystPolicy.STOCK,new CatalystPolicy(64,64))){policy=next;cases();}
        System.out.println("LOSSY_CONVERSION_PASS "+checks);
    }
    static void cases() {
        var reportedRecipes=List.of(recipe("compress",Map.of("dust",4L),Map.of("gem",3L)),recipe("grind",Map.of("gem",1L),Map.of("dust",1L)));
        for(long order:new long[]{6,Long.MAX_VALUE}) {
            var b=new PlanningBudget(0,4_000_000,128L<<20,()->false,System::nanoTime);
            var w=new GraphPlanningWork<>(new GraphCompiler<>(reportedRecipes),"gem",order,Map.of("gem",1L),true,true,b).catalysts(policy);
            while(!w.step()){}
            var p=w.result();
            BigInteger expectedRuns=BigInteger.valueOf(order).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
            check(p.patternTimesExact().equals(Map.of("compress",expectedRuns)),"Reported case still executes a round trip");
            check(p.missingExact().equals(Map.of("dust",expectedRuns.multiply(BigInteger.valueOf(4)))),"Reported case has inflated or truncated deficit");
            check(!p.initialExact().containsKey("gem"),"Unnecessarily borrowed stored gem");
            PlanVerifier.verify(new GraphPlan<>(p.target(),p.amount(),p.preserveSeeds(),p.steps(),p.recipes(),p.initialExact(),p.seeds(),Map.of(),GraphPlan.Result.FEASIBLE,0,0));
            System.out.println("REPORTED n="+order+" times="+p.patternTimesExact()+" missing="+p.missingExact());
        }
        for(String material:List.of("gem","dust"))for(long n:new long[]{1,10,1_000_000,Integer.MAX_VALUE,Long.MAX_VALUE}) {
            BigInteger runs=material.equals("gem")?BigInteger.valueOf(n).add(BigInteger.TWO).divide(BigInteger.valueOf(3)):BigInteger.valueOf(n);
            BigInteger raw=runs.multiply(BigInteger.valueOf(material.equals("gem")?4:1));
            String source=material.equals("gem")?"dust":"gem", used=material.equals("gem")?"compress":"grind", unused=material.equals("gem")?"grind":"compress";
            for(boolean funded:new boolean[]{false,true}) {
                if(funded&&raw.bitLength()>63)continue;
                var p=solve(material,n,funded?Map.of(source,raw.longValueExact()):Map.of(),true);
                check(p.feasible()==funded,"Wrong funding result");
                BigInteger reverse=p.patternTimesExact().getOrDefault(unused,BigInteger.ZERO);
                check(material.equals("gem")?reverse.compareTo(BigInteger.valueOf(3))<0:reverse.signum()==0,"Avoidable full round trip "+unused);
                if(reverse.signum()==0){
                    check(runs.equals(p.patternTimesExact().get(used)),"Wrong direct count");
                    check(p.initialExact().equals(Map.of(source,raw)),"Unnecessary conversion inventory");
                    check(funded||p.missingExact().equals(Map.of(source,raw)),"Incorrect exact shortage");
                } else check(p.initialExact().getOrDefault(source,BigInteger.ZERO).compareTo(raw)<=0,"Mixed conversion did not save input");
            }
        }
        var topup=solve("gem",2,Map.of("gem",1L,"dust",3L),false);
        check(topup.feasible(),"Lost legitimate batch top-up");
        check(topup.patternTimesExact().containsKey("compress")&&topup.patternTimesExact().containsKey("grind"),"Expected necessary mixed conversion");
    }
}
