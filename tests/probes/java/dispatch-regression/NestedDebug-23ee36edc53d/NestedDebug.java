import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;
public class NestedDebug {
 public static void main(String[] args){
  var recipes=DeepLongBoundary.nested(24);var b=new PlanningBudget(5000,10_000_000,128L<<20,()->false,System::nanoTime);
  var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",Long.MAX_VALUE,Map.of("C0",1L),true,true,b).catalysts(CatalystPolicy.MINIMAL);
  while(!w.step()){}var p=w.result();var s=SequenceSummary.of(p.steps(),p.recipes());
  System.out.println("result="+p.result()+" work="+b.nodes()+" initial="+p.initialExact());
  for(var k:s.keys()) if(p.initialExact().getOrDefault(k,BigInteger.ZERO).add(s.peak(k)).compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)System.out.println("overpeak="+k+" peak="+s.peak(k));
  System.out.println("counts="+p.patternTimesExact());
  System.out.println("steps="+p.steps());
 }
}
