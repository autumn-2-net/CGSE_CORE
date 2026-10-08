package org.cgse.core;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

public class Gt2V15Budget {
    static final Gson JSON=new Gson();
    public static void main(String[] args)throws Exception {
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        String[] modes=args.length>2?args[2].split(","):new String[]{"lcg","domain","quick"};
        long limit=args.length>3?Long.parseLong(args[3]):20_000_000;
        String filter=args.length>4?args[4]:"";
        int variations=args.length>5?Integer.parseInt(args[5]):1;
        try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            for(JsonElement element:cases){JsonObject c=element.getAsJsonObject();String id=c.get("id").getAsString();if(!id.contains(filter))continue;
                for(int variation=15;variation<16;variation++)for(String mode:modes){
                    int n=c.getAsJsonArray("lower").size();BigInteger[]lo=new BigInteger[n],hi=new BigInteger[n];
                    List<Integer> order=new ArrayList<>();for(int i=0;i<n;i++)order.add(i);
                    if(variation>0)Collections.shuffle(order,new Random(7001+variation));
                    int[]remap=new int[n];for(int i=0;i<n;i++)remap[order.get(i)]=i;
                    for(int i=0;i<n;i++){lo[remap[i]]=c.getAsJsonArray("lower").get(i).getAsBigInteger();JsonElement h=c.getAsJsonArray("upper").get(i);hi[remap[i]]=h.isJsonNull()?null:h.getAsBigInteger();}
                    List<ExactLinearProgram.Constraint> rows=new ArrayList<>();
                    BigInteger scale=variation==3&&!mode.startsWith("main")?BigInteger.TEN.pow(35):BigInteger.ONE;
                    for(JsonElement e:c.getAsJsonArray("rows")){JsonObject r=e.getAsJsonObject();Map<Integer,BigInteger>t=new LinkedHashMap<>();for(var entry:r.getAsJsonObject("terms").entrySet())t.put(remap[Integer.parseInt(entry.getKey())],entry.getValue().getAsBigInteger().multiply(scale));rows.add(new ExactLinearProgram.Constraint(t,r.get("upper").getAsBigInteger().multiply(scale)));}
                    if(variation>0)Collections.shuffle(rows,new Random(8009+variation));
                    PlanningBudget budget=new PlanningBudget(Long.parseLong(System.getenv().getOrDefault("CGSE_BENCH_WALL_MS","10000")),limit,256L<<20,()->false,System::nanoTime);budget.enableMetrics();long started=System.nanoTime();String status="UNKNOWN",error="";BigInteger[]counts=null;boolean infeasible=false;
                    try {
                        switch(mode){
                            case "pb-retained", "pb-lrb" -> {try(CountCdcl search=new CountCdcl(rows,lo,hi,budget,262144,mode.equals("pb-lrb")?CountCdcl.Branching.LEARNING_RATE:CountCdcl.Branching.ACTIVITY).retained()){int rounds=0;do{while(!search.step()){}counts=search.counts();infeasible=search.infeasible();if(counts!=null||infeasible||!search.paused()||budget.remainingWork()<1024)break;search.resume(Math.min(262144,budget.remainingWork()));}while(++rounds<10000);}}
                            case "lcg" -> {try(CountLcg search=new CountLcg(rows,lo,hi,budget,limit)){while(!search.step()){}counts=search.counts();infeasible=search.infeasible();}}
                            case "lcg-retained" -> {try(CountLcg search=new CountLcg(rows,lo,hi,budget,limit)){int rounds=0;do{while(!search.step()){}counts=search.counts();infeasible=search.infeasible();if(counts!=null||infeasible||!search.paused()||budget.remainingWork()<1024)break;search.resume(Math.min(250000,budget.remainingWork()));}while(++rounds<10000);}}
                            case "view-retained" -> {try(CountModelViews models=CountModelViews.create(rows,lo,hi,budget)){if(models!=null){models.compileLight();try(CountViewSearch search=new CountViewSearch(models,budget)){int rounds=0;long before;do{before=budget.nodes();search.resume(Math.min(250000,budget.remainingWork()));while(!search.step()){}counts=search.counts();infeasible=search.infeasible();}while(counts==null&&!infeasible&&budget.remainingWork()>=8192&&budget.nodes()>before&&++rounds<10000);}}}}
                            case "domain" -> {try(CountDomainSearch search=new CountDomainSearch(rows,lo,hi,budget,limit)){while(!search.step()){}counts=search.counts();infeasible=search.infeasible();}}
                            case "quick" -> {try(CountQuickSolve search=new CountQuickSolve(rows,lo,hi,budget)){while(!search.step()){}counts=search.counts();infeasible=search.infeasible();}}
                            case "main", "main-retained" -> {Embedding e=new Embedding(rows,lo,hi);try(IntegerCountSearch<String> search=new IntegerCountSearch<>(new GraphCompiler<>(e.recipes),"target",1,e.stock,Map.of(),Set.of(),Set.of(),false,true,budget,started)){int rounds=0;do{while(!search.step()){}if(!mode.endsWith("retained")||search.result()!=null||search.infeasible()||!search.paused()||budget.remainingWork()<8192)break;search.resume();}while(++rounds<10000);GraphPlan<String> p=search.result();infeasible=search.infeasible();if(p!=null&&(p.result()==GraphPlan.Result.FEASIBLE||p.result()==GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL)){PlanVerifier.verifyRuntimeInventory(p);counts=new BigInteger[n];for(int i=0;i<n;i++)counts[i]=p.patternTimesExact().getOrDefault("var"+i,BigInteger.ZERO).add(lo[i]);}}}
                            default -> throw new IllegalArgumentException(mode);
                        }
                        if(counts!=null){verify(rows,lo,hi,counts);status="SAT";}else if(infeasible)status="UNSAT";
                    }catch(PlanningBudget.Exhausted exhausted){status="LIMIT";error=exhausted.toString();}
                    catch(Throwable failure){status="ERROR";error=failure.toString();failure.printStackTrace(System.err);}
                    Map<String,Object>result=new LinkedHashMap<>();result.put("id",id);result.put("mode",mode);result.put("variation",variation);result.put("variables",n);result.put("rows",rows.size());result.put("status",status);result.put("expected",c.has("expected")?c.get("expected").getAsString():"UNKNOWN");result.put("milliseconds",(System.nanoTime()-started)/1e6);result.put("work",budget.nodes());result.put("peakBytes",budget.peakBytes());result.put("reservedAfterClose",budget.reservedBytes());result.put("diagnostics",budget.diagnostics());result.put("strategyMetrics",budget.metrics().strategies());result.put("error",error);
                    if(counts!=null){List<String>witness=new ArrayList<>();for(int i=0;i<n;i++)witness.add(counts[remap[i]].toString());result.put("witness",witness);}
                    out.println(JSON.toJson(result));out.flush();System.out.println(id+" "+mode+" v"+variation+" "+status+" work="+budget.nodes()+" ms="+result.get("milliseconds"));
                    if(budget.reservedBytes()!=0)throw new AssertionError("memory leak "+result);
                    String expected=(String)result.get("expected");if((status.equals("SAT")&&expected.equals("UNSAT"))||(status.equals("UNSAT")&&expected.equals("SAT")))throw new AssertionError("WRONG ANSWER "+result);
                }
            }
        }
    }
    static void verify(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi,BigInteger[]x){for(int i=0;i<x.length;i++)if(x[i].compareTo(lo[i])<0||hi[i]!=null&&x[i].compareTo(hi[i])>0)throw new AssertionError("bounds "+i);for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));if(sum.compareTo(row.upper())>0)throw new AssertionError("row "+row);}}
    static class Embedding {
        final List<GraphRecipe<String>>recipes=new ArrayList<>();final Map<String,Long>stock=new LinkedHashMap<>();
        Embedding(List<ExactLinearProgram.Constraint>rows,BigInteger[]lo,BigInteger[]hi){
            int n=lo.length;List<List<GraphRecipe.Slot<String>>>inputs=new ArrayList<>();List<Map<String,Long>>outputs=new ArrayList<>();
            for(int i=0;i<n;i++){if(hi[i]==null)throw new IllegalArgumentException("main adapter requires finite upper");inputs.add(new ArrayList<>());outputs.add(new LinkedHashMap<>());long cap=hi[i].subtract(lo[i]).longValueExact();if(cap>0)stock.put("quota"+i,cap);inputs.get(i).add(new GraphRecipe.Slot<>("quota"+i,1));outputs.get(i).put("marker"+i,1L);}
            List<GraphRecipe.Slot<String>>finish=new ArrayList<>();
            for(int k=0;k<rows.size();k++){var r=rows.get(k);BigInteger bound=r.upper(),buffer=BigInteger.ZERO;
                for(var t:r.terms().entrySet()){int i=t.getKey();BigInteger v=t.getValue();bound=bound.subtract(v.multiply(lo[i]));if(v.signum()>0)buffer=buffer.add(v.multiply(hi[i].subtract(lo[i])));}
                buffer=buffer.max(bound).max(BigInteger.ZERO);long initial=buffer.longValueExact();if(initial>0)stock.put("row"+k,initial);long need=buffer.subtract(bound).longValueExact();if(need>0)finish.add(new GraphRecipe.Slot<>("row"+k,need));
                for(var t:r.terms().entrySet()){int i=t.getKey();long q=t.getValue().abs().longValueExact();if(t.getValue().signum()>0)inputs.get(i).add(new GraphRecipe.Slot<>("row"+k,q));else if(t.getValue().signum()<0)outputs.get(i).put("row"+k,q);}
            }
            for(int i=0;i<n;i++)recipes.add(new GraphRecipe<>("var"+i,"var"+i,inputs.get(i),outputs.get(i)));recipes.add(new GraphRecipe<>("finish","finish",finish,Map.of("target",1L)));
        }
    }
}
