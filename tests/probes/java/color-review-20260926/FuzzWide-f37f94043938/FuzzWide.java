package org.cgse.core;

import java.util.*;
import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Larger finite random networks; direct BFS is independent of the production planner. */
public final class FuzzWide {
    static Fixture generate(int seed){
        Random rng=new Random(seed);int places=5+rng.nextInt(3),total=5+rng.nextInt(8),num=10+rng.nextInt(9);
        Map<String,Long> stock=new LinkedHashMap<>();stock.put("F",(long)(6+rng.nextInt(6)));
        for(int i=0;i<total;i++)stock.merge("P"+rng.nextInt(places-1),1L,Long::sum);
        List<GraphRecipe<String>> recipes=new ArrayList<>();
        for(int i=0;i<num;i++){
            Map<String,Long> in=new LinkedHashMap<>(),output=new LinkedHashMap<>();in.put("F",1L);
            int take=1+rng.nextInt(4),make=1+rng.nextInt(4);
            for(int j=0;j<take;j++)in.merge("P"+rng.nextInt(places),1L,Long::sum);
            for(int j=0;j<make;j++)output.merge("P"+rng.nextInt(places),1L,Long::sum);
            recipes.add(r("r"+i,in,output));
        }
        return new Fixture("wide_seed"+seed,recipes,stock,"P"+(places-1),2+rng.nextInt(6),false);
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);out=root.resolve("cases");Files.createDirectories(out);
        int start=Integer.parseInt(args[1]),count=Integer.parseInt(args[2]);Map<String,Integer> histogram=new TreeMap<>();int missed=0,wrong=0,oracleLimits=0,maxStates=0;
        for(int seed=start;seed<start+count;seed++){
            Fixture f=generate(seed);Oracle oracle;
            try{oracle=bfs(f);}catch(AssertionError limit){
                if(!limit.getMessage().contains("oracle unexpectedly too large"))throw limit;
                oracleLimits++;Files.writeString(root.resolve("results.jsonl"),json(Map.of("seed",seed,"oracle","LIMIT"))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);continue;
            }
            maxStates=Math.max(maxStates,oracle.states());var checked=run(f,100,false);var g=checked.result();
            if(g.status().equals("TIMEOUT")||g.status().equals("SEARCH_LIMIT")||oracle.feasible()&&!feasible(g)){checked=run(f,3000,false);g=checked.result();}
            if(feasible(g)&&!oracle.feasible())throw new AssertionError("False acceptance "+f.name());
            if(oracle.feasible()&&!feasible(g)){
                missed++;if(g.status().equals("MISSING_INPUT")||g.status().equals("MISSING_SEED")||g.status().equals("INFEASIBLE"))wrong++;
                save(f,"BFS="+oracle+" CGSE="+g);System.out.println("MISS "+f.name()+" BFS="+oracle+" CGSE="+g);
            }
            histogram.merge((oracle.feasible()?"SAT/":"UNSAT/")+g.status(),1,Integer::sum);
            Map<String,Object> record=new LinkedHashMap<>();record.put("seed",seed);record.put("case",f.name());record.put("oracle",oracle.feasible()?"SAT":"UNSAT");record.put("states",oracle.states());record.put("witness",oracle.witness());record.put("cgse",Map.of("status",g.status(),"ms",g.ms()));
            Files.writeString(root.resolve("results.jsonl"),json(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if((seed-start+1)%50==0){System.out.println("PROGRESS "+(seed-start+1)+" missed="+missed+" wrong="+wrong+" oracleLimits="+oracleLimits+" maxStates="+maxStates+" "+histogram);System.out.flush();}
        }
        System.out.println("FINAL count="+count+" missed="+missed+" wrong="+wrong+" oracleLimits="+oracleLimits+" maxStates="+maxStates+" "+histogram);
    }
}
