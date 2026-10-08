import org.cgse.core.*;
import java.util.*;
import java.lang.management.ManagementFactory;

public class SummaryCompare {
    static final com.sun.management.ThreadMXBean MEMORY = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final Map<String, GraphRecipe<String>> RECIPES = new LinkedHashMap<>();
    static final List<PlanStep> CHILDREN = new ArrayList<>();
    static PlanStep steps;
    static SequenceSummary<String> before, after;
    static void run(boolean previous, int sample) {
        var budget = new PlanningBudget(0,10_000_000,()->false);
        long allocated = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long started=System.nanoTime();
        if(previous) {
            var work=new PreviousSummaryComputation<>(steps,RECIPES,budget);
            while(!work.step()) {}
            before=work.result();
        } else {
            var work=new SummaryComputation<>(steps,RECIPES,budget);
            while(!work.step()) {}
            after=work.result();
        }
        long elapsed=System.nanoTime()-started;
        allocated=MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
        System.out.printf(Locale.ROOT,"sample=%d previous=%s elapsed_ms=%.4f allocated_bytes=%d%n",sample,previous,elapsed/1e6,allocated);
    }
    public static void main(String[] args) {
        for(int i=0;i<32768;i++) {
            String id="r"+i;
            RECIPES.put(id,new GraphRecipe<>(id,id,List.of(new GraphRecipe.Slot<>("k"+i,1)),Map.of("k"+(i+1),1L)));
            CHILDREN.add(new PlanStep.Batch(id,1_000_000_000_000L));
        }
        steps=new PlanStep.Sequence(CHILDREN);
        for(int i=0;i<20;i++) {
            run(i%2==0,i); run(i%2!=0,i);
            if(!before.equals(after)) throw new AssertionError("Summary changed");
        }
    }
}
