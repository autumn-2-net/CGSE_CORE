package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class StrengtheningOracle {
    static long checks, vectors, shrunk;
    static BigInteger b(long n) { return BigInteger.valueOf(n); }
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static PlanningBudget budget() { return new PlanningBudget(0, 20_000_000, 128L<<20, ()->false, System::nanoTime); }
    static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, BigInteger[] x) {
        for(int i=0;i<x.length;i++) if(x[i].compareTo(lo[i])<0 || hi[i]!=null&&x[i].compareTo(hi[i])>0)return false;
        for(var r:rows){BigInteger sum=b(0);for(var t:r.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(r.upper())>0)return false;}
        return true;
    }
    static void eq(List<ExactLinearProgram.Constraint> rows, Map<Integer,BigInteger> terms, BigInteger rhs) {
        rows.add(new ExactLinearProgram.Constraint(terms,rhs));var opposite=new LinkedHashMap<Integer,BigInteger>();
        terms.forEach((i,a)->opposite.put(i,a.negate()));rows.add(new ExactLinearProgram.Constraint(opposite,rhs.negate()));
    }
    static void enumerate(BigInteger[] lo, BigInteger[] hi, java.util.function.Consumer<BigInteger[]> check) {
        for(int i=0;i<lo.length;i++)if(lo[i].compareTo(hi[i])>0)return;
        BigInteger[] x=lo.clone();
        while(true){vectors++;check.accept(x);int i=0;while(i<x.length && x[i].equals(hi[i])){x[i]=lo[i];i++;}if(i==x.length)return;x[i]=x[i].add(b(1));}
    }
    static void model(int trial, List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi) {
        var budget=budget();try(var reduction=new CountReduction(rows,lo,hi,budget);var views=CountModelViews.create(rows,lo,hi,budget)) {
            views.compileLight();while(!reduction.step()){}views.addReduced(reduction);
            if(reduction.variables()<lo.length)shrunk++;
            int[] reps=reduction.representatives();
            enumerate(lo,hi,x->{
                boolean truth=valid(rows,lo,hi,x);
                for(var view:views.available())if(view.name().equals("normalized"))check(truth==valid(view.rows(),view.lower(),view.upper(),x),"light equivalence "+trial);
                var y=new BigInteger[reps.length];for(int i=0;i<y.length;i++)y[i]=x[reps[i]];
                if(truth){
                    check(valid(reduction.rows(),reduction.lower(),reduction.upper(),y),"lost original solution "+trial);
                    check(Arrays.equals(x,reduction.expand(y)),"inverse mismatch "+trial);
                }
            });
            enumerate(reduction.lower(),reduction.upper(),y->{if(valid(reduction.rows(),reduction.lower(),reduction.upper(),y))check(valid(rows,lo,hi,reduction.expand(y)),"introduced solution "+trial);});
            var objective=new BigInteger[lo.length];for(int i=0;i<objective.length;i++)objective[i]=b((i*3+trial)%9-4);
            BigInteger minimum=reduction.minimum(objective);
            enumerate(lo,hi,x->{if(valid(rows,lo,hi,x)){BigInteger value=b(0);for(int i=0;i<x.length;i++)value=value.add(x[i].multiply(objective[i]));check(minimum==null||minimum.compareTo(value)<=0,"invalid objective lower bound "+trial);}});
        }
        check(budget.reservedBytes()==0,"workspace leak "+trial);
    }
    public static void main(String[] args) {
        Random random=new Random(0xCA53E);
        for(int trial=0;trial<1800;trial++){
            int n=2+random.nextInt(4);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=b(random.nextInt(2));hi[i]=lo[i].add(b(random.nextInt(5)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<2+random.nextInt(7);r++){
                var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=b(random.nextInt(101)-50);int scale=trial%3==0?7:1;
                for(int i=0;i<n;i++){long a=random.nextInt(101)-50;if(a!=0)terms.put(i,b(a*scale));}
                rows.add(new ExactLinearProgram.Constraint(terms,rhs.multiply(b(scale))));
            }
            for(int i=0;i<n-1;i++)if(random.nextBoolean())eq(rows,Map.of(i,b(1),i+1,b(random.nextBoolean()?2+random.nextInt(5):-2-random.nextInt(5))),b(random.nextInt(16)-4));
            Collections.shuffle(rows,random);model(trial,rows,lo,hi);
        }
        // Planted chains exercise both orientations, reflection, fixed endpoints,
        // non-divisible bounds and 130-bit offsets, with all solutions retained.
        for(int t=0;t<120;t++){
            BigInteger base=t%3==0?b(1).shiftLeft(130):b(0);
            BigInteger[] lo={base,base,base,base},hi={base.add(b(6)),base.add(b(6)),base.add(b(6)),base.add(b(6))};
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            eq(rows,Map.of(0,b(1),1,b(-2)),base.negate());
            eq(rows,Map.of(1,b(3),2,b(1)),base.multiply(b(4)).add(b(6)));
            eq(rows,Map.of(2,b(-1),3,b(1)),b(0));
            if(t%2==0)hi[0]=base.add(b(5));Collections.shuffle(rows,new Random(t));model(1800+t,rows,lo,hi);
        }
        // Both coefficients non-unit: no rational substitution that drops a residue class.
        var rows=new ArrayList<ExactLinearProgram.Constraint>();eq(rows,Map.of(0,b(3),1,b(-2)),b(1));
        model(1920,rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(12),b(12)});
        var rootBudget=budget();try(var reduced=new CountReduction(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(12),b(12)},rootBudget)){
            while(!reduced.step()){}check(reduced.variables()==2,"non-unit pivot must preserve coordinates");
        }
        // Unbounded upper domains cannot be treated as finite coefficient saturation.
        var unbounded=List.of(new ExactLinearProgram.Constraint(Map.of(0,b(-999),1,b(1)),b(-50)));
        var ub=budget();try(var views=CountModelViews.create(unbounded,new BigInteger[]{b(0),b(0)},new BigInteger[]{null,null},ub)){
            views.compileLight();for(var v:views.available())for(int x=0;x<5;x++)for(int y=0;y<10;y++){
                var point=new BigInteger[]{b(x),b(y)};check(valid(unbounded,v.lower(),v.upper(),point)==valid(v.rows(),v.lower(),v.upper(),point),"unbounded saturation");
            }
        }
        for(int stop=1;stop<=1000;stop++){
            int[] at={0};int limit=stop;var budget=new PlanningBudget(0,20_000_000,128L<<20,()->++at[0]>=limit,System::nanoTime);
            try(var reduction=new CountReduction(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(12),b(12)},budget)){
                while(!reduction.step()){}
                try(var views=CountModelViews.create(rows,new BigInteger[]{b(0),b(0)},new BigInteger[]{b(12),b(12)},budget)){views.compileLight();views.addReduced(reduction);}
            }catch(java.util.concurrent.CancellationException expected){}
            check(budget.reservedBytes()==0,"cancel workspace leak "+stop);
        }
        check(shrunk>100,"affine path not exercised");
        System.out.println("PASS strengthening models=1921 vectors="+vectors+" checks="+checks+" reduced="+shrunk+" cancel=1000");
    }
}
