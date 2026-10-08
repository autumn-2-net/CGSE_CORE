import org.cgse.core.*;
import java.lang.management.ManagementFactory;
import java.util.*;

public final class ControlledBench {
    static final com.sun.management.ThreadMXBean MEMORY=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    record Measurement(String status,long nanos,long allocated,long work,int nodes){}
    static Measurement run(ComplexCycleStress.Case c) {
        var budget=new PlanningBudget(5000,10_000_000,128L<<20,()->false,System::nanoTime);
        long alloc=MEMORY.getCurrentThreadAllocatedBytes(),start=System.nanoTime();
        var w=new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),c.target(),c.amount(),c.stock(),true,true,budget).catalysts(CatalystPolicy.MINIMAL);
        while(!w.step()){}var p=w.result();long elapsed=System.nanoTime()-start,bytes=MEMORY.getCurrentThreadAllocatedBytes()-alloc;
        if(p.feasible())ExtremeCycleStress.validate(c,p);
        return new Measurement(p.result().name(),elapsed,bytes,budget.nodes(),NestedCompilerChecks.nodes(p.steps()));
    }
    static long median(long[] samples){Arrays.sort(samples);return samples[samples.length/2];}
    public static void main(String[] args) {
        var cases=new ArrayList<ComplexCycleStress.Case>();long n=100_000_017;
        cases.add(ComplexCycleStress.ring(32768,n,1));
        cases.add(ExtremeCycleStress.dag(32768,"deep",n));
        cases.add(ExtremeCycleStress.dag(32768,"tree",n));
        cases.add(ComplexCycleStress.hub(2048,n,1));cases.add(ComplexCycleStress.hub(8192,n,1));
        for(int d:new int[]{12,24,40,60})cases.add(ComplexCycleStress.nested(d,2,1,1));
        var actual=SaveCycleReview.recipes().stream().filter(r->!r.id().equals("metal")).toList();
        var stock=SaveCycleReview.raw();stock.put("hypercube",2L);stock.put("residue",1100L);
        cases.add(new ComplexCycleStress.Case("hypercube-three-cycle",actual,"hypercube",n,stock,Map.of(),null,true));
        for(int i=0;i<40;i++)run(ComplexCycleStress.nested(8,2,1,1));
        for(var c:cases) {
            for(int i=0;i<3;i++)run(c);
            long[] times=new long[7],alloc=new long[7],work=new long[7];var statuses=new TreeSet<String>();int nodes=0;
            for(int i=0;i<7;i++){var m=run(c);times[i]=m.nanos;alloc[i]=m.allocated;work[i]=m.work;nodes=m.nodes;statuses.add(m.status);}
            System.out.printf(Locale.ROOT,"BENCH name=%s recipes=%d amount=%d status=%s median_ms=%.3f median_alloc_mib=%.3f work=%d program_nodes=%d%n",c.name(),c.recipes().size(),c.amount(),statuses,median(times)/1e6,median(alloc)/1048576.0,median(work),nodes);
        }
    }
}
