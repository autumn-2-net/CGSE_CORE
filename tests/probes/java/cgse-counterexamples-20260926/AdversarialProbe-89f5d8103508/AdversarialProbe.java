package org.cgse.core;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;

/** Independent test driver. All generated recipes are deterministic, ordinary inputs/outputs. */
public final class AdversarialProbe {
    static Path out;
    record Checked(Result result, GraphPlan<String> plan) {}
    static Checked run(Fixture f,long ms,boolean preserve) {
        long start=System.nanoTime();
        PlanningBudget b=new PlanningBudget(ms,20_000_000,256L<<20,()->false,System::nanoTime);
        GraphPlan<String> p=new GraphPlanner<>(new GraphCompiler<>(f.recipes())).plan(f.target(),f.amount(),f.stock(),preserve,true,b);
        double elapsed=(System.nanoTime()-start)/1e6;
        if(p.feasible()) {
            Summary s=summary(p.steps(),p.recipes());verify(f,s);
            for(var e:p.seeds().entrySet())if(BigInteger.valueOf(f.stock().getOrDefault(e.getKey(),0L)).add(s.delta().getOrDefault(e.getKey(),BigInteger.ZERO)).compareTo(BigInteger.valueOf(e.getValue()))<0)throw new AssertionError("unfunded reserve");
        }
        return new Checked(new Result(p.result().name(),elapsed,"checks="+b.nodes()+" missing="+p.missingExact()+" trace="+b.diagnostics()),p);
    }
    static Result scip(Fixture f,double seconds) {
        long start=System.nanoTime();var solver=MPSolver.createSolver("SCIP");
        solver.setNumThreads(1);solver.setTimeLimit((long)(seconds*1000));
        long upper=f.amount();for(long v:f.stock().values())upper=Math.max(upper,v);
        MPVariable[] x=new MPVariable[f.recipes().size()];
        for(int i=0;i<x.length;i++)x[i]=solver.makeIntVar(0,f.recipes().get(i).id().equals("finish")?1:upper,"x"+i);
        Set<String> keys=new TreeSet<>(f.stock().keySet());for(var r:f.recipes()){keys.addAll(r.inputs().keySet());keys.addAll(r.outputs().keySet());}
        for(String k:keys){var c=solver.makeConstraint((k.equals(f.target())?f.amount():0)-f.stock().getOrDefault(k,0L),Double.POSITIVE_INFINITY,k);for(int i=0;i<x.length;i++)c.setCoefficient(x[i],f.recipes().get(i).outputs().getOrDefault(k,0L)-f.recipes().get(i).inputs().getOrDefault(k,0L));}
        long built=System.nanoTime();var status=solver.solve();long end=System.nanoTime();
        String info="build_ms="+(built-start)/1e6+" solve_ms="+(end-built)/1e6;
        if(status==MPSolver.ResultStatus.OPTIMAL||status==MPSolver.ResultStatus.FEASIBLE){
            List<PlanStep> steps=new ArrayList<>();Map<String,GraphRecipe<String>> recipes=new LinkedHashMap<>();
            for(int i=0;i<x.length;i++){long n=Math.round(x[i].solutionValue());if(Math.abs(x[i].solutionValue()-n)>1e-5)throw new AssertionError("fractional SCIP witness");var r=f.recipes().get(i);recipes.put(r.id(),r);steps.add(new PlanStep.Batch(r.id(),n));}
            verify(f,summary(new PlanStep.Sequence(steps),recipes));
        }
        solver.delete();return new Result(status.name(),(end-start)/1e6,info);
    }
    static Fixture multi(int n,int dims,int scale,int seed,int shift) {
        Random rng=new Random(seed);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            String u="U"+i;stock.put(u,1L);boolean chosen=rng.nextBoolean();Map<String,Long> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
            for(int d=0;d<dims;d++){long w=1+rng.nextInt(scale);a.put("X"+d,w);b.put("Y"+d,w);need.merge((chosen?"X":"Y")+d,w,Long::sum);}
            rs.add(r("a"+i,Map.of(u,1L),a));rs.add(r("b"+i,Map.of(u,1L),b));
        }
        if(shift!=0){need.merge("X0",(long)shift,Long::sum);need.merge("Y0",-(long)shift,Long::sum);}
        need.values().removeIf(v->v==0);rs.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("multi_n"+n+"_d"+dims+"_w"+scale+"_s"+seed+"_shift"+shift,rs,stock,"GOAL",1,true);
    }
    static Fixture explicitPairs(Fixture base) {
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> need=new LinkedHashMap<>(base.recipes().get(base.recipes().size()-1).inputs());
        for(int i=0;i<base.recipes().size()-1;i++) {var r=base.recipes().get(i);Map<String,Long> outputs=new LinkedHashMap<>(r.outputs());outputs.put("PAIR"+(i/2),1L);need.put("PAIR"+(i/2),1L);rs.add(r(r.id(),r.inputs(),outputs));}
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Fixture(base.name()+"_explicit_pairs",rs,base.stock(),base.target(),base.amount(),true);
    }
    static Fixture randomSat(int n,int m,int seed) {
        Random rng=new Random(seed);List<int[]> clauses=new ArrayList<>();
        for(int j=0;j<m;j++){int[] c=new int[3];Set<Integer> used=new HashSet<>();for(int k=0;k<3;k++){int v;do{v=rng.nextInt(n);}while(!used.add(v));c[k]=rng.nextBoolean()?v+1:-v-1;}clauses.add(c);}
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++){stock.put("U"+i,1L);need.put("D"+i,1L);for(int v=0;v<2;v++){Map<String,Long> outputs=new LinkedHashMap<>();outputs.put("D"+i,1L);for(int j=0;j<m;j++)for(int lit:clauses.get(j))if(Math.abs(lit)==i+1&&(lit>0)==(v==1))outputs.put("C"+j,1L);rs.add(r("v"+i+"_"+v,Map.of("U"+i,1L),outputs));}}
        for(int j=0;j<m;j++)need.put("C"+j,1L);rs.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("randomsat_n"+n+"_m"+m+"_s"+seed,rs,stock,"GOAL",1,true);
    }
    static Fixture pigeon(int pigeons,int holes) {
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int h=0;h<holes;h++)stock.put("H"+h,1L);
        for(int p=0;p<pigeons;p++){stock.put("U"+p,1L);need.put("D"+p,1L);for(int h=0;h<holes;h++)rs.add(r("p"+p+"h"+h,Map.of("U"+p,1L,"H"+h,1L),Map.of("D"+p,1L)));}
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Fixture("pigeon_"+pigeons+"_"+holes,rs,stock,"GOAL",1,true);
    }
    static String json(Object o) {
        if(o instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";
        if(o instanceof Map<?,?> m){List<String>x=new ArrayList<>();m.forEach((k,v)->x.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",x)+"}";}
        if(o instanceof Collection<?> c)return "["+String.join(",",c.stream().map(AdversarialProbe::json).toList())+"]";
        return String.valueOf(o);
    }
    static void save(Fixture f,String detail) throws Exception {
        Map<String,Object> d=new LinkedHashMap<>();d.put("name",f.name());d.put("target",f.target());d.put("amount",f.amount());d.put("stock",new TreeMap<>(f.stock()));d.put("dag",f.dag());
        d.put("recipes",f.recipes().stream().map(r->Map.of("id",r.id(),"inputs",new TreeMap<>(r.inputs()),"outputs",new TreeMap<>(r.outputs()))).toList());d.put("detail",detail);
        Files.writeString(out.resolve(f.name()+".json"),json(d));
    }
    static boolean feasible(Result r){return r.status().equals("OPTIMAL")||r.status().equals("FEASIBLE")||r.status().equals("FEASIBLE_NOT_PROVEN_OPTIMAL");}
    static void screen(Fixture f) throws Exception {
        var cp=cpDag(f,3);var mip=scip(f,3);var g=run(f,3000,true).result();
        System.out.println("CASE\t"+f.name()+"\tCGSE\t"+g.status()+"\t"+g.ms());System.out.println("CASE\t"+f.name()+"\tCP_SAT\t"+cp.status()+"\t"+cp.ms());System.out.println("CASE\t"+f.name()+"\tSCIP\t"+mip.status()+"\t"+mip.ms());
        if(!feasible(g)||!feasible(cp)||g.ms()>100){String detail="CGSE "+g+"\nCP "+cp+"\nSCIP "+mip;System.out.println("DETAIL "+f.name()+" "+detail);save(f,detail);}
        if(feasible(g)&&cp.status().equals("INFEASIBLE"))throw new AssertionError("CP UNSAT vs verified CGSE witness");
    }
    static Fixture token(int seed) {
        Random rng=new Random(seed);int places=4+rng.nextInt(2),total=3+rng.nextInt(5),num=4+rng.nextInt(7);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>();
        for(int i=0;i<total;i++)stock.merge("P"+rng.nextInt(places-1),1L,Long::sum);
        for(int i=0;i<num;i++){int size=1+rng.nextInt(3);Map<String,Long> in=new LinkedHashMap<>(),outputs=new LinkedHashMap<>();for(int j=0;j<size;j++){in.merge("P"+rng.nextInt(places),1L,Long::sum);outputs.merge("P"+rng.nextInt(places),1L,Long::sum);}rs.add(r("r"+i,in,outputs));}
        return new Fixture("token_seed"+seed,rs,stock,"P"+(places-1),1+rng.nextInt(Math.min(total,4)),false);
    }
    record Oracle(boolean feasible,int states,List<String> witness) {}
    static Oracle bfs(Fixture f) {
        Set<String> keySet=new TreeSet<>(f.stock().keySet());for(var r:f.recipes()){keySet.addAll(r.inputs().keySet());keySet.addAll(r.outputs().keySet());}keySet.add(f.target());List<String> keys=new ArrayList<>(keySet);int target=keys.indexOf(f.target());
        List<Long> root=keys.stream().map(k->f.stock().getOrDefault(k,0L)).toList();Map<List<Long>,List<String>> visited=new LinkedHashMap<>();ArrayDeque<List<Long>> queue=new ArrayDeque<>();visited.put(root,List.of());queue.add(root);
        while(!queue.isEmpty()){
            List<Long> state=queue.remove();if(state.get(target)>=f.amount())return new Oracle(true,visited.size(),visited.get(state));
            for(var r:f.recipes()){
                List<Long> next=new ArrayList<>();boolean enabled=true;
                for(int k=0;k<keys.size();k++){String key=keys.get(k);long in=r.inputs().getOrDefault(key,0L);if(state.get(k)<in){enabled=false;break;}next.add(state.get(k)-in+r.outputs().getOrDefault(key,0L));}
                if(enabled&&!visited.containsKey(next)){List<String> path=new ArrayList<>(visited.get(state));path.add(r.id());visited.put(next,List.copyOf(path));queue.add(next);}
            }
            if(visited.size()>100000)throw new AssertionError("finite oracle unexpectedly too large");
        }
        return new Oracle(false,visited.size(),List.of());
    }
    static void fuzz(int from,int count) throws Exception {
        int yes=0,no=0,miss=0,wrong=0,unknown=0;
        Map<String,Integer> histogram=new TreeMap<>();List<String> records=new ArrayList<>();
        for(int seed=from;seed<from+count;seed++){
            var f=token(seed);var oracle=bfs(f);if(oracle.feasible())yes++;else no++;
            var g=run(f,100,false).result();
            if(feasible(g)!=oracle.feasible() || g.ms()>99){
                g=run(f,3000,false).result();
                if(oracle.feasible()&&!feasible(g)){miss++;if(g.status().equals("MISSING_INPUT"))wrong++;String info="BFS="+oracle+" CGSE="+g;save(f,info);System.out.println("MISS "+f.name()+" "+info);}
                else if(!oracle.feasible()&&feasible(g))throw new AssertionError("Invalid acceptance "+f.name());
            }
            if(!oracle.feasible()&&!g.status().equals("MISSING_INPUT"))unknown++;
            histogram.merge((oracle.feasible()?"SAT/":"UNSAT/")+g.status(),1,Integer::sum);
            records.add(json(Map.of("seed",seed,"oracle",oracle.feasible(),"states",oracle.states(),"witness",oracle.witness(),"status",g.status(),"ms",g.ms())));
            if((seed-from+1)%50==0)System.out.println("FUZZ progress="+(seed-from+1)+" feasible="+yes+" infeasible="+no+" missed="+miss+" missing_on_feasible="+wrong+" unknown_unsat="+unknown);
        }
        System.out.println("FUZZ_FINAL count="+count+" feasible="+yes+" infeasible="+no+" missed="+miss+" missing_on_feasible="+wrong+" unknown_unsat="+unknown);
        System.out.println("FUZZ_STATUSES "+histogram);Files.write(out.resolveSibling("fuzz-results.jsonl"),records);
    }
    public static void main(String[] args)throws Exception {
        Loader.loadNativeLibraries();Locale.setDefault(Locale.ROOT);out=Path.of(args[1]);Files.createDirectories(out);
        switch(args[0]) {
            case "screen" -> {
                for(int i=0;i<8;i++){var f=multi(8,1,100,42,0);run(f,3000,true);cpDag(f,3);scip(f,3);}
                for(int n:new int[]{20,28,36,48})screen(multi(n,2,1000,42,0));
                for(int n:new int[]{20,28,36})screen(multi(n,1,1000000,42,0));
                for(int n:new int[]{20,28,36})screen(multi(n,2,1000,42,1));
                for(int n:new int[]{32,64,96})screen(sat(n,n*4,42));
                for(int n:new int[]{24,40,64})for(int seed=0;seed<3;seed++)screen(randomSat(n,(int)(n*4.3),seed));
                for(int holes:new int[]{4,8,12})screen(pigeon(holes+1,holes));
            }
            case "fuzz" -> fuzz(Integer.parseInt(args[2]),Integer.parseInt(args[3]));
            case "focused" -> {
                List<Fixture> cases=List.of(multi(20,2,1000,42,0),multi(20,2,1000,42,1),randomSat(24,103,0),sat(96,384,42),explicitPairs(multi(20,2,1000,42,0)));
                for(var f:cases)for(int i=0;i<3;i++){System.out.println("REPEAT "+i);screen(f);}
            }
            case "shrink" -> {for(int n:new int[]{10,12,14,16,18})screen(multi(n,2,1000,42,0));}
            case "proof" -> {
                var f=randomSat(24,103,0);long start=System.nanoTime();var b=new PlanningBudget(3000,20000000,256L<<20,()->false,System::nanoTime);
                var compiler=new GraphCompiler<>(f.recipes());var model=RecipeCountModel.create(compiler,f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,b);
                var bounds=new CountBounds(model.recipes.size(),model.constraints,b);while(!bounds.step()){}
                var reduction=new CountReduction(model.constraints,bounds.lowerBounds(),bounds.upperBounds(),b);while(!reduction.step()){}
                var bool=new CountBoolean(reduction.rows(),reduction.lower(),reduction.upper(),b);while(!bool.step()){}
                System.out.println("BOOLEAN_ONLY ms="+(System.nanoTime()-start)/1e6+" counts="+Arrays.toString(bool.counts())+" diagnostics="+b.diagnostics());
                bool.close();reduction.close();bounds.close();model.close();
                start=System.nanoTime();b=new PlanningBudget(3000,20000000,256L<<20,()->false,System::nanoTime);
                var s=new IntegerCountSearch<>(compiler,f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,true,b,start);while(!s.step(null)){}
                System.out.println("INTEGER_ONLY ms="+(System.nanoTime()-start)/1e6+" infeasible="+s.infeasible()+" diagnostics="+b.diagnostics());s.close();
                var t=token(9);System.out.println("TOKEN9 "+bfs(t)+" "+run(t,3000,false).result());save(t,"BFS="+bfs(t)+" CGSE="+run(t,3000,false).result());
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
