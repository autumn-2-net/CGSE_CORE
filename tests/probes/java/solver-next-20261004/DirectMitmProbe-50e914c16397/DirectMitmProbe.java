package org.cgse.core;
import com.google.gson.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
public final class DirectMitmProbe {
    public static void main(String[]args)throws Exception{
        var cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        for(var entry:cases){var c=entry.getAsJsonObject();String id=c.get("id").getAsString();if(args.length>1?!id.contains(args[1]):!id.equals("generated/subset-n24-bits24")&&!id.equals("generated/subset-n24-bits48"))continue;
            int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}
            var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var e:c.getAsJsonArray("rows")){var rr=e.getAsJsonObject();var terms=new LinkedHashMap<Integer,BigInteger>();for(var v:rr.getAsJsonObject("terms").entrySet())terms.put(Integer.parseInt(v.getKey()),v.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(terms,rr.get("upper").getAsBigInteger()));}
            var budget=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);long started=System.nanoTime();boolean sat;
            try(var mitm=new CountMeetInMiddle(rows,lo,hi,budget)){while(!mitm.step()){}sat=mitm.counts()!=null;if(sat)CountBenchmark.verify(rows,lo,hi,mitm.counts());}
            if(budget.reservedBytes()!=0)throw new AssertionError("leak");System.out.println(id+" direct-mitm SAT="+sat+" work="+budget.nodes()+" ms="+(System.nanoTime()-started)/1e6+" "+budget.diagnostics());
        }
    }
}
