package org.cgse.core;
import java.math.BigInteger;import java.nio.file.*;import java.util.*;import com.google.gson.*;
public class StructureDiagnostic{
 public static void main(String[]args)throws Exception{for(var raw:JsonParser.parseString(Files.readString(Path.of(".local/solver-stage-20261006/hard-unsat.json"))).getAsJsonArray()){
 var c=raw.getAsJsonObject();int n=c.getAsJsonArray("lower").size();var lo=new BigInteger[n];var hi=new BigInteger[n];for(int i=0;i<n;i++){lo[i]=c.getAsJsonArray("lower").get(i).getAsBigInteger();hi[i]=c.getAsJsonArray("upper").get(i).getAsBigInteger();}var rows=new ArrayList<ExactLinearProgram.Constraint>();for(var rr:c.getAsJsonArray("rows")){var r=rr.getAsJsonObject();var a=new LinkedHashMap<Integer,BigInteger>();for(var e:r.getAsJsonObject("terms").entrySet())a.put(Integer.valueOf(e.getKey()),e.getValue().getAsBigInteger());rows.add(new ExactLinearProgram.Constraint(a,r.get("upper").getAsBigInteger()));}
 var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);try(var s=new CountStructureSearch(rows,lo,hi,budget,10_000_000)){while(!s.step()){}System.out.println(c.get("id")+" "+s.infeasible()+" "+budget.nodes()+" "+budget.diagnostics());}if(budget.reservedBytes()!=0)throw new AssertionError("leak");
 }}
}
