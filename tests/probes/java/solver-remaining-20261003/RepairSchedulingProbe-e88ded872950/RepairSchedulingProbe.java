package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class RepairSchedulingProbe {
    static int assertions;
    static BigInteger b(long v) { return BigInteger.valueOf(v); }
    static void check(boolean v,String msg) { assertions++; if(!v) throw new AssertionError(msg); }
    record Model(List<ExactLinearProgram.Constraint> rows,BigInteger[] lo,BigInteger[] hi,ExactRational[] point) {}
    static Model coloring(int colors,int order) {
        int vertices=colors+1, n=vertices*colors+1, x=n-1;
        var rows=new ArrayList<ExactLinearProgram.Constraint>();
        BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];ExactRational[]p=new ExactRational[n];
        Arrays.fill(lo,b(0));Arrays.fill(hi,b(1));Arrays.fill(p,new ExactRational(b(1),b(colors)));hi[x]=b(2);p[x]=ExactRational.ZERO;
        for(int v=0;v<vertices;v++) {
            Map<Integer,BigInteger>up=new LinkedHashMap<>(),down=new LinkedHashMap<>();
            for(int c=0;c<colors;c++){up.put(v*colors+c,b(1));down.put(v*colors+c,b(-1));}
            rows.add(new ExactLinearProgram.Constraint(up,b(1)));rows.add(new ExactLinearProgram.Constraint(down,b(-1)));
        }
        for(int v=0;v<vertices;v++)for(int w=v+1;w<vertices;w++)for(int c=0;c<colors;c++)
            rows.add(new ExactLinearProgram.Constraint(Map.of(v*colors+c,b(2),w*colors+c,b(2),x,b(-1)),b(2)));
        if(order>0)Collections.shuffle(rows,new Random(918273L+order));
        return new Model(rows,lo,hi,p);
    }
    static boolean valid(Model m,BigInteger[]v){
        for(int i=0;i<v.length;i++)if(v[i].compareTo(m.lo[i])<0||m.hi[i]!=null&&v[i].compareTo(m.hi[i])>0)return false;
        for(var row:m.rows){BigInteger sum=b(0);for(var e:row.terms().entrySet())sum=sum.add(e.getValue().multiply(v[e.getKey()]));if(sum.compareTo(row.upper())>0)return false;}return true;
    }
    static void run(Model m,String name,long bytes,int cancelAt){
        var polls=new AtomicInteger();var budget=new PlanningBudget(0,4_000_000,bytes,()->cancelAt>0&&polls.incrementAndGet()>cancelAt,System::nanoTime);
        BigInteger[]result=null;boolean cancelled=false;
        try(var n=new CountNeighborhood(m.rows,m.lo,m.hi,m.point,budget).pump(false)){
            while(!n.step()){}result=n.counts();if(result!=null)check(valid(m,result),"invalid witness "+name);
        }catch(CancellationException expected){cancelled=true;}catch(PlanningBudget.Exhausted expected){}
        check(budget.reservedBytes()==0,"leak "+name+" "+budget.reservedBytes());
        System.out.println(name+" found="+(result!=null)+" work="+budget.nodes()+" cancelled="+cancelled);
    }
    public static void main(String[]args){
        for(int colors=3;colors<=8;colors++)for(int order=0;order<3;order++)run(coloring(colors,order),"color-"+colors+"-"+order,64L<<20,0);
        for(int stop=1;stop<=160;stop++)run(coloring(4,0),"cancel-"+stop,64L<<20,stop);
        for(int bytes:new int[]{1,1024,8192,32768,65536})run(coloring(4,0),"memory-"+bytes,bytes,0);
        System.out.println("PASS assertions="+assertions);
    }
}
