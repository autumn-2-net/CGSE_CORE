package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

public final class StructuralOracleProbe {
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static boolean oracle(List<GraphRecipe<String>> rs,int fuel,int a,int b,int amount){
        Set<List<Integer>> seen=new HashSet<>();Deque<List<Integer>> todo=new ArrayDeque<>();todo.add(List.of(fuel,a,b,0));
        List<String> keys=List.of("fuel","a","b","x0");
        while(!todo.isEmpty()){
            var v=todo.removeFirst();if(!seen.add(v))continue;if(v.get(3)>=amount)return true;
            for(var recipe:rs){var next=new ArrayList<>(v);boolean enabled=true;
                for(var input:recipe.inputs().entrySet()){int id=keys.indexOf(input.getKey());if(v.get(id)<input.getValue()){enabled=false;break;}next.set(id,next.get(id)-input.getValue().intValue());}
                if(!enabled)continue;for(var out:recipe.outputs().entrySet()){int id=keys.indexOf(out.getKey());next.set(id,next.get(id)+out.getValue().intValue());}todo.addLast(next);
            }
        }return false;
    }
    public static void main(String[] args)throws Exception{
        var results=new ArrayList<Map<String,Object>>();long assertions=0;int yes=0,complete=0,unknown=0;
        for(int seed=0;seed<256;seed++){
            Random random=new Random(seed);int fuel=1+random.nextInt(9),a=random.nextInt(3),b=random.nextInt(3),amount=1+random.nextInt(7);
            var core=new ArrayList<>(List.of(r("forward",Map.of("fuel",1L,"a",1L),Map.of("b",2L)),r("return",Map.of("fuel",1L,"b",1L),Map.of("a",2L)),
                r("exitA",Map.of("fuel",1L,"a",1L),Map.of("x0",1L)),r("exitB",Map.of("fuel",2L,"b",1L),Map.of("x0",2L))));
            boolean expected=oracle(core,fuel,a,b,amount);if(expected)yes++;
            var recipes=new ArrayList<>(core);int length=16+seed%13;
            for(int i=1;i<=length;i++)recipes.add(r("tail"+i,Map.of("x"+(i-1),1L),Map.of(i==length?"target":"x"+i,1L)));
            Collections.shuffle(recipes,new Random(seed*177L));var stock=Map.of("fuel",(long)fuel,"a",(long)a,"b",(long)b);
            var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);boolean feasible=false,impossible=false;String error="";
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(recipes),"target",amount,stock,Map.of(),Set.of(),Set.of(),false,true,budget,System.nanoTime())){
                while(!search.step()){}var plan=search.result();impossible=search.infeasible();if(plan!=null){PlanVerifier.verifyRuntimeInventory(plan);feasible=plan.feasible();}
            }catch(PlanningBudget.Exhausted limit){error=limit.limit().toString();}
            if(feasible||impossible)complete++;else unknown++;
            if(feasible&&!expected||impossible&&expected||budget.reservedBytes()!=0)throw new AssertionError("oracle "+seed+" expected="+expected+" feasible="+feasible+" impossible="+impossible+" leak="+budget.reservedBytes());
            assertions+=3;results.add(Map.of("id",seed,"expected",expected,"feasible",feasible,"infeasible",impossible,"work",budget.nodes(),"limit",error));
        }
        Files.writeString(Path.of(args[0],"structural-oracle.json"),new Gson().toJson(results));System.out.println(Map.of("cases",256,"feasibleOracle",yes,"complete",complete,"unknown",unknown,"assertions",assertions));
    }
}
