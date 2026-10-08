package org.cgse.core;
import com.google.gson.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
public class AffineDiagnose {
    static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    public static void main(String[] args)throws Exception{
        var cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        for(var ce:cases){var c=ce.getAsJsonObject();int n=c.getAsJsonArray("lower").size();BigInteger[] lo=new BigInteger[n],hi=new BigInteger[n];
            for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var re:c.getAsJsonArray("rows")){var r=re.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var e:r.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(e.getKey()),e.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,r.get("upper").getAsBigInteger()));}
            var budget=new PlanningBudget(60000,20000000,256L<<20,()->false,System::nanoTime);long start=System.nanoTime();
            try(var lattice=new CountAffineLattice(rows,lo,hi,budget)){
                if(args.length>1){Field allowance=CountAffineLattice.class.getDeclaredField("allowance");allowance.setAccessible(true);allowance.setLong(lattice,Long.parseLong(args[1]));}
                int previous=-1;
                while(!lattice.step()){
                    int phase=(int)field(lattice,"phase");if(phase!=previous){System.out.println(c.get("id")+" phase="+phase+" work="+budget.nodes()+" dim="+((List<?>)field(lattice,"basis")).size());previous=phase;}
                }
                System.out.println(c.get("id")+" found="+(lattice.counts()!=null)+" work="+budget.nodes()+" phase="+field(lattice,"phase")+" eq="+field(lattice,"equation")+" attempts="+field(lattice,"attempt")+" pivot="+field(lattice,"pivot")+" ms="+(System.nanoTime()-start)/1e6+" diag="+budget.diagnostics());
                var point=(BigInteger[])field(lattice,"point");if(point!=null){int bits=0;for(var x:point)bits=Math.max(bits,x.bitLength());System.out.println("maxPointBits="+bits);}
            }catch(Exception e){System.out.println(e);}
            if(budget.reservedBytes()!=0)throw new AssertionError("leak");
        }
    }
}
