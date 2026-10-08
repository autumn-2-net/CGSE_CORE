package org.cgse.core;

import java.util.*;
import java.math.BigInteger;

public class ExecutionReview {
 static BigInteger z(long n){return BigInteger.valueOf(n);}
 static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static long verified, rejected, rules;
 static void review(List<GraphRecipe<String>> rs, Map<String,Long> stock,int depth) {
  var budget=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
  var all=new ArrayList<>(rs);var keys=new HashSet<String>();for(var r:rs){keys.addAll(r.inputs().keySet());keys.addAll(r.outputs().keySet());}
  for(String k:keys)all.add(recipe("dummy_"+k,Map.of(k,1L),Map.of(k,1L)));
  try(var model=RecipeCountModel.region(all,Map.of(),stock,Set.of(),budget);var ex=new CountExecution<>(model,budget)){
   var proofs=ex.proofs();rules+=proofs.size();
   // Enumerate EVERY executable multiset up to depth; each state stores actual
   // inventory, independently updated by input/output arcs, without summaries.
   record Node(List<Integer> counts,Map<String,Long> held,int used){}
   List<Integer> empty=new ArrayList<>(Collections.nCopies(all.size(),0));var todo=new ArrayDeque<Node>();var seen=new HashSet<List<Integer>>();todo.add(new Node(empty,stock,0));seen.add(empty);
   while(!todo.isEmpty()) {var node=todo.remove();var p=node.counts.stream().map(n->ExactRational.of(z(n))).toArray(ExactRational[]::new);
    for(var proof:proofs){check(!proof.guard().violated(p,budget),"unsound rule "+proof+" counts="+node.counts+" stock="+stock+" recipes="+rs);verified++;}
    if(node.used==depth)continue;
    for(int i=0;i<rs.size();i++){var r=rs.get(i);if(r.inputs().entrySet().stream().anyMatch(e->node.held.getOrDefault(e.getKey(),0L)<e.getValue()))continue;
     var c=new ArrayList<>(node.counts);c.set(i,c.get(i)+1);if(!seen.add(c))continue;var h=new HashMap<>(node.held);r.inputs().forEach((k,n)->h.merge(k,-n,Long::sum));r.outputs().forEach((k,n)->h.merge(k,n,Long::sum));todo.add(new Node(c,h,node.used+1));
    }
   }
   // Guards may reject arbitrary count vectors, but never one of the independently
   // enumerated reachable vectors. Exercise exact integer complement as well.
   var rng=new Random(17);for(int t=0;t<100;t++){BigInteger[] lo=new BigInteger[all.size()],hi=new BigInteger[all.size()];var v=new ArrayList<Integer>();for(int i=0;i<lo.length;i++){int n=i<rs.size()?rng.nextInt(3):0;lo[i]=hi[i]=z(n);v.add(n);}for(var proof:proofs){boolean forbidden=proof.guard().conflict().impliedBy(lo,hi,budget);check(forbidden==proof.guard().violated(Arrays.stream(lo).map(ExactRational::of).toArray(ExactRational[]::new),budget),"complement");if(forbidden){check(!seen.contains(v),"rejected executable multiset");rejected++;}}}
  }
  check(budget.reservedBytes()==0,"leak="+budget.reservedBytes());
 }
 public static void main(String[]args){var rng=new Random(19292627);
  for(int t=0;t<500;t++){var rs=new ArrayList<GraphRecipe<String>>();var s=new HashMap<String,Long>();for(int k=0;k<4;k++)s.put("m"+k,(long)rng.nextInt(5));
   for(int i=0;i<2+rng.nextInt(4);i++){var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();for(int j=0;j<4;j++){long a=rng.nextInt(3),b=rng.nextInt(3);if(a>0)in.put("m"+j,a);if(b>0)out.put("m"+j,b);}if(out.isEmpty())out.put("m0",1L);rs.add(recipe("r"+i,in,out));}review(rs,s,8);
  }
  review(List.of(recipe("loss",Map.of("A",2L),Map.of("A",1L,"P",1L)),recipe("repair",Map.of("B",1L),Map.of("A",1L))),Map.of("A",2L,"B",3L),8);
  review(List.of(recipe("grow",Map.of("A",1L),Map.of("A",2L))),Map.of(),8);
  review(List.of(recipe("long",Map.of("A",Long.MAX_VALUE),Map.of("A",Long.MAX_VALUE-1,"P",1L))),Map.of("A",Long.MAX_VALUE),2);
  System.out.println("PASS execution guards networks=503 rules="+rules+" valid_prefix_rule_checks="+verified+" rejected_count_checks="+rejected);
 }
}
