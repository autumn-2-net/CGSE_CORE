package org.cgse.core;
import java.util.*;
public final class PolicyProbe{
 public static void main(String[]args){
  var policy=new CountPortfolioPolicy();var arms=List.of(policy.add(100),policy.add(10),policy.add(30));
  long[] work=new long[3];int[] last={-1,-1,-1},calls=new int[3];int maxWait=0;long max=0;
  for(int turn=0;turn<120;turn++){
   var a=policy.select();int id=arms.indexOf(a);policy.selected(a);long q=policy.quantum(a,1_000_000);
   if(q<4096||q>32768)throw new AssertionError("quantum cap");max=Math.max(max,q);
   if(last[id]>=0)maxWait=Math.max(maxWait,turn-last[id]);last[id]=turn;calls[id]++;work[id]+=q;
   policy.feedback(a,q,id==1?Math.max(1,q/32):0);
  }
  if(maxWait>2*arms.size()+1)throw new AssertionError("starvation="+maxWait);
  if(work[1]<=work[0]+work[2])throw new AssertionError("productive arm gets no benefit "+Arrays.toString(work));
  double previous=arms.get(1).reward;policy.feedback(arms.get(1),32768,0);if(arms.get(1).reward>=previous)throw new AssertionError("no forgetting");
  for(var a:arms)a.retired=true;if(policy.select()!=null)throw new AssertionError("retired selected");
  System.out.println("PASS feedback work="+Arrays.toString(work)+" calls="+Arrays.toString(calls)+" max_wait="+maxWait+" max_quantum="+max+"; caps, forced exploration, decay, retirement");
 }
}
