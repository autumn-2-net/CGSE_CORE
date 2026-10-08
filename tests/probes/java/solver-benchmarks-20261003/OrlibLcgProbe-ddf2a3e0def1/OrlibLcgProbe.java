package org.cgse.core;
import java.math.BigInteger;import java.util.*;import java.nio.file.*;
public final class OrlibLcgProbe {
    public static void main(String[]args)throws Exception{
        try(var in=new Scanner(Path.of(args[0]))){int cases=in.nextInt();for(int c=0;c<cases;c++){
            String name=in.next();int n=in.nextInt(),m=in.nextInt();boolean feasible=in.nextBoolean();var rows=new ArrayList<ExactLinearProgram.Constraint>();
            for(int i=0;i<m;i++){var rhs=in.nextBigInteger();var terms=new LinkedHashMap<Integer,BigInteger>();for(int j=0;j<n;j++){var a=in.nextBigInteger();if(a.signum()!=0)terms.put(j,a);}rows.add(new ExactLinearProgram.Constraint(terms,rhs));}
            var lo=new BigInteger[n];var hi=new BigInteger[n];Arrays.fill(lo,BigInteger.ZERO);Arrays.fill(hi,BigInteger.ONE);
            var b=new PlanningBudget(0,8_000_000,128L<<20,()->false,System::nanoTime);String result;long start=System.nanoTime();
            try(var solver=new CountLcg(rows,lo,hi,b,1_000_000)){
                while(!solver.step()){}var counts=solver.counts();result=counts!=null?"SAT":solver.infeasible()?"UNSAT":"UNKNOWN";
                if(counts!=null&&(!feasible||!LazyLcgProbe.holds(rows,counts)))throw new AssertionError("bad witness "+name);
                if(solver.infeasible()&&feasible)throw new AssertionError("false UNSAT "+name);
                System.out.println(name+"\t"+n+"\t"+result+"\t"+b.nodes()+"\t"+b.peakBytes()+"\t"+((System.nanoTime()-start)/1_000_000)+"\t"+b.diagnostics());
            }if(b.reservedBytes()!=0)throw new AssertionError("leak");
        }}
    }
}
