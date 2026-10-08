package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
public class AffineReuseProbe {
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
        var actual=(BigInteger[])call(x,"nearestPoint");call(x,"gramSchmidt");
        check(Arrays.deepEquals(oldMu,(ExactRational[][])field(x,"mu")),"mu differs from complete exact reconstruction");
        check(Arrays.equals(oldNorms,(ExactRational[])field(x,"norms")),"norms differ from complete exact reconstruction");
        var point=(BigInteger[])field(x,"point");var lo=(BigInteger[])field(x,"lower");var hi=(BigInteger[])field(x,"upper");var orth=(ExactRational[][])field(x,"orthogonal");var norms=(ExactRational[])field(x,"norms");var basis=(List<BigInteger[]>)field(x,"basis");int attempt=(int)field(x,"attempt");
        var expected=point.clone();ExactRational[] residual=new ExactRational[point.length];
        for(int i=0;i<point.length;i++){int fraction=attempt==0?8:2+Math.floorMod(i*7+attempt*5,13);var target=ExactRational.of(lo[i]).add(new ExactRational(hi[i].subtract(lo[i]).multiply(b(fraction)),b(16)));residual[i]=target.subtract(ExactRational.of(point[i]));}
        for(int j=basis.size()-1;j>=0;j--){ExactRational value=ExactRational.ZERO;for(int i=0;i<point.length;i++)value=value.add(residual[i].multiply(orth[j][i]));var q=round(value.divide(norms[j]));for(int i=0;i<point.length;i++){var change=basis.get(j)[i].multiply(q);expected[i]=expected[i].add(change);residual[i]=residual[i].subtract(ExactRational.of(change));}}
        check(Arrays.equals(actual,expected),"Babai factorization projection differs");projections++;
        field(x,"work",originalWork);field(x,"allowance",allowance);
    }
    static void randomized()throws Exception{
        Random random=new Random(9418831);
        for(int sample=0;sample<1200;sample++){
            int n=3+random.nextInt(16);BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n],witness=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=b(random.nextInt(5)-2);if(sample%4==0)lo[i]=lo[i].add(BigInteger.ONE.shiftLeft(70+sample%70).multiply(b(i%2==0?1:-1)));hi[i]=lo[i].add(b(3+random.nextInt(6)));witness[i]=lo[i].add(b(random.nextInt(hi[i].subtract(lo[i]).intValue()+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int k=0;k<Math.max(2,n/2);k++){var terms=new LinkedHashMap<Integer,BigInteger>();for(int i=0;i<n;i++)if(random.nextInt(4)!=0){int a=random.nextInt(13)-6;if(a!=0)terms.put(i,b(a));}var rhs=dot(terms,witness);rows.add(new ExactLinearProgram.Constraint(terms,rhs));var reverse=new LinkedHashMap<Integer,BigInteger>();terms.forEach((i,a)->reverse.put(i,a.negate()));rows.add(new ExactLinearProgram.Constraint(reverse,rhs.negate()));}
            Collections.shuffle(rows,random);var budget=new PlanningBudget(0,50000000,256L<<20,()->false,System::nanoTime);BigInteger[] oldValue;
            try(var original=new ReferenceAffineLattice(rows,lo,hi,budget)){while(!original.step()){}oldValue=original.counts();if(oldValue!=null)oldSolved++;}
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
    public static void main(String[] args)throws Exception{randomized();}
}
