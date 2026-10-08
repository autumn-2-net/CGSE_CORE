package org.cgse.core;

import java.util.*;
import java.nio.file.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.google.ortools.linearsolver.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Original recipe rows enter every solver; exact witness reconstruction follows outside its timer. */
public final class FusionProbe {
    static final class Json {
        final String s;int p=0;Json(String s){this.s=s;}
        void ws(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
        Object value(){ws();char c=s.charAt(p);
            if(c=='{'){p++;Map<String,Object> m=new LinkedHashMap<>();ws();if(s.charAt(p)=='}'){p++;return m;}while(true){String k=(String)value();ws();if(s.charAt(p++)!=':')throw new IllegalArgumentException();m.put(k,value());ws();if(s.charAt(p++)=='}')return m;}}
            if(c=='['){p++;List<Object> a=new ArrayList<>();ws();if(s.charAt(p)==']'){p++;return a;}while(true){a.add(value());ws();if(s.charAt(p++)==']')return a;}}
            if(c=='"'){p++;StringBuilder b=new StringBuilder();while(true){c=s.charAt(p++);if(c=='"')return b.toString();if(c=='\\'){c=s.charAt(p++);c=switch(c){case 'n'->'\n';case 'r'->'\r';case 't'->'\t';default->c;};}b.append(c);}}
            if(s.startsWith("true",p)){p+=4;return true;}if(s.startsWith("false",p)){p+=5;return false;}if(s.startsWith("null",p)){p+=4;return null;}
            int a=p;while(p<s.length()&&"0123456789-+.eE".indexOf(s.charAt(p))>=0)p++;String n=s.substring(a,p);return Long.valueOf(n);
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Object> list(Object x){return (List<Object>)x;}
    static Map<String,Long> amounts(Object x){Map<String,Long> a=new LinkedHashMap<>();map(x).forEach((k,v)->a.put(k,((Number)v).longValue()));return a;}
    static Fixture fixture(Map<String,Object> d){List<GraphRecipe<String>> rs=new ArrayList<>();for(Object x:list(d.get("recipes"))){var r=map(x);rs.add(r((String)r.get("id"),amounts(r.get("inputs")),amounts(r.get("outputs"))));}return new Fixture((String)d.get("name"),rs,amounts(d.get("stock")),(String)d.get("target"),((Number)d.get("amount")).longValue(),Boolean.TRUE.equals(d.get("dag")));}
    static void validate(Fixture f,Map<String,Object> d,Map<String,Long> counts){
        Map<String,GraphRecipe<String>> rs=new LinkedHashMap<>();for(var r:f.recipes())rs.put(r.id(),r);
        List<PlanStep> steps=new ArrayList<>();Map<String,Long> used=new HashMap<>();
        for(Object x:list(d.get("decode"))){var op=map(x);String type=(String)op.get("type");
            if(type.equals("counter")){
                int bits=((Number)op.get("bits")).intValue();String prefix=(String)op.get("prefix");PlanStep body=new PlanStep.Sequence(List.of());
                for(int i=0;i<bits;i++){String id=prefix+"inc"+i;long n=1L<<(bits-i-1);if(counts.getOrDefault(id,0L)!=n)throw new AssertionError("Unexpected counter counts");used.put(id,n);body=new PlanStep.Sequence(List.of(body,new PlanStep.Batch(id,1),body));}steps.add(body);
            }else{
                long n=counts.getOrDefault((String)op.get("count_from"),0L);
                if(type.equals("batch")){String id=(String)op.get("id");steps.add(new PlanStep.Batch(id,n));used.merge(id,n,Long::sum);}
                else{List<PlanStep> children=new ArrayList<>();for(Object id:list(op.get("ids"))){children.add(new PlanStep.Batch((String)id,1));used.merge((String)id,n,Long::sum);}steps.add(new PlanStep.Repeat(new PlanStep.Sequence(children),n));}
            }
        }
        for(var r:f.recipes())if(!used.getOrDefault(r.id(),0L).equals(counts.getOrDefault(r.id(),0L)))throw new AssertionError("Unreplayed native count "+r.id());
        verify(f,summary(new PlanStep.Sequence(steps),rs));
    }
    record Solved(Result result,Map<String,Long> counts){}
    static Set<String> keys(Fixture f){Set<String> k=new TreeSet<>(f.stock().keySet());k.add(f.target());for(var r:f.recipes()){k.addAll(r.inputs().keySet());k.addAll(r.outputs().keySet());}return k;}
    static Solved cp(Fixture f,Map<String,Object> d,long ms){
        long start=System.nanoTime();CpModel m=new CpModel();IntVar[] x=new IntVar[f.recipes().size()];long upper=((Number)d.get("upper")).longValue();
        for(int i=0;i<x.length;i++)x[i]=m.newIntVar(0,f.recipes().get(i).id().endsWith("finish")?1:upper,"x"+i);
        for(String k:keys(f)){LinearExprBuilder row=LinearExpr.newBuilder();for(int i=0;i<x.length;i++){var r=f.recipes().get(i);row.addTerm(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}m.addGreaterOrEqual(row,(k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L));}
        long built=System.nanoTime();CpSolver s=new CpSolver();s.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(ms/1000.0);var status=s.solve(m);long end=System.nanoTime();Map<String,Long> counts=new LinkedHashMap<>();
        if(status==CpSolverStatus.OPTIMAL||status==CpSolverStatus.FEASIBLE){for(int i=0;i<x.length;i++)counts.put(f.recipes().get(i).id(),s.value(x[i]));validate(f,d,counts);}
        return new Solved(new Result(status.name(),(end-start)/1e6,"build_ms="+(built-start)/1e6+" solve_ms="+(end-built)/1e6+" branches="+s.numBranches()+" validation="+m.validate()),counts);
    }
    static Solved mip(Fixture f,Map<String,Object> d,long ms){
        long start=System.nanoTime();MPSolver s=MPSolver.createSolver("SCIP");s.setNumThreads(1);s.setTimeLimit(ms);MPVariable[] x=new MPVariable[f.recipes().size()];long upper=((Number)d.get("upper")).longValue();
        for(int i=0;i<x.length;i++)x[i]=s.makeIntVar(0,f.recipes().get(i).id().endsWith("finish")?1:upper,"x"+i);
        for(String k:keys(f)){var row=s.makeConstraint((k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L),Double.POSITIVE_INFINITY);for(int i=0;i<x.length;i++){var r=f.recipes().get(i);row.setCoefficient(x[i],r.outputs().getOrDefault(k,0L)-r.inputs().getOrDefault(k,0L));}}
        long built=System.nanoTime();var status=s.solve();long end=System.nanoTime();Map<String,Long> counts=new LinkedHashMap<>();
        if(status==MPSolver.ResultStatus.OPTIMAL||status==MPSolver.ResultStatus.FEASIBLE){for(int i=0;i<x.length;i++){double v=x[i].solutionValue();long n=Math.round(v);if(Math.abs(v-n)>1e-5)throw new AssertionError("Fractional SCIP count");counts.put(f.recipes().get(i).id(),n);}validate(f,d,counts);}
        s.delete();return new Solved(new Result(status.name(),(end-start)/1e6,"build_ms="+(built-start)/1e6+" solve_ms="+(end-built)/1e6),counts);
    }
    static Map<String,Object> result(Result r){return Map.of("status",r.status(),"ms",r.ms(),"info",r.info());}
    public static void main(String[] args)throws Exception{
        Loader.loadNativeLibraries();Locale.setDefault(Locale.ROOT);Path input=Path.of(args[0]),output=Path.of(args[1]);long budget=Long.parseLong(args[2]);int repeats=args.length>4?Integer.parseInt(args[4]):1;
        Set<String> selected=args.length>3&&!args[3].equals("all")?new HashSet<>(Files.readAllLines(Path.of(args[3]))):null;Files.createDirectories(output);
        for(int i=0;i<5;i++)run(ContrastProbe.dag(20),1000,false);
        for(Path p:Files.list(input).sorted().toList())if(p.toString().endsWith(".json")){
            var d=map(new Json(Files.readString(p)).value());Fixture f=fixture(d);if(selected!=null&&!selected.contains(f.name()))continue;
            if(d.get("truth").equals("SAT"))validate(f,d,amounts(d.get("certificate_counts")));
            for(int rep=0;rep<repeats;rep++){
                Map<String,Object> row=new LinkedHashMap<>();row.put("case",f.name());row.put("family",d.get("mode"));row.put("truth",d.get("truth"));row.put("recipes",f.recipes().size());row.put("budget_ms",budget);row.put("rep",rep);
                var c=cp(f,d,budget);row.put("cp_sat",result(c.result()));row.put("cp_counts",c.counts());var m=mip(f,d,budget);row.put("scip",result(m.result()));row.put("scip_counts",m.counts());
                try{var g=run(f,budget,false).result();row.put("cgse",result(g));if(feasible(g)&&d.get("truth").equals("UNSAT"))throw new AssertionError("False CGSE acceptance");}
                catch(Throwable failure){row.put("cgse",result(new Result("ERROR",0,failure.toString())));failure.printStackTrace();}
                Files.writeString(output.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("CASE "+f.name()+" "+json(row.get("cgse"))+" CP="+c.result().status()+"/"+c.result().ms()+" SCIP="+m.result().status()+"/"+m.result().ms());System.out.flush();
            }
        }
    }
}
