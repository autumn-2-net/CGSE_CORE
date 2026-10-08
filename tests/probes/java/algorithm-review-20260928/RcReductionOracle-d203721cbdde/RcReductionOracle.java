package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent finite-domain equivalence check; retained only locally. */
public class RcReductionOracle {
    static final BigInteger Z=BigInteger.ZERO, O=BigInteger.ONE;
    static long checked, legal;
    static int fixedAliases;

    static boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] lo, BigInteger[] hi, BigInteger[] x) {
        for(int i=0;i<x.length;i++) if(x[i].compareTo(lo[i])<0 || hi[i]!=null && x[i].compareTo(hi[i])>0) return false;
        for(var row:rows){var sum=Z;for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(x[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}
        return true;
    }
    static ExactLinearProgram.Constraint row(long bound,long... terms){var map=new TreeMap<Integer,BigInteger>();for(int i=0;i<terms.length;i++)if(terms[i]!=0)map.put(i,BigInteger.valueOf(terms[i]));return new ExactLinearProgram.Constraint(map,BigInteger.valueOf(bound));}
    static void equation(List<ExactLinearProgram.Constraint> rows,long bound,long... terms){rows.add(row(bound,terms));long[] neg=terms.clone();for(int i=0;i<neg.length;i++)neg[i]=-neg[i];rows.add(row(-bound,neg));}
    static void verify(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi, long memory,int test){
        var b=new PlanningBudget(0,20_000_000,memory,()->false,System::nanoTime);
        try(var reduced=new CountReduction(rows,lo,hi,b)){
            while(!reduced.step()){}
            var x=lo.clone();var rlo=reduced.lower();var rhi=reduced.upper();var reps=reduced.representatives();
            var coordinate=reduced.coordinates();
            for(int i=0;i<lo.length;i++)if(coordinate.roots().get(i)<0 && coordinate.offsets().get(i).signum()!=0 && !lo[i].equals(hi[i]))fixedAliases++;
            while(true){
                var y=new BigInteger[reps.length];for(int i=0;i<y.length;i++)y[i]=x[reps[i]];
                boolean truth=valid(rows,lo,hi,x), transformed=valid(reduced.rows(),rlo,rhi,y)&&Arrays.equals(x,reduced.expand(y));
                checked++;if(truth)legal++;
                if(truth!=transformed)throw new AssertionError("equivalence test="+test+" original="+truth+" x="+Arrays.toString(x)+" lo="+Arrays.toString(lo)+" hi="+Arrays.toString(hi)+" mapping="+coordinate+" rows="+rows+" compiled="+reduced.rows()+" notes="+b.diagnostics());
                int i=0;while(i<x.length&&x[i].equals(hi[i])){x[i]=lo[i];i++;}if(i==x.length)break;x[i]=x[i].add(O);
            }
        }
        if(b.reservedBytes()!=0)throw new AssertionError("memory leak");
    }
    public static void main(String[] args){
        var rng=new Random(2026092831L);
        for(int t=0;t<3200;t++){
            int n=3+rng.nextInt(4);var lo=new BigInteger[n];var hi=new BigInteger[n];var planted=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=BigInteger.valueOf(rng.nextInt(2));hi[i]=lo[i].add(BigInteger.valueOf(1+rng.nextInt(3)));planted[i]=lo[i].add(BigInteger.valueOf(rng.nextInt(hi[i].subtract(lo[i]).intValue()+1)));}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int r=0;r<5+rng.nextInt(6);r++){
                var terms=new TreeMap<Integer,BigInteger>();var sum=Z;
                for(int i=0;i<n;i++){var c=BigInteger.valueOf(rng.nextInt(11)-5);if(c.signum()!=0)terms.put(i,c);sum=sum.add(c.multiply(planted[i]));}
                rows.add(new ExactLinearProgram.Constraint(terms,sum.add(BigInteger.valueOf(rng.nextInt(6)-(t%3==0?3:0)))));
            }
            for(int i=1;i<n;i+=2){int s=rng.nextBoolean()?1:-1;equation(rows,planted[i-1].add(planted[i].multiply(BigInteger.valueOf(s))).longValueExact(),indexed(n,i-1,1,i,s));}
            Collections.shuffle(rows,rng);verify(rows,lo,hi,t%17==0?512:256L<<20,t);
        }
        // The first substitution fixes its aliases in a later saturation pass.
        for(int t=0;t<80;t++){
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            equation(rows,3,1,1,0,0,0,0);
            equation(rows,4,0,0,1,1,0,0);
            rows.add(row(-3,-1,-1,0,0,0,2));
            rows.add(row(-4,0,0,-1,-1,-1,0));
            rows.add(row(7,1,1,1,1,1,1));
            if(t%2==0) equation(rows,2,0,0,0,0,1,1);
            Collections.shuffle(rows,rng);
            var lo=new BigInteger[]{Z,Z,Z,Z,Z,Z};var hi=new BigInteger[]{BigInteger.valueOf(3),BigInteger.valueOf(3),BigInteger.valueOf(4),BigInteger.valueOf(4),BigInteger.TWO,BigInteger.TWO};
            verify(rows,lo,hi,256L<<20,3200+t);
        }
        // Beyond-long constants: project by a small bounded offset and check all points.
        for(int t=0;t<32;t++){
            var base=O.shiftLeft(72+t);var lo=new BigInteger[]{base,base,Z,Z};var hi=new BigInteger[]{base.add(BigInteger.TWO),base.add(BigInteger.TWO),BigInteger.TWO,BigInteger.TWO};
            var rows=new ArrayList<ExactLinearProgram.Constraint>();
            rows.add(new ExactLinearProgram.Constraint(Map.of(0,O,1,O),base.shiftLeft(1).add(BigInteger.TWO)));
            rows.add(new ExactLinearProgram.Constraint(Map.of(0,O.negate(),1,O.negate()),base.shiftLeft(1).add(BigInteger.TWO).negate()));
            rows.add(new ExactLinearProgram.Constraint(Map.of(0,O.negate(),2,O),base.negate()));
            rows.add(new ExactLinearProgram.Constraint(Map.of(1,O.negate(),3,O),base.add(BigInteger.TWO).negate()));
            rows.add(new ExactLinearProgram.Constraint(Map.of(2,O,3,O),Z));
            verify(rows,lo,hi,256L<<20,3300+t);
        }
        var componentRows=List.of(row(-303,-1,-1,-1,0,0),row(303,1,1,1,0,0),
            row(-34571622,-12696,0,-12696,-89605,-31174),row(30724734,0,-12696,0,89605,31174));
        var componentLo=new BigInteger[]{Z,Z,Z,Z,Z};var componentHi=new BigInteger[]{BigInteger.valueOf(957),BigInteger.valueOf(957),BigInteger.valueOf(957),BigInteger.valueOf(302),BigInteger.valueOf(704)};
        for(int stop=1;stop<=4001;stop+=10){
            int[] checks={0};int at=stop;
            var b=new PlanningBudget(0,20_000_000,256L<<20,()->++checks[0]>=at,System::nanoTime);
            try(var component=new CountComponents(componentRows,componentLo,componentHi,b)){
                while(!component.step()){}
                if(component.counts()!=null&&!valid(componentRows,componentLo,componentHi,component.counts()))throw new AssertionError("bad component lift");
            }catch(java.util.concurrent.CancellationException expected){}
            if(b.reservedBytes()!=0)throw new AssertionError("component cancellation leak "+stop+" bytes="+b.reservedBytes());
        }
        for(long mem:new long[]{1,4096,65536,1048576}){
            var b=new PlanningBudget(0,20_000_000,mem,()->false,System::nanoTime);
            try(var component=new CountComponents(componentRows,componentLo,componentHi,b)){
                while(!component.step()){}
                if(component.counts()!=null&&!valid(componentRows,componentLo,componentHi,component.counts()))throw new AssertionError("bad memory-limit lift");
            }
            if(b.reservedBytes()!=0)throw new AssertionError("component memory leak");
        }
        System.out.println("PASS rc reduction models=3312 points="+checked+" legal="+legal+" fixed_nonzero_aliases="+fixedAliases+"; component 401 cancellation points + 4 memory limits");
    }
    static long[] indexed(int n,int a,long av,int b,long bv){var x=new long[n];x[a]=av;x[b]=bv;return x;}
}
