import org.cgse.core.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

public final class ExtremeCycleStress {
    static final long N=100_000_017L;
    static final com.sun.management.ThreadMXBean MEMORY=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static ComplexCycleStress.Case dag(int size, String shape,long n) {
        var recipes=new ArrayList<GraphRecipe<String>>();var body=new ArrayList<PlanStep>();var stock=new LinkedHashMap<String,Long>();
        if(shape.equals("deep")) {
            stock.put("R",n);
            for(int i=0;i<size;i++){
                recipes.add(ComplexCycleStress.recipe("r"+i,Map.of(i==0?"R":"Q"+(i-1),1L),Map.of("Q"+i,1L)));
                body.add(new PlanStep.Batch("r"+i,n));
            }
            return new ComplexCycleStress.Case("dag-deep"+size,recipes,"Q"+(size-1),n,stock,Map.of(),new PlanStep.Sequence(body),true);
        }
        stock.put("R",Math.multiplyExact(n,size));
        var outputs=new LinkedHashMap<String,Long>();
        for(int i=0;i<size;i++) {
            recipes.add(ComplexCycleStress.recipe("leaf"+i,Map.of("R",1L),Map.of("Q"+i,1L)));
            body.add(new PlanStep.Batch("leaf"+i,n));outputs.put("Q"+i,1L);
        }
        if(shape.equals("wide")) {
            recipes.add(ComplexCycleStress.recipe("join",outputs,Map.of("P",1L)));body.add(new PlanStep.Batch("join",n));
        } else ComplexCycleStress.joinTree(recipes,body,outputs,n);
        return new ComplexCycleStress.Case("dag-"+shape+size,recipes,"P",n,stock,Map.of(),new PlanStep.Sequence(body),true);
    }
    static ComplexCycleStress.Case distractions(int depth,long n) {
        var c=ComplexCycleStress.nested(depth,2,n,1);
        var recipes=new ArrayList<GraphRecipe<String>>();
        for(int i=0;i<=depth;i++){
            recipes.add(ComplexCycleStress.recipe("dead"+i,Map.of("Z"+i,1L),Map.of("Q"+i,1L)));
            recipes.add(ComplexCycleStress.recipe("z"+i,Map.of("Q"+i,2L),Map.of("Z"+i,1L)));
        }
        recipes.addAll(c.recipes());
        return new ComplexCycleStress.Case("nested-distractors"+depth,recipes,c.target(),n,c.stock(),c.seeds(),c.witness(),true);
    }
    static void validate(ComplexCycleStress.Case c,GraphPlan<String> p) {
        PlanVerifier.verify(p);
        p.initialExact().forEach((k,v)->{if(v.compareTo(BigInteger.valueOf(c.stock().getOrDefault(k,0L)))>0)throw new AssertionError("Invented stock "+k);});
        var ledger=new HashMap<>(p.initialExact());
        var availableRecipes=new HashSet<>(c.recipes());
        p.patternTimesExact().forEach((id,times)->{
            var r=p.recipes().get(id);
            if(!availableRecipes.contains(r))throw new AssertionError("Foreign recipe "+id);
            r.inputs().forEach((k,v)->ledger.merge(k,BigInteger.valueOf(v).multiply(times).negate(),BigInteger::add));
            r.outputs().forEach((k,v)->ledger.merge(k,BigInteger.valueOf(v).multiply(times),BigInteger::add));
        });
        ledger.forEach((k,v)->{if(v.signum()<0)throw new AssertionError("Negative "+k);});
        if(ledger.getOrDefault(c.target(),BigInteger.ZERO).compareTo(BigInteger.valueOf(c.amount()))<0)throw new AssertionError("Missing output");
    }
    static void run(ComplexCycleStress.Case c) {
        var b=new PlanningBudget(3000,10_000_000,128L<<20,()->false,System::nanoTime); b.enableMetrics();
        long start=System.nanoTime(),allocated=MEMORY.getCurrentThreadAllocatedBytes();
        try {
            GraphPlan<String> p;
            try(var scope=b.work(PlanningBudget.Phase.BUILD)){
                var work=new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),c.target(),c.amount(),c.stock(),true,true,b).catalysts(CatalystPolicy.MINIMAL);
                while(!work.step()){}p=work.result();
            }
            long wall=System.nanoTime()-start,alloc=MEMORY.getCurrentThreadAllocatedBytes()-allocated;
            if(p.feasible()) validate(c,p);
            System.out.printf(Locale.ROOT,"EXTREME name=%s recipes=%d amount=%d result=%s wall_ms=%.3f allocated_mb=%.3f nodes=%d peak_mb=%.3f detail=%s phases=%s%n",c.name(),c.recipes().size(),c.amount(),p.result(),wall/1e6,alloc/1048576.0,b.nodes(),b.peakBytes()/1048576.0,b.failureDetail(),b.metrics().activeNanos());
        }catch(Throwable t){System.out.println("EXTREME_ERROR name="+c.name()+" kind="+t);t.printStackTrace(System.out);}
    }
    public static void main(String[] args) {
        String filter=args.length>0?args[0]:"";
        var cases=new ArrayList<ComplexCycleStress.Case>();
        for(int size:new int[]{2048,8192,32768}){
            cases.add(ComplexCycleStress.ring(size,N,1));
            cases.add(dag(size,"deep",N));cases.add(dag(size,"tree",N));cases.add(dag(size,"wide",N));
        }
        for(int size:new int[]{2048,8192}){
            cases.add(ComplexCycleStress.chain(size,N,1));cases.add(ComplexCycleStress.independent(size,N,1));cases.add(ComplexCycleStress.hub(size,N,1));
        }
        for(int d:new int[]{16,64,128,512})cases.add(ComplexCycleStress.nested(d,1,N,1));
        for(int d:new int[]{12,24,40,60})cases.add(ComplexCycleStress.nested(d,2,1,1));
        for(int d:new int[]{2,4,8,12})cases.add(distractions(d,1));
        cases.add(ComplexCycleStress.braid(1024,N,1));
        cases.add(dag(512,"deep",Long.MAX_VALUE));
        for(var c:cases)if(c.name().contains(filter)){
            System.out.println("BEGIN "+c.name());run(c);
        }
    }
}
