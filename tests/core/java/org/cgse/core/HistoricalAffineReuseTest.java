// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
// Migrated from AffineReuseProbe-d50ff58d6c07; immutable original remains under tests/probes.
package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
public class HistoricalAffineReuseTest {
    static int checks,oldSolved,newSolved,regressions,projections;
    static Object field(Object v,String name)throws Exception{var f=v.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(v);}
    static void field(Object v,String name,Object value)throws Exception{var f=v.getClass().getDeclaredField(name);f.setAccessible(true);f.set(v,value);}
    static Object call(Object v,String name)throws Exception{var m=v.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(v);}
    static void check(boolean p,String s){checks++;if(!p)throw new AssertionError(s);}
    static BigInteger b(long n){return BigInteger.valueOf(n);}
    static BigInteger dot(Map<Integer,BigInteger> terms,BigInteger[] v){BigInteger x=BigInteger.ZERO;for(var e:terms.entrySet())x=x.add(e.getValue().multiply(v[e.getKey()]));return x;}
    static BigInteger round(ExactRational x){return x.add(new ExactRational(b(1),b(2))).floor();}
    static ExactRational[][] copy(ExactRational[][] v){return Arrays.stream(v).map(ExactRational[]::clone).toArray(ExactRational[][]::new);}
    static void invariants(CountAffineLattice x)throws Exception{
        int equation=(int)field(x,"equation");var rows=(List<ExactLinearProgram.Constraint>)field(x,"equations");var p=(BigInteger[])field(x,"point");var basis=(List<BigInteger[]>)field(x,"basis");
        if(p==null)return;
        for(int r=0;r<Math.min(equation,rows.size());r++){var row=rows.get(r);check(dot(row.terms(),p).equals(row.upper()),"particular equation");for(var col:basis)check(dot(row.terms(),col).signum()==0,"kernel direction");}
    }
    static void compareProjection(CountAffineLattice x)throws Exception{
        long originalWork=(long)field(x,"work"),allowance=(long)field(x,"allowance");field(x,"allowance",Long.MAX_VALUE);
        var oldMu=copy((ExactRational[][])field(x,"mu"));var oldNorms=((ExactRational[])field(x,"norms")).clone();
        var actual=(BigInteger[])call(x,"nearestPoint");
        var basis=(List<BigInteger[]>)field(x,"basis");
        var orth=new ExactRational[basis.size()][];
        var mu=new ExactRational[basis.size()][basis.size()];
        var norms=new ExactRational[basis.size()];
        // Independent classical Gram-Schmidt; production keeps only LDL factors.
        for(int j=0;j<basis.size();j++){
            Arrays.fill(mu[j],ExactRational.ZERO);
            orth[j]=Arrays.stream(basis.get(j)).map(ExactRational::of).toArray(ExactRational[]::new);
            for(int k=0;k<j;k++){
                var product=ExactRational.ZERO;
                for(int i=0;i<orth[j].length;i++)product=product.add(ExactRational.of(basis.get(j)[i]).multiply(orth[k][i]));
                mu[j][k]=product.divide(norms[k]);
                for(int i=0;i<orth[j].length;i++)orth[j][i]=orth[j][i].subtract(mu[j][k].multiply(orth[k][i]));
            }
            norms[j]=ExactRational.ZERO;
            for(var value:orth[j])norms[j]=norms[j].add(value.multiply(value));
        }
        check(Arrays.deepEquals(oldMu,mu),"mu differs from complete exact reconstruction");
        check(Arrays.equals(oldNorms,norms),"norms differ from complete exact reconstruction");
        var point=(BigInteger[])field(x,"point");var lo=(BigInteger[])field(x,"lower");var hi=(BigInteger[])field(x,"upper");int attempt=(int)field(x,"attempt");
        var expected=point.clone();ExactRational[] residual=new ExactRational[point.length];
        for(int i=0;i<point.length;i++){int fraction=(boolean)field(x,"lowerFirst")?0:attempt==0?8:attempt==8?0:2+Math.floorMod(i*7+attempt*5,13);var target=ExactRational.of(lo[i]).add(new ExactRational(hi[i].subtract(lo[i]).multiply(b(fraction)),b(16)));residual[i]=target.subtract(ExactRational.of(point[i]));}
        for(int j=basis.size()-1;j>=0;j--){ExactRational value=ExactRational.ZERO;for(int i=0;i<point.length;i++)value=value.add(residual[i].multiply(orth[j][i]));var q=round(value.divide(norms[j]));for(int i=0;i<point.length;i++){var change=basis.get(j)[i].multiply(q);expected[i]=expected[i].add(change);residual[i]=residual[i].subtract(ExactRational.of(change));}}
        check(Arrays.equals(actual,expected),"Babai factorization projection differs");projections++;
        field(x,"work",originalWork);field(x,"allowance",allowance);
    }
    static void randomized(boolean largeOffsets)throws Exception{
        Random random=new Random(9418831);
        for(int sample=0;sample<1200;sample++){
            int n=3+random.nextInt(16);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],witness=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=b(random.nextInt(5)-2);if(largeOffsets&&sample%4==0)lo[i]=lo[i].add(BigInteger.ONE.shiftLeft(70+sample%70).multiply(b(i%2==0?1:-1)));hi[i]=lo[i].add(b(3+random.nextInt(6)));witness[i]=lo[i].add(b(random.nextInt(hi[i].subtract(lo[i]).intValue()+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int k=0;k<Math.max(2,n/2);k++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)if(random.nextInt(4)!=0){int a=random.nextInt(13)-6;if(a!=0)terms.put(i,b(a));}var rhs=dot(terms,witness);rows.add(new ExactLinearProgram.Constraint(terms,rhs));var reverse=new LinkedHashMap<Integer,BigInteger>();terms.forEach((i,a)->reverse.put(i,a.negate()));rows.add(new ExactLinearProgram.Constraint(reverse,rhs.negate()));}
            Collections.shuffle(rows,random);var budget=new PlanningBudget(0,50000000,256L<<20,()->false,System::nanoTime);BigInteger[] oldValue;
            try(var original=new HistoricalReferenceAffineLattice(rows,lo,hi,budget)){while(!original.step()){}oldValue=original.counts();if(oldValue!=null)oldSolved++;}
            check(budget.reservedBytes()==0,"reference memory");
            budget=new PlanningBudget(0,50000000,256L<<20,()->false,System::nanoTime);
            try(var current=new CountAffineLattice(rows,lo,hi,budget)){
                while(!current.step()){invariants(current);if((int)field(current,"phase")==3)compareProjection(current);}
                var value=current.counts();if(value!=null){newSolved++;for(int i=0;i<n;i++)check(value[i].compareTo(lo[i])>=0&&value[i].compareTo(hi[i])<=0,"domain");for(var row:rows)check(dot(row.terms(),value).compareTo(row.upper())<=0,"exact original row");}else if(oldValue!=null)regressions++;
            }
            check(budget.reservedBytes()==0,"candidate memory");
        }
        System.out.println("oldSolved="+oldSolved+" newSolved="+newSolved+" regressions="+regressions+" projections="+projections+" checks="+checks);
        check(regressions==0,"direct algorithm feasible regressions");
    }
    public static void main(String[] args)throws Exception{randomized(false);randomized(true);}
}
