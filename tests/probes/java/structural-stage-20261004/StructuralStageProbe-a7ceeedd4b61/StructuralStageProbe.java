package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class StructuralStageProbe {
    static long assertions;
    static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    record Case(List<GraphRecipe<String>> recipes,Map<String,Long> stock,Map<String,BigInteger> counts,long amount,boolean force){}
    static Case chain(int length,int seed){
        Random random=new Random(seed);long amount=1+random.nextInt(500);boolean force=seed%2==0;
        var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
        var expected=new LinkedHashMap<String,BigInteger>();
        stock.put("target",(long)random.nextInt(900));
        for(int i=0;i<length;i++){
            long out=1+random.nextInt(4),in=1+random.nextInt((int)out);
            var inputs=new LinkedHashMap<String,Long>();inputs.put(i==0?"raw":"x"+(i-1),in);
            if(i%3==0)inputs.put("side",1L);
            recipes.add(r("r"+i,inputs,Map.of(i==length-1?"target":"x"+i,out)));
        }
        var demand=new LinkedHashMap<String,BigInteger>();demand.put("target",BigInteger.valueOf(amount));
        for(int i=length-1;i>=0;i--){var recipe=recipes.get(i);String key=recipe.outputs().keySet().iterator().next();
            BigInteger need=demand.getOrDefault(key,BigInteger.ZERO).subtract(BigInteger.valueOf(stock.getOrDefault(key,0L))).max(BigInteger.ZERO);
            if(force&&key.equals("target"))need=need.max(BigInteger.valueOf(amount));
            BigInteger q=BigInteger.valueOf(recipe.outputs().get(key));BigInteger runs=need.add(q).subtract(BigInteger.ONE).divide(q);
            expected.put(recipe.id(),runs);
            recipe.inputs().forEach((k,v)->demand.merge(k,BigInteger.valueOf(v).multiply(runs),BigInteger::add));
        }
        stock.put("raw",demand.getOrDefault("raw",BigInteger.ZERO).longValueExact());stock.put("side",demand.getOrDefault("side",BigInteger.ZERO).longValueExact());
        Collections.shuffle(recipes,new Random(seed+1000));
        return new Case(recipes,stock,expected,amount,force);
    }
    static void shells(){
        for(int seed=0;seed<600;seed++){
            Case c=chain(1+seed%80,seed);var b=budget();
            try(var m=RecipeCountModel.create(new GraphCompiler<>(c.recipes),"target",c.amount,c.stock,Map.of(),Set.of(),Set.of(),c.force,b);
                var kernel=CountShellCompilation.create(m,"target")){
                check(kernel!=null,"missing kernel");check(kernel.peeled()==m.recipes.size(),"unpeeled chain");
                for(int i=0;i<m.recipes.size();i++)check(kernel.fixed(i).equals(c.counts.get(m.recipes.get(i).id())),"wrong ceil or target stock");
                check(kernel.restoreAndCheck(m,new BigInteger[0])!=null,"bad inverse");
            }
            check(b.reservedBytes()==0,"kernel leak");
        }
    }
    static void boundary(String name,List<GraphRecipe<String>> rs,Map<String,Long> stock,Map<String,Long> seeds,Set<String> external,Set<String> forbidden){
        var b=budget();try(var m=RecipeCountModel.create(new GraphCompiler<>(rs),"target",1,stock,seeds,external,Set.of(),true,b);
            var k=CountShellCompilation.create(m,"target")){
            check(k!=null,"boundary kernel "+name);
            for(int i=0;i<m.recipes.size();i++)if(forbidden.contains(m.recipes.get(i).id()))check(k.fixed(i)==null,"crossed "+name+" "+m.recipes.get(i).id());
        }check(b.reservedBytes()==0,"boundary leak "+name);
    }
    static void boundaries(){
        var start=r("start",Map.of("raw",1L),Map.of("mid",1L));var end=r("end",Map.of("mid",1L),Map.of("target",1L));
        boundary("stock",List.of(start,end),Map.of("mid",1L),Map.of(),Set.of(),Set.of("start"));
        boundary("seed",List.of(start,end),Map.of(),Map.of("mid",1L),Set.of(),Set.of("start"));
        boundary("external",List.of(start,end),Map.of(),Map.of(),Set.of("mid"),Set.of("start"));
        boundary("coproduct",List.of(r("start",Map.of("raw",1L),Map.of("mid",1L,"other",1L)),end),Map.of(),Map.of(),Set.of(),Set.of("start"));
        boundary("choice",List.of(start,r("alternative",Map.of("raw2",1L),Map.of("mid",1L)),end),Map.of(),Map.of(),Set.of(),Set.of("start","alternative"));
        boundary("cycle",List.of(r("start",Map.of("target",1L),Map.of("mid",2L)),end),Map.of("target",1L),Map.of(),Set.of(),Set.of("start","end"));
        boundary("configuration",List.of(new GraphRecipe<>("start","start",List.of(new GraphRecipe.Slot<>("raw",1,0,true)),Map.of("mid",1L)),end),Map.of(),Map.of(),Set.of(),Set.of("start"));
        boundary("reusable",List.of(new GraphRecipe<>("start","start",List.of(new GraphRecipe.Slot<>("raw",1,0,true,true)),Map.of("raw",1L,"mid",1L)),end),Map.of(),Map.of(),Set.of(),Set.of("start"));
        // Two suffixes share an upstream producer: aggregate both exact demands.
        var rs=List.of(r("s",Map.of("raw",3L),Map.of("x",2L)),r("a",Map.of("x",3L),Map.of("a",1L)),r("b",Map.of("x",2L),Map.of("b",1L)),r("end",Map.of("a",1L,"b",1L),Map.of("target",1L)));
        var b=budget();try(var m=RecipeCountModel.create(new GraphCompiler<>(rs),"target",1,Map.of("raw",9L),Map.of(),Set.of(),Set.of(),true,b);var k=CountShellCompilation.create(m,"target")){
            check(k.peeled()==4,"shared suffix not peeled");for(int i=0;i<m.recipes.size();i++)if(m.recipes.get(i).id().equals("s"))check(k.fixed(i).equals(BigInteger.valueOf(3)),"shared ceiling applied too early");
        }check(b.reservedBytes()==0,"shared leak");
    }
    static void lifecycle(){
        Case c=chain(40,12);
        for(int limit=1;limit<=240;limit++){
            AtomicInteger calls=new AtomicInteger();final int at=limit*7;
            var b=new PlanningBudget(0,2_000_000,128L<<20,()->calls.incrementAndGet()>at,System::nanoTime);
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(c.recipes),"target",c.amount,c.stock,Map.of(),Set.of(),Set.of(),false,true,b,System.nanoTime())){while(!search.step()){} }
            catch(CancellationException|PlanningBudget.Exhausted accepted){}
            check(b.reservedBytes()==0,"cancel leak "+limit+" "+b.reservedBytes());
        }
        for(int limit=1;limit<=100;limit++){
            var b=new PlanningBudget(0,1000000,limit*32768L,()->false,System::nanoTime);
            try(var search=new IntegerCountSearch<>(new GraphCompiler<>(c.recipes),"target",c.amount,c.stock,Map.of(),Set.of(),Set.of(),false,true,b,System.nanoTime())){while(!search.step()){} }
            catch(PlanningBudget.Exhausted accepted){}
            check(b.reservedBytes()==0,"memory leak "+limit);
        }
    }
    public static void main(String[]args)throws Exception{shells();boundaries();lifecycle();var report=Map.of("assertions",assertions,"chains",600,"lifecycle",340);Files.writeString(Path.of(args[0],"structural-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
