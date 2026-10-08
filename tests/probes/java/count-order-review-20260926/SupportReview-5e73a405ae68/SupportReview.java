package org.cgse.core;
import java.util.*;
import java.math.BigInteger;

public class SupportReview {
 static BigInteger z(long n){return BigInteger.valueOf(n);}
 static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[]args){var rng=new Random(2726926);int closed=0,witness=0,unknown=0;
  for(int test=0;test<600;test++){
   int[] initial=new int[4];for(int i=0;i<3+rng.nextInt(4);i++)initial[rng.nextInt(3)]++;var stock=new HashMap<String,Long>();for(int i=0;i<4;i++)stock.put(""+i,(long)initial[i]);int wanted=1+rng.nextInt(Arrays.stream(initial).sum());var rs=new ArrayList<GraphRecipe<String>>();
   for(int i=0;i<4+rng.nextInt(5);i++){var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();for(int j=0;j<1+rng.nextInt(3);j++){in.merge(""+rng.nextInt(4),1L,Long::sum);out.merge(""+rng.nextInt(4),1L,Long::sum);}rs.add(recipe("r"+i,in,out));}
   for(int i=0;i<4;i++)rs.add(recipe("d"+i,Map.of(""+i,1L),Map.of(""+i,1L)));
   BigInteger[] counts=new BigInteger[rs.size()];Arrays.fill(counts,z(0));for(int i=0;i<rs.size()-4;i++)if(rng.nextBoolean())counts[i]=z(1);
   var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);
   try(var model=RecipeCountModel.region(rs,Map.of("3",z(wanted)),stock,Set.of(),budget);var search=new CountSupportSearch<>(model,counts,budget)){
    while(!search.step()){}
    if(search.result()==CountSupportSearch.Result.CLOSED){closed++;var cut=search.cut();check(cut!=null,"missing proof cut");var excluded=cut.terms().keySet();var subset=new ArrayList<GraphRecipe<String>>();for(int i=0;i<rs.size();i++){if(!excluded.contains(i))subset.add(rs.get(i));else check(cut.terms().get(i).equals(z(-1)),"bad boundary row");}check(!CountOracle.reachable(subset,initial,wanted),"boundary cut excluded a reachable goal test="+test);}
    else if(search.result()==CountSupportSearch.Result.WITNESS){witness++;Map<String,GraphRecipe<String>> map=new HashMap<>();rs.forEach(r->map.put(r.id(),r));Map<String,BigInteger> held=new HashMap<>();stock.forEach((k,n)->held.put(k,z(n)));ScheduleReview.replay(search.witness(),map,held);check(held.getOrDefault("3",z(0)).compareTo(z(wanted))>=0,"wrong witness goal");}
    else unknown++;
   }
   check(budget.reservedBytes()==0,"support leaked reservation");
  }
  var b=new PlanningBudget(0,1_000_000,128L<<20,()->false,System::nanoTime);var rs=List.of(recipe("grow",Map.of("A",1L),Map.of("A",2L)));
  try(var m=RecipeCountModel.region(rs,Map.of("A",z(Long.MAX_VALUE)),Map.of("A",1L),Set.of(),b);var s=new CountSupportSearch<>(m,new BigInteger[]{z(Long.MAX_VALUE)},b)){while(!s.step()){}check(s.result()==CountSupportSearch.Result.UNKNOWN&&s.cut()==null,"unbounded growth cannot learn failure");}
  check(b.reservedBytes()==0,"growth leak");
  System.out.println("PASS support-boundary proof checks cases=600 witness="+witness+" closed="+closed+" unknown="+unknown+"; cuts independently checked with all boundary exits removed; long growth remains UNKNOWN");
 }
}
