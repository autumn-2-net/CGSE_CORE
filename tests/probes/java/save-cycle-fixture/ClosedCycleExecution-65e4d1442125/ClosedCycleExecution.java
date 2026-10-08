import org.cgse.core.*;
import java.util.*;

public final class ClosedCycleExecution {
    public static void main(String[] args) {
        var recipes=SaveCycleReview.recipes().stream().filter(r->!r.id().equals("metal")).toList();
        for(long n:new long[]{1,1259,5000}) {
            var stock=SaveCycleReview.raw(); stock.put("hypercube",2L); stock.put("residue",1100L);
            var p=CoproductRegression.plan(recipes,"hypercube",n,stock);
            if(!p.feasible())throw new AssertionError("Not feasible: "+p.result());
            PlanVerifier.verify(p);
            for(int reload:new int[]{0,97}) {
                var m=new ComplexCycleStress.Machines(p,reload);
                for(int tick=0;tick<250_000&&!m.runtime.finished();tick++) m.step();
                if(m.runtime.state()!=GraphJobRuntime.State.COMPLETED)throw new AssertionError("Unfinished: "+m.runtime.reason());
                if(!p.patternTimes().equals(m.accepted))throw new AssertionError("Exactly-once counts differ");
                if(!m.delivered.equals(Map.of("hypercube",n)))throw new AssertionError("Delivery differs");
                if(!m.physical.isEmpty()||!m.flights.isEmpty())throw new AssertionError("Stranded output");
                p.seeds().forEach((k,v)->{if(m.refunded.getOrDefault(k,0L)<v)throw new AssertionError("Unreturned seed: "+k);});
                System.out.println("CLOSED_EXEC n="+n+" reload="+reload+" result="+m.runtime.state()+" ticks="+m.tick+" accepted="+m.accepted+" overlaps="+m.overlaps);
            }
        }
    }
}
