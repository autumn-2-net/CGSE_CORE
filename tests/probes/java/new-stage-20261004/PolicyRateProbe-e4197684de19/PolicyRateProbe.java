package org.cgse.core;
import java.util.*;
import java.nio.file.*;
public final class PolicyRateProbe {
 static long checks,failures;
 static void check(boolean value){checks++;if(!value)failures++;}
 static void pairs(long u1,long d1,long u2,long d2,int n,boolean equal){
  var p=new CountPortfolioPolicy();var a=p.add(1,"a");var b=p.add(1,"b");
  for(int i=0;i<n;i++){p.candidateFeedback(a,u1,d1,true,i%3!=0);p.candidateFeedback(b,u2,d2,true,i%3!=0);}
  double x=p.candidateEfficiency(a),y=p.candidateEfficiency(b);check(equal?Math.abs(x-y)<1e-12:x>y);
 }
 public static void main(String[]args)throws Exception{
  for(int seed=0;seed<2000;seed++){
   long unit=1+seed*7919L;int n=1+seed%19;
   pairs(unit,100*unit,100*unit,100*unit,n,false);
   pairs(100*unit,unit,100*unit,100*unit,n,false);
   pairs(unit,100*unit,100*unit,unit,n,true);
   var p=new CountPortfolioPolicy();var a=p.add(1,"a");var b=p.add(1,"b");
   for(int i=0;i<n;i++){p.candidateFeedback(a,unit,unit,true,true);p.candidateFeedback(b,unit,unit,true,false);}
   check(p.candidateEfficiency(a)>p.candidateEfficiency(b));
   double old=p.candidateEfficiency(a);p.mode(CountPortfolioPolicy.Mode.PROOF);check(p.candidateEfficiency(a)==1);p.candidateFeedback(a,Long.MAX_VALUE,Long.MAX_VALUE,true,true);check(Double.isFinite(p.candidateEfficiency(a)));p.mode(CountPortfolioPolicy.Mode.FIRST_WITNESS);check(p.candidateEfficiency(a)==old);
  }
  var p=new CountPortfolioPolicy();var cheap=p.add(1,"cheap");var costly=p.add(1,"costly");
  for(int i=0;i<5;i++){p.candidateFeedback(cheap,10,1000,true,true);p.candidateFeedback(costly,1000,1000,true,true);}
  String sample="cheap="+p.candidateEfficiency(cheap)+", costly="+p.candidateEfficiency(costly);
  var seen=new HashSet<CountPortfolioPolicy.Arm>();for(int i=0;i<30;i++){var arm=p.select();seen.add(arm);p.selected(arm);p.feedback(arm,100,arm==cheap?100:0);}check(seen.size()==2);
  String report="assertions="+checks+", failures="+failures+", "+sample;Files.writeString(Path.of(args[0],"policy-rate.txt"),report);System.out.println(report);
  if(args.length>1&&failures!=0)throw new AssertionError(report);
 }
}
