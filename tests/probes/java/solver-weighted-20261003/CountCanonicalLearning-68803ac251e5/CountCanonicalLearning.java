package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
final class CountCanonicalLearning implements AutoCloseable {
 final CountLcgLearning search;
 final int[] order;
 CountCanonicalLearning(List<ExactLinearProgram.Constraint> rows,BigInteger[] lower,BigInteger[] upper,PlanningBudget budget,long maximum){
  int n=lower.length;
  int objective=-1;
  for(int r=0;r<rows.size();r++) {
   var row=rows.get(r);
   if(row.terms().size()>=n/2&&row.terms().values().stream().allMatch(a->a.signum()>0)&&(objective<0||row.terms().size()>rows.get(objective).terms().size())) objective=r;
  }
  var costs=new BigInteger[n];Arrays.fill(costs,BigInteger.ZERO);
  if(objective>=0)for(var term:rows.get(objective).terms().entrySet())costs[term.getKey()]=term.getValue();
  List<List<String>> signatures=new ArrayList<>();for(int i=0;i<n;i++)signatures.add(new ArrayList<>());
  for(var row:rows){
   String shape=row.upper()+":"+row.terms().values().stream().sorted().toList();
   for(var term:row.terms().entrySet()){budget.check();signatures.get(term.getKey()).add(term.getValue()+":"+shape);}
  }
  var keys=new String[n];for(int i=0;i<n;i++){Collections.sort(signatures.get(i));keys[i]=signatures.get(i).toString();}
  Integer[] sorted=new Integer[n];for(int i=0;i<n;i++)sorted[i]=i;
  final boolean reverse=Boolean.getBoolean("canonical.reverse");
  Arrays.sort(sorted,(a,b)->{int c=costs[a].compareTo(costs[b]);if(c!=0)return reverse?-c:c;return keys[a].compareTo(keys[b]);});
  order=new int[n];int[] inverse=new int[n];BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];
  for(int i=0;i<n;i++){order[i]=sorted[i];inverse[order[i]]=i;lo[i]=lower[order[i]];hi[i]=upper[order[i]];}
  var mapped=new ArrayList<ExactLinearProgram.Constraint>();
  for(var row:rows){var terms=new TreeMap<Integer,BigInteger>();for(var t:row.terms().entrySet()){budget.check();terms.put(inverse[t.getKey()],t.getValue());}mapped.add(new ExactLinearProgram.Constraint(terms,row.upper()));}
  mapped.sort((a,b)->{for(int i=0;i<n;i++){int c=a.terms().getOrDefault(i,BigInteger.ZERO).compareTo(b.terms().getOrDefault(i,BigInteger.ZERO));if(c!=0)return c;}return a.upper().compareTo(b.upper());});
  search=new CountLcgLearning(mapped,lo,hi,budget,maximum);
 }
 boolean step(){return search.step();}
 BigInteger[] counts(){var c=search.counts();if(c==null)return null;var result=new BigInteger[c.length];for(int i=0;i<c.length;i++)result[order[i]]=c[i];return result;}
 boolean infeasible(){return search.infeasible();}
 boolean paused(){return search.paused();}
 void resume(long quantum){search.resume(quantum);}
 public void close(){search.close();}
}
