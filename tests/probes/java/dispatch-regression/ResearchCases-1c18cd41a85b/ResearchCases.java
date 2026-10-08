import org.cgse.core.*;
import java.util.*;
public class ResearchCases {
 static int cases;
 static GraphRecipe<String> r(String id,Map<String,Long> i,Map<String,Long> o){return new GraphRecipe<>(id,id,i.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),o);}
 static void test(String name,List<GraphRecipe<String>> recipes,String target,long n,Map<String,Long> stock,Map<String,Long> seeds,boolean feasible){
  var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);long begin=System.nanoTime();
  var w=new GraphPlanningWork<>(new GraphCompiler<>(recipes),target,n,stock,Set.of(),seeds,true,true,b).catalysts(CatalystPolicy.MINIMAL);while(!w.step()){}
  var p=w.result();System.out.printf("RESEARCH %s n=%d status=%s work=%d ms=%.3f missing=%s%n",name,n,p.result(),b.nodes(),(System.nanoTime()-begin)/1e6,p.missingExact());
  if(p.feasible()!=feasible)throw new AssertionError(name+" result "+p.result());if(p.feasible())PlanVerifier.verifyRuntimeInventory(p);else if(p.missing().isEmpty())throw new AssertionError(name+" missing preview absent");cases++;
 }
 public static void main(String[]args){
  var cross=List.of(r("r1",Map.of("A",1L),Map.of("U",1L,"X",1L)),r("r2",Map.of("B",1L,"X",1L),Map.of("V",1L,"Y",1L)),r("r3",Map.of("U",1L,"Y",1L),Map.of("A",1L,"P",1L)),r("r4",Map.of("V",1L,"A",1L),Map.of("B",1L,"A",1L,"Q",1L)));
  var nested=List.of(r("r1",Map.of("A",1L,"F",1L),Map.of("B",1L)),r("r2",Map.of("B",1L,"C",1L),Map.of("D",1L)),r("r3",Map.of("D",1L),Map.of("B",1L,"C",1L,"X",1L)),r("r4",Map.of("B",1L,"X",1L),Map.of("E",1L)),r("r5",Map.of("E",1L),Map.of("A",1L,"P",1L)));
  for(long n:new long[]{1,100_000_000,Long.MAX_VALUE}){
   test("crossed-intervals",cross,"P",n,Map.of("A",1L,"B",1L),Map.of("A",1L,"B",1L,"Q",1L),true);
   test("inner-starts-outer",nested,"P",n,Map.of("A",1L,"C",1L,"F",n),Map.of("A",1L,"C",1L),true);
  }
  test("nested-one-raw-short",nested,"P",Long.MAX_VALUE,Map.of("A",1L,"C",1L,"F",Long.MAX_VALUE-1),Map.of("A",1L,"C",1L),false);
  test("integer-sat-dag",List.of(r("xT",Map.of("Vx",1L),Map.of("C1",1L,"C2",1L)),r("xF",Map.of("Vx",1L),Map.of("C3",1L,"C4",1L)),r("yT",Map.of("Vy",1L),Map.of("C1",1L,"C3",1L)),r("yF",Map.of("Vy",1L),Map.of("C2",1L,"C4",1L)),r("finish",Map.of("C1",1L,"C2",1L,"C3",1L,"C4",1L),Map.of("G",1L))),"G",1,Map.of("Vx",1L,"Vy",1L),Map.of(),false);
  test("ratio-is-not-fixed",List.of(r("r1",Map.of("A",1L,"F",1L),Map.of("B",2L)),r("r2",Map.of("B",1L),Map.of("A",1L,"P",1L))),"P",2,Map.of("A",1L,"F",1L),Map.of("A",1L),true);
  test("fractional-start-not-integer",List.of(r("grow",Map.of("A",2L),Map.of("A",3L))),"A",1,Map.of("A",1L),Map.of("A",1L),false);
  for(int i=0;i<6;i++){
   var order=new ArrayList<>(List.of(r("r1",Map.of("A",1L),Map.of("B",1L)),r("r2",Map.of("B",1L),Map.of("A",1L,"P",1L)),r("r3",Map.of("A",1L),Map.of("Q",1L))));Collections.shuffle(order,new Random(i));
   test("bad-greedy-not-nogood-"+i,order,"P",1,Map.of("A",1L),Map.of("Q",1L),true);
  }
  for(int n=1;n<=7;n++){
   var recipes=new ArrayList<GraphRecipe<String>>();var stock=new HashMap<String,Long>();var seeds=new HashMap<String,Long>();stock.put("T",1L);
   recipes.add(r("start",Map.of("T",1L),Map.of("C0",1L)));
   for(int i=0;i<n;i++){stock.put("Z"+i,1L);seeds.put("Z"+i,1L);recipes.add(r("zero"+i,Map.of("C"+i,1L,"Z"+i,1L),Map.of("T",1L,"O"+i,1L)));recipes.add(r("one"+i,Map.of("C"+i,1L,"O"+i,1L),Map.of("C"+(i+1),1L,"Z"+i,1L)));}recipes.add(r("halt",Map.of("C"+n,1L),Map.of("G",1L)));
   test("binary-counter-"+n,recipes,"G",1,stock,seeds,true);
  }
  System.out.println("RESEARCH_CASES_PASS "+cases);
 }
}
