package org.cgse.core;

import com.google.ortools.Loader;
import java.util.*;
import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Deterministic probes for f4cbf89. This file does not change production code. */
public final class NextProbe {
    static Fixture bounded(int n,int cap,int dims,int scale,int seed,int shift) {
        Random rng=new Random(seed);List<GraphRecipe<String>> recipes=new ArrayList<>();
        Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++) {
            String u="U"+i;stock.put(u,(long)cap);int take=rng.nextInt(cap+1);
            if(i==0)take=1; // Guarantee a planted mixed allocation when cap > 1.
            Map<String,Long> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
            a.put("D"+i,1L);b.put("D"+i,1L);need.put("D"+i,(long)cap);
            for(int d=0;d<dims;d++) {
                long w=1+rng.nextInt(scale);a.put("X"+d,w);b.put("Y"+d,w);
                need.merge("X"+d,w*take,Long::sum);need.merge("Y"+d,w*(cap-take),Long::sum);
            }
            recipes.add(r("a"+i,Map.of(u,1L),a));recipes.add(r("b"+i,Map.of(u,1L),b));
        }
        need.merge("X0",(long)shift,Long::sum);need.merge("Y0",-(long)shift,Long::sum);
        need.values().removeIf(x->x==0);
        recipes.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("bounded_n"+n+"_cap"+cap+"_d"+dims+"_w"+scale+"_s"+seed+"_shift"+shift,recipes,stock,"GOAL",1,true);
    }
    static Fixture fuel(int seed) {
        Random rng=new Random(seed);int places=4,total=2+rng.nextInt(5),num=4+rng.nextInt(5);
        List<GraphRecipe<String>> recipes=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>();
        stock.put("F",(long)(4+rng.nextInt(3)));
        for(int i=0;i<total;i++)stock.merge("P"+rng.nextInt(places-1),1L,Long::sum);
        for(int i=0;i<num;i++) {
            int take=1+rng.nextInt(3),make=1+rng.nextInt(3);
            Map<String,Long> in=new LinkedHashMap<>(),output=new LinkedHashMap<>();in.put("F",1L);
            for(int j=0;j<take;j++)in.merge("P"+rng.nextInt(places),1L,Long::sum);
            for(int j=0;j<make;j++)output.merge("P"+rng.nextInt(places),1L,Long::sum);
            recipes.add(r("r"+i,in,output));
        }
        return new Fixture("fuel_seed"+seed,recipes,stock,"P3",2+rng.nextInt(4),false);
    }
    static void fuzzNew(int start,int count,boolean old) throws Exception {
        Map<String,Integer> hist=new TreeMap<>();List<String> records=new ArrayList<>();
        int missed=0,falseMissing=0,maxStates=0;
        for(int seed=start;seed<start+count;seed++) {
            Fixture f=old?token(seed):fuel(seed);Oracle oracle=bfs(f);maxStates=Math.max(maxStates,oracle.states());
            var checked=run(f,100,false);var g=checked.result();
            if(g.status().equals("TIMEOUT")||g.status().equals("SEARCH_LIMIT")||oracle.feasible()&&!feasible(g)) {
                checked=run(f,3000,false);g=checked.result();
            }
            if(feasible(g)&&!oracle.feasible())throw new AssertionError("False accept "+f.name());
            boolean miss=oracle.feasible()&&!feasible(g);
            if(miss) {
                missed++;
                if(g.status().equals("MISSING_INPUT")||g.status().equals("MISSING_SEED")||g.status().equals("INFEASIBLE"))falseMissing++;
                String info="BFS="+oracle+" CGSE="+g;save(f,info);System.out.println("MISS "+f.name()+" "+info);
            }
            hist.merge((oracle.feasible()?"SAT/":"UNSAT/")+g.status(),1,Integer::sum);
            Map<String,Object> record=new LinkedHashMap<>();record.put("seed",seed);record.put("oracle",oracle.feasible());
            record.put("states",oracle.states());record.put("witness",oracle.witness());record.put("status",g.status());record.put("ms",g.ms());
            record.put("missing",checked.plan().missingExact().toString());records.add(json(record));
            if((seed-start+1)%50==0)System.out.println("PROGRESS "+(seed-start+1)+" missed="+missed+" falseMissing="+falseMissing+" "+hist);
        }
        System.out.println("FINAL count="+count+" missed="+missed+" falseMissing="+falseMissing+" maxStates="+maxStates+" "+hist);
        Files.write(out.resolveSibling(old?"token-results.jsonl":"fuel-results.jsonl"),records);
    }
    public static void main(String[] args)throws Exception {
        Loader.loadNativeLibraries();Locale.setDefault(Locale.ROOT);out=Path.of(args[1]);Files.createDirectories(out);
        switch(args[0]) {
            case "new-focused" -> {
                for(int i=0;i<10;i++){var f=bounded(4,2,2,30,42,0);run(f,3000,true);cpDag(f,3);scip(f,3);}
                for(var f:List.of(bounded(12,2,2,1000,42,0),bounded(8,3,2,1000,42,1)))
                    for(int i=0;i<3;i++){System.out.println("REPEAT "+i);screen(f);}
            }
            case "bounded" -> {
                for(int i=0;i<8;i++){var f=bounded(4,2,2,30,42,0);run(f,3000,true);cpDag(f,3);scip(f,3);}
                for(int cap:new int[]{2,3})for(int n:new int[]{6,8,12,16})screen(bounded(n,cap,2,1000,42,0));
                for(int n:new int[]{8,12})screen(bounded(n,3,2,1000,42,1));
            }
            case "bounded-repeat" -> {
                int n=Integer.parseInt(args[2]),cap=Integer.parseInt(args[3]),shift=Integer.parseInt(args[4]);
                var f=bounded(n,cap,2,1000,42,shift);save(f,"Exact generated fixture; see raw output.");
                for(int i=0;i<3;i++){System.out.println("REPEAT "+i);screen(f);}
            }
            case "new-proof" -> {
                for(var f:List.of(bounded(12,2,2,1000,42,0),bounded(8,3,2,1000,42,1))) {
                    var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
                    var compiler=new GraphCompiler<>(f.recipes());
                    var model=RecipeCountModel.create(compiler,f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,budget);
                    var bounds=new CountBounds(model.recipes.size(),model.constraints,budget);while(!bounds.step()){}
                    var reduction=new CountReduction(model.constraints,bounds.lowerBounds(),bounds.upperBounds(),budget);while(!reduction.step()){}
                    System.out.println("REDUCED "+f.name()+" variables="+reduction.lower().length+" lower="+Arrays.toString(reduction.lower())+" upper="+Arrays.toString(reduction.upper()));
                    var matching=new CountMeetInMiddle(reduction.rows(),reduction.lower(),reduction.upper(),budget);while(!matching.step()){}
                    System.out.println("MATCHING counts="+Arrays.toString(matching.counts())+" infeasible="+matching.infeasible()+" trace="+budget.diagnostics());
                    matching.close();reduction.close();bounds.close();model.close();
                    long start=System.nanoTime();budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
                    var search=new IntegerCountSearch<>(compiler,f.target(),f.amount(),f.stock(),Map.of(),Set.of(),Set.of(),true,true,budget,start);
                    while(!search.step(null)){}
                    System.out.println("INTEGER_ONLY "+f.name()+" ms="+(System.nanoTime()-start)/1e6+" infeasible="+search.infeasible()+" diagnostics="+budget.diagnostics());search.close();
                }
            }
            case "fuel" -> fuzzNew(Integer.parseInt(args[2]),Integer.parseInt(args[3]),false);
            case "token" -> fuzzNew(Integer.parseInt(args[2]),Integer.parseInt(args[3]),true);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
