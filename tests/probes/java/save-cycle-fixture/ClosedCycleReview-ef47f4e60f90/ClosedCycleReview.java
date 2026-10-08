import org.cgse.core.*;
import java.util.*;
public class ClosedCycleReview {
 public static void main(String[]args){
  var recipes=SaveCycleReview.recipes().stream().filter(r->!r.id().equals("metal")).toList();
  for(long n:new long[]{1,1000,100_000_017L,1_000_000_000_000L}){
   var stock=SaveCycleReview.raw();stock.put("hypercube",2L);stock.put("residue",1100L);
   var p=CoproductRegression.plan(recipes,"hypercube",n,stock);
   if(p.feasible()) PlanVerifier.verify(p);
   System.out.println("CLOSED n="+n+" result="+p.result()+" nodes="+p.searchNodes()+" ms="+p.planningNanos()/1e6+" counts="+p.patternTimesExact()+" initial="+p.initialExact());
   if(n==1000)System.out.println(p.steps());
  }
 }
}
