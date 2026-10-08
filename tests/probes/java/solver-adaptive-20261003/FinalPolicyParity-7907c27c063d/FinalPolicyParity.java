package org.cgse.core;
import java.util.*;
public class FinalPolicyParity {
 public static void main(String[] args){long assertions=0;for(int seed=0;seed<100;seed++){
  var a=new CountPortfolioPolicy();var b=new CountPortfolioReference();
  var x=new ArrayList<CountPortfolioPolicy.Arm>();var y=new ArrayList<CountPortfolioReference.Arm>();Random r=new Random(seed);int n=2+r.nextInt(30);
  for(int i=0;i<n;i++){long c=r.nextInt(100000);x.add(a.add(c));y.add(b.add(c));}
  for(int t=0;t<3000;t++){var p=a.select();var q=b.select();int id=x.indexOf(p);if(id!=y.indexOf(q))throw new AssertionError("selection");
   long amount=a.quantum(p,100000);if(amount!=b.quantum(q,100000))throw new AssertionError("quantum");a.selected(p);b.selected(q);
   long spent=amount+r.nextInt(10000),gain=r.nextInt(5)==0?0:r.nextInt(10000);a.feedback(p,spent,gain);b.feedback(q,spent,gain);
   if(Double.doubleToLongBits(p.reward)!=Double.doubleToLongBits(q.reward)||p.work!=q.work||p.waiting!=q.waiting)throw new AssertionError("feedback");assertions+=3;
  }
 }System.out.println("PASS final production parity decisions=300000 assertions="+assertions);}
}
