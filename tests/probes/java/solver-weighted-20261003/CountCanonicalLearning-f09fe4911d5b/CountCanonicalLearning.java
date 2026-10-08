package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
/** Local integration harness only. */
final class CountCanonicalLearning implements AutoCloseable {
    final CountCanonicalModel model;
    final CountLcgLearning search;
    CountCanonicalLearning(List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,PlanningBudget budget,long maximum) {
        long before=budget.nodes();
        model=CountCanonicalModel.create(rows,lower,upper,budget);
        if(model==null)throw new AssertionError("Known canonical model declined; work="+(budget.nodes()-before));
        try {
            var reference=CanonicalModelProbe.legacy(rows,lower,upper);
            if(!model.rows().equals(reference.rows()) || !Arrays.equals(model.order(),reference.order()) || !Arrays.equals(model.lower(),reference.low()) || !Arrays.equals(model.upper(),reference.high()))throw new AssertionError("Actual main mapping differs from original desc");
            System.out.println("CANONICAL n="+lower.length+" rows="+rows.size()+" work="+(budget.nodes()-before)+" reserved="+budget.reservedBytes());
            search=new CountLcgLearning(model.rows(),model.lower(),model.upper(),budget,maximum);
        } catch(Throwable t){model.close();throw t;}
    }
    boolean step(){return search.step();}
    BigInteger[] counts(){return model.restore(search.counts());}
    boolean infeasible(){return search.infeasible();}
    boolean paused(){return search.paused();}
    void resume(long quantum){search.resume(quantum);}
    public void close(){try{search.close();}finally{model.close();}}
}
