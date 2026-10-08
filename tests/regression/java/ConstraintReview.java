package org.cgse.core;
import java.util.*;
import java.math.BigInteger;

public class ConstraintReview {
 static BigInteger z(long n){return BigInteger.valueOf(n);}
 static PlanningBudget budget(){return new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);}
 static ExactLinearProgram.Constraint row(long rhs,long... a){Map<Integer,BigInteger> t=new LinkedHashMap<>();for(int i=0;i<a.length;i++)if(a[i]!=0)t.put(i,z(a[i]));return new ExactLinearProgram.Constraint(t,z(rhs));}
 static ExactLinearProgram.Constraint bound(int i,int n,boolean low){return new ExactLinearProgram.Constraint(Map.of(i,low?z(-1):z(1)),z(low?-n:n));}
 static boolean holds(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var r:rows){BigInteger s=z(0);for(var t:r.terms().entrySet())s=s.add(t.getValue().multiply(x[t.getKey()]));if(s.compareTo(r.upper())>0)return false;}return true;}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static BigInteger[] vector(int n,int code,int base){var x=new BigInteger[n];for(int i=0;i<n;i++){x[i]=z(code%base);code/=base;}return x;}
 static boolean domain(BigInteger[] x,BigInteger[] lo,BigInteger[] hi){for(int i=0;i<x.length;i++)if(x[i].compareTo(lo[i])<0||hi[i]!=null&&x[i].compareTo(hi[i])>0)return false;return true;}
 public static void main(String[]args)throws Exception{reduction(); propagation(); inherited(); warm(); longBoundaries();System.out.println("PASS all constraint checks");}
 static void reduction(){Random random=new Random(92627);long points=0,proofs=0;
  for(int t=0;t<700;t++){
   int n=2+random.nextInt(5);var rows=new ArrayList<ExactLinearProgram.Constraint>();var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,z(0));Arrays.fill(hi,z(3));
   if(t%3==0){lo[n-1]=z(1);hi[n-1]=z(1);}
   for(int j=0;j<3;j++){long[]a=new long[n];for(int i=0;i<n;i++)a[i]=random.nextInt(7)-3;rows.add(row(random.nextInt(19)-4,a));}
   int pairs=1+random.nextInt(n-1);
   for(int k=0;k<pairs;k++){int a=random.nextInt(n),b=random.nextInt(n);if(a==b)continue;long[] c=new long[n];c[a]=1;c[b]=random.nextBoolean()?1:-1;long rhs=random.nextInt(5)-1;rows.add(row(rhs,c));for(int i=0;i<n;i++)c[i]=-c[i];rows.add(row(-rhs,c));}
   if(t%7==0){var factor=z(Long.MAX_VALUE).pow(2);for(int k=0;k<rows.size();k++){var old=rows.get(k);Map<Integer,BigInteger> terms=new HashMap<>();old.terms().forEach((i,c)->terms.put(i,c.multiply(factor)));rows.set(k,new ExactLinearProgram.Constraint(terms,old.upper().multiply(factor)));}}
   var costs=new BigInteger[n];for(int i=0;i<n;i++)costs[i]=z(random.nextInt(15)-7);
   var b=budget();try(var compiled=new CountReduction(rows,lo,hi,b)){while(!compiled.step()){}
    BigInteger costBound=compiled.minimum(costs);
    int limit=1<<(2*n);for(int code=0;code<limit;code++){var original=vector(n,code,4);if(!domain(original,lo,hi)||!holds(rows,original))continue;var small=new BigInteger[compiled.variables()];int[] ids=compiled.representatives();for(int i=0;i<ids.length;i++)small[i]=original[ids[i]];check(holds(compiled.rows(),small),"lost integer solution "+t);check(domain(small,compiled.lower(),compiled.upper()),"lost domain "+t);check(Arrays.equals(original,compiled.expand(small)),"bad recovery");BigInteger cost=z(0);for(int i=0;i<n;i++)cost=cost.add(costs[i].multiply(original[i]));check(costBound==null||costBound.compareTo(cost)<=0,"unsound objective bound");points++;}
    int reducedLimit=1<<(2*compiled.variables());for(int code=0;code<reducedLimit;code++){var small=vector(compiled.variables(),code,4);if(!holds(compiled.rows(),small)||!domain(small,compiled.lower(),compiled.upper()))continue;var original=compiled.expand(small);check(domain(original,lo,hi)&&holds(rows,original),"invented solution "+t);points++;}
    for(var proof:compiled.proof()){var a=proof.first();var c=proof.second();check(a.upper().equals(c.upper().negate()),"equality RHS");for(var e:a.terms().entrySet())check(e.getValue().equals(c.terms().get(e.getKey()).negate()),"equality coefficient");check(a.terms().get(proof.eliminated()).multiply(proof.offset()).equals(a.upper()),"offset proof");check(a.terms().get(proof.eliminated()).multiply(proof.multiplier()).add(a.terms().get(proof.retained())).signum()==0,"sign proof");proofs++;}
   }
  }System.out.println("PASS reductions=700 independently enumerated solutions="+points+" equality proofs="+proofs);
 }
 static void propagation(){
  var globals=new ArrayList<ExactLinearProgram.Constraint>();globals.add(row(2,1,1,1));for(int i=0;i<3;i++)globals.add(bound(i,1,false));
  var inputs=new ArrayList<>(globals);inputs.add(bound(0,1,true));inputs.add(bound(1,1,true));var no=new CountConflict(List.of(bound(0,1,true),bound(1,1,true),bound(2,1,true)));
  var b=budget();try(var bounds=new CountBounds(3,inputs,b,globals.size())){bounds.learn(List.of(no));while(!bounds.step()){}check(!bounds.blocked()&&bounds.upperBounds()[2].signum()==0,"unit nogood did not propagate");}
  // An arbitrary signed implication is independently checked over every assignment.
  Random r=new Random(921);int checked=0;
  for(int t=0;t<1500;t++){
   int n=4;var assumptions=new ArrayList<ExactLinearProgram.Constraint>();for(int j=0;j<1+r.nextInt(5);j++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=r.nextInt(5)-2;assumptions.add(row(r.nextInt(15)-3,a));}
   var low=new BigInteger[n];var high=new BigInteger[n];for(int i=0;i<n;i++){low[i]=z(r.nextInt(3));high[i]=z(low[i].intValue()+r.nextInt(4-low[i].intValue()));}
   var learned=new CountConflict(assumptions);var consequence=learned.propagate(low,high,budget());if(consequence==null)continue;
   for(int code=0;code<256;code++){var x=vector(n,code,4);if(!domain(x,low,high)||holds(assumptions,x))continue;check(consequence.row()!=null&&holds(List.of(consequence.row()),x),"unsound nogood propagation");}checked++;
  }
  // Copy the fixed point, then change just one independent variable.
  int n=128;var base=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<n;i++)base.add(bound(i,100,false));
  var sharedBudget=budget();CountBounds.Seed seed;try(var p=new CountBounds(n,base,sharedBudget,base.size())){while(!p.step()){}seed=p.snapshot();}
  var child=new ArrayList<>(base);child.add(bound(77,49,false));long coldStart=sharedBudget.nodes();BigInteger[] cold;
  try(var p=new CountBounds(n,child,sharedBudget,base.size())){while(!p.step()){}cold=p.upperBounds();}long coldWork=sharedBudget.nodes()-coldStart;
  long warmStart=sharedBudget.nodes();try(var p=new CountBounds(n,child,sharedBudget,base.size(),seed)){while(!p.step()){}check(Arrays.equals(cold,p.upperBounds()),"inherited bounds mismatch");}long warmWork=sharedBudget.nodes()-warmStart;seed.close();
  check(warmWork<coldWork/4,"bounds not incremental cold="+coldWork+" warm="+warmWork);
  System.out.println("PASS nogood implications="+checked+" incremental independent bounds cold="+coldWork+" warm="+warmWork);
 }
 static void warm(){Random r=new Random(260926);int cases=0,dead=0;long coldWork=0,hotWork=0;
  for(int t=0;t<400;t++){
   int n=2+r.nextInt(6);var rows=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<n;i++)rows.add(bound(i,8,false));
   for(int j=0;j<n;j++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=r.nextInt(7)-3;rows.add(row(1+r.nextInt(15),a));}
   // Duplicate original rows exercise slack identity preservation.
   if(t%3==0)rows.add(rows.get(0));
   var objective=new BigInteger[n];for(int i=0;i<n;i++)objective[i]=z(r.nextInt(7)-3);
   var budget=budget();ExactLinearProgram.Basis basis;
   try(var p=new ExactLinearProgram(n,rows,objective,budget,null,true)){while(!p.step()){}check(p.result()==ExactLinearProgram.Result.OPTIMAL,"root LP failed");basis=p.takeBasis();}
   check(basis!=null,"missing root basis");
   for(int k=0;k<3;k++){
    var child=new ArrayList<>(rows);child.add(bound(r.nextInt(n),r.nextInt(11)-1,r.nextBoolean()));
    var before=budget.nodes();ExactLinearProgram.Result expected;ExactRational[] reference;
    try(var cold=new ExactLinearProgram(n,child,objective,budget)){while(!cold.step()){}expected=cold.result();reference=cold.point();}
    coldWork+=budget.nodes()-before;before=budget.nodes();
    try(var hot=new ExactLinearProgram(n,child,objective,budget,basis,true)){while(!hot.step()){}check(hot.hot(),"basis not used");check(hot.result()==expected,"LP hot/cold mismatch test="+t+" child="+k+" "+hot.result()+" / "+expected);
     if(expected==ExactLinearProgram.Result.OPTIMAL){ExactRational a=ExactRational.ZERO,c=ExactRational.ZERO;var point=hot.point();for(int i=0;i<n;i++){a=a.add(point[i].multiply(ExactRational.of(objective[i])));c=c.add(reference[i].multiply(ExactRational.of(objective[i])));}check(a.equals(c),"wrong warm optimum");
      // Exercise a grandchild whose parent basis already contains appended slacks.
      var next=hot.takeBasis();if(k==2&&next!=null){var third=new ArrayList<>(child);third.add(bound(0,4,false));ExactLinearProgram.Result cr;ExactRational[] cp;
       try(var cold=new ExactLinearProgram(n,third,objective,budget)){while(!cold.step()){}cr=cold.result();cp=cold.point();}
       try(var nested=new ExactLinearProgram(n,third,objective,budget,next,false)){while(!nested.step()){}check(nested.result()==cr,"grandchild status");if(cr==ExactLinearProgram.Result.OPTIMAL){ExactRational aa=ExactRational.ZERO,cc=ExactRational.ZERO;for(int i=0;i<n;i++){aa=aa.add(nested.point()[i].multiply(ExactRational.of(objective[i])));cc=cc.add(cp[i].multiply(ExactRational.of(objective[i])));}check(aa.equals(cc),"grandchild objective");}}
      }if(next!=null)next.close();
     }else if(expected==ExactLinearProgram.Result.INFEASIBLE)dead++;else throw new AssertionError("unexpected limited tiny LP");
    }hotWork+=budget.nodes()-before;cases++;
   }basis.close();check(budget.reservedBytes()==0,"LP snapshot reservation leak "+budget.reservedBytes());
  }System.out.println("PASS rational LP hot/cold cases="+cases+" infeasible="+dead+" cold_work="+coldWork+" hot_work_with_grandchildren="+hotWork);
 }
 static void inherited(){Random r=new Random(128192);int checks=0;
  for(int t=0;t<700;t++){
   int n=4;var globals=new ArrayList<ExactLinearProgram.Constraint>();for(int i=0;i<n;i++)globals.add(bound(i,3,false));
   for(int j=0;j<4;j++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=r.nextInt(7)-3;globals.add(row(r.nextInt(12),a));}
   var current=new ArrayList<ExactLinearProgram.Constraint>();current.add(bound(r.nextInt(n),r.nextInt(3),r.nextBoolean()));
   var input=new ArrayList<>(globals);input.addAll(current);var budget=budget();CountBounds.Seed seed;
   try(var root=new CountBounds(n,input,budget,globals.size())){while(!root.step()){}if(root.blocked())continue;seed=root.snapshot();}
   for(int k=0;k<4;k++){
    var decisions=new ArrayList<>(current);decisions.add(bound(r.nextInt(n),r.nextInt(4),r.nextBoolean()));var child=new ArrayList<>(globals);child.addAll(decisions);
    try(var cold=new CountBounds(n,child,budget,globals.size());var hot=new CountBounds(n,child,budget,globals.size(),seed)){
     while(!cold.step()){}while(!hot.step()){}check(cold.blocked()==hot.blocked(),"bound fork status "+t);
     for(int code=0;code<256;code++){var x=vector(n,code,4);if(holds(child,x)){check(!hot.blocked()&&domain(x,hot.lowerBounds(),hot.upperBounds()),"fork pruned feasible assignment");}}
     if(hot.blocked()){var used=hot.conflictingAssumptions();check(used!=null,"missing conflict explanation");var proof=new ArrayList<>(globals);for(int i=used.nextSetBit(0);i>=0;i=used.nextSetBit(i+1))proof.add(decisions.get(i));for(int code=0;code<256;code++)check(!holds(proof,vector(n,code,4)),"unsound inherited proof");}
    }checks++;
   }seed.close();check(budget.reservedBytes()==0,"bound snapshot reservation leak");
  }System.out.println("PASS incremental bound forks="+checks+" and independently checked conflict assumptions");
 }
 static void longBoundaries(){
  var budget=budget();BigInteger cap=z(Long.MAX_VALUE).pow(2);var rootRows=List.of(new ExactLinearProgram.Constraint(Map.of(0,z(1)),cap));var objective=new BigInteger[]{z(1)};ExactLinearProgram.Basis basis;
  try(var root=new ExactLinearProgram(1,rootRows,objective,budget,null,true)){while(!root.step()){}check(root.point()[0].numerator().equals(cap),"long^2 root");basis=root.takeBasis();}
  var child=new ArrayList<>(rootRows);child.add(new ExactLinearProgram.Constraint(Map.of(0,z(1)),cap.subtract(z(1))));
  try(var hot=new ExactLinearProgram(1,child,objective,budget,basis,false)){while(!hot.step()){}check(hot.hot()&&hot.point()[0].numerator().equals(cap.subtract(z(1))),"lost single unit above long");}
  // A basis from a tighter, unrelated order must not restrict this new model.
  var changed=List.of(new ExactLinearProgram.Constraint(Map.of(0,z(1)),cap.add(z(1))));
  try(var fresh=new ExactLinearProgram(1,changed,objective,budget,basis,false)){while(!fresh.step()){}check(!fresh.hot()&&fresh.point()[0].numerator().equals(cap.add(z(1))),"stale order constraint reused");}
  basis.close();check(budget.reservedBytes()==0,"long basis memory leak");
  System.out.println("PASS long^2 plus/minus one and unrelated-order basis rejection");
 }
}
