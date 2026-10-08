package org.cgse.core;
import java.math.BigInteger;
import java.util.*;

public final class ResidueProbe {
    static int assertions, systems, feasible, narrowed, removed;
    static BigInteger b(long x){return BigInteger.valueOf(x);}
    static void check(boolean good,String message){assertions++;if(!good)throw new AssertionError(message);}
    static ExactLinearProgram.Constraint row(long upper,long... coefficients){Map<Integer,BigInteger> m=new LinkedHashMap<>();for(int i=0;i<coefficients.length;i++)if(coefficients[i]!=0)m.put(i,b(coefficients[i]));return new ExactLinearProgram.Constraint(m,b(upper));}
    static void equality(List<ExactLinearProgram.Constraint> rows,ExactLinearProgram.Constraint row){rows.add(row);Map<Integer,BigInteger> m=new LinkedHashMap<>();row.terms().forEach((i,a)->m.put(i,a.negate()));rows.add(new ExactLinearProgram.Constraint(m,row.upper().negate()));}
    static boolean holds(List<ExactLinearProgram.Constraint> rows,BigInteger[] point){for(var row:rows){BigInteger total=BigInteger.ZERO;for(var term:row.terms().entrySet())total=total.add(term.getValue().multiply(point[term.getKey()]));if(total.compareTo(row.upper())>0)return false;}return true;}
    static List<BigInteger[]> points(BigInteger[] low,BigInteger[] high){var out=new ArrayList<BigInteger[]>();enumerate(out,low.clone(),low,high,0);return out;}
    static void enumerate(List<BigInteger[]> out,BigInteger[] value,BigInteger[] low,BigInteger[] high,int k){if(k==value.length){out.add(value.clone());return;}if(high[k]==null||high[k].subtract(low[k]).compareTo(b(100))>0)throw new AssertionError("unbounded oracle");for(BigInteger x=low[k];x.compareTo(high[k])<=0;x=x.add(BigInteger.ONE)){value[k]=x;enumerate(out,value,low,high,k+1);}}
    static String key(BigInteger[] point){return Arrays.toString(point);}
    static void system(List<ExactLinearProgram.Constraint> rows,BigInteger[] low,BigInteger[] high){
        systems++;var truth=points(low,high).stream().filter(p->holds(rows,p)).toList();feasible+=truth.isEmpty()?0:1;
        var budget=new PlanningBudget(0,10_000_000,64L<<20,()->false,System::nanoTime);
        try(var p=new CountResiduePresolve(rows,low,high,budget)){
            while(!p.step()){}var cuts=p.cuts();if(!cuts.isEmpty())narrowed++;
            for(var point:truth){check(holds(cuts,point),"lost residue solution rows="+rows+" cuts="+cuts+" p="+key(point));for(int i=0;i<low.length;i++)check(point[i].compareTo(p.lower()[i])>=0&&(p.upper()[i]==null||point[i].compareTo(p.upper()[i])<=0),"lost interval");}
        }
        try(var reduction=new CountReduction(rows,low,high,budget,true)){
            while(!reduction.step()){}if(reduction.variables()<low.length)removed++;
            var expected=new HashSet<String>();truth.forEach(p->expected.add(key(p)));
            var got=new HashSet<String>();for(var point:points(reduction.lower(),reduction.upper()))if(holds(reduction.rows(),point)){
                var restored=reduction.expand(point);check(holds(rows,restored),"invalid restored rows="+rows+" reduced="+reduction.rows()+" restored="+key(restored));for(int i=0;i<low.length;i++)check(restored[i].compareTo(low[i])>=0&&restored[i].compareTo(high[i])<=0,"restored domain");got.add(key(restored));
            }
            check(expected.equals(got),"mapping mismatch rows="+rows+" lo="+key(low)+" hi="+key(high)+" expected="+expected+" got="+got+" reduced="+reduction.rows());
            for(var point:truth){int[] reps=reduction.representatives();var reduced=new BigInteger[reps.length];for(int i=0;i<reps.length;i++)reduced[i]=point[reps[i]];check(Arrays.equals(reduction.expand(reduced),point),"representative coordinates changed");}
        }
        check(budget.reservedBytes()==0,"memory leak "+budget.reservedBytes());
    }
    static void targeted(){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();equality(rows,row(16,6,10));system(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(10),b(1)});
        rows.clear();equality(rows,row(1,1,-4,0));equality(rows,row(3,1,0,-6));system(rows,new BigInteger[]{b(0),b(0),b(0)},new BigInteger[]{b(30),b(8),b(8)});
        rows.clear();equality(rows,row(-1,-1,4,0));equality(rows,row(2,1,0,-6));system(rows,new BigInteger[]{b(0),b(0),b(0)},new BigInteger[]{b(30),b(8),b(8)});
        rows.clear();equality(rows,row(1,1,-4));var budget=new PlanningBudget(0,1_000_000,()->false);
        try(var p=new CountResiduePresolve(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{null,null},budget)){while(!p.step()){}check(p.lower()[0].equals(b(1))&&p.upper()[0]==null,"unbounded residue");}
        check(budget.reservedBytes()==0,"unbounded leak");
        BigInteger modulus=BigInteger.ONE.shiftLeft(100).add(BigInteger.ONE),lo=BigInteger.ONE.shiftLeft(130);rows.clear();equality(rows,new ExactLinearProgram.Constraint(Map.of(0,BigInteger.ONE,1,modulus.negate()),b(7)));
        budget=new PlanningBudget(0,1_000_000,()->false);try(var p=new CountResiduePresolve(rows,new BigInteger[]{lo,b(0)},new BigInteger[]{lo.add(modulus.multiply(b(3))),null},budget)){while(!p.step()){}check(p.lower()[0].subtract(b(7)).mod(modulus).signum()==0,"big residue lower");check(p.upper()[0].subtract(b(7)).mod(modulus).signum()==0,"big residue upper");}check(budget.reservedBytes()==0,"big leak");
        rows= new ArrayList<>(List.of(row(1,1,-4)));budget=new PlanningBudget(0,1_000_000,()->false);try(var p=new CountResiduePresolve(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(20),b(20)},budget)){while(!p.step()){}check(p.cuts().isEmpty(),"one direction treated as equality");}
        rows.clear();equality(rows,row(1,1,-4,0));equality(rows,row(2,1,0,-6));
        for(int limit=1;limit<250;limit++){budget=new PlanningBudget(0,limit,()->false);try(var p=new CountResiduePresolve(rows,new BigInteger[]{b(0),b(0),b(0)},new BigInteger[]{b(30),b(8),b(8)},budget)){while(!p.step()){}}catch(PlanningBudget.Exhausted ignored){}check(budget.reservedBytes()==0,"limit leak "+limit);}
        for(int memory:new int[]{1,256,4096,16384}){budget=new PlanningBudget(0,1_000_000,memory,()->false,System::nanoTime);try(var p=new CountResiduePresolve(rows,new BigInteger[]{b(0),b(0),b(0)},new BigInteger[]{b(30),b(8),b(8)},budget)){while(!p.step()){}}catch(PlanningBudget.Exhausted ignored){}check(budget.reservedBytes()==0,"memory leak "+memory);}
    }
    public static void main(String[]args){targeted();Random random=new Random(202610031L);for(int trial=0;trial<2500;trial++){int n=2+random.nextInt(3);var low=new BigInteger[n];var high=new BigInteger[n];for(int i=0;i<n;i++){low[i]=b(random.nextInt(2));high[i]=low[i].add(b(1+random.nextInt(5)));}var rows=new ArrayList<ExactLinearProgram.Constraint>();int eq=1+random.nextInt(3);for(int e=0;e<eq;e++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=random.nextInt(15)-7;equality(rows,row(random.nextInt(41)-20,a));}for(int j=0;j<random.nextInt(3);j++){long[] a=new long[n];for(int i=0;i<n;i++)a[i]=random.nextInt(7)-3;rows.add(row(random.nextInt(31)-10,a));}system(rows,low,high);}System.out.println("PASS systems="+systems+" feasible="+feasible+" residue_tightened="+narrowed+" dimension_reduced="+removed+" assertions="+assertions);}
}
