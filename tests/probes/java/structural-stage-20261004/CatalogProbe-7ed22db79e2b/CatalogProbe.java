package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CatalogProbe {
    static long checks, graphs, models, portsChecked;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> inputs,Map<String,Long> outputs){return new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs);}
    static GraphCatalogIndex<String> build(GraphCompiler<String> c){var b=budget();try(var builder=new GraphCatalogIndex.Builder<>(c.catalog(),b)){for(int i=0;i<c.catalog().size();i++)builder.add(c.catalog().get(i),i);while(!builder.step()){}check(builder.result()!=null,"index missing");return builder.result();}finally{check(b.reservedBytes()==0,"builder leak");}}
    static void ports(GraphCompiler<String> c,GraphCatalogIndex<String> index){
        var consumers=new HashMap<String,List<Integer>>();var producers=new HashMap<String,List<Integer>>();
        for(int r=0;r<c.catalog().size();r++){var recipe=c.catalog().get(r);var p=index.ports(recipe);check(p!=null,"missing recipe ports");var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();var physical=new HashSet<String>();var changes=new HashMap<String,BigInteger>();
            for(int i=0;i<p.inputs().length;i++){String k=index.resource(p.inputs()[i]);in.put(k,p.inputAmounts()[i]);check(p.configurationAmounts()[i]==recipe.configurationInputs().getOrDefault(k,0L),"configuration changed");check(p.reusableAmounts()[i]==recipe.reusableInputs().getOrDefault(k,0L),"reusable changed");}
            for(int i=0;i<p.outputs().length;i++)out.put(index.resource(p.outputs()[i]),p.outputAmounts()[i]);for(int i:p.physicalOutputs())physical.add(index.resource(i));
            for(int i=0;i<p.changed().length;i++)changes.put(index.resource(p.changed()[i]),p.changes()[i]);check(in.equals(recipe.inputs())&&out.equals(recipe.outputs())&&physical.equals(recipe.executionOutputs().keySet()),"port meaning changed");
            var keys=new HashSet<>(in.keySet());keys.addAll(out.keySet());for(String k:keys){var net=BigInteger.valueOf(out.getOrDefault(k,0L)).subtract(BigInteger.valueOf(in.getOrDefault(k,0L))).add(BigInteger.valueOf(recipe.configurationInputs().getOrDefault(k,0L))).subtract(BigInteger.valueOf(recipe.reusableInputs().getOrDefault(k,0L)));check(net.equals(changes.getOrDefault(k,BigInteger.ZERO)),"exact delta changed");}
            for(String k:in.keySet())consumers.computeIfAbsent(k,x->new ArrayList<>()).add(r);for(String k:physical)producers.computeIfAbsent(k,x->new ArrayList<>()).add(r);portsChecked++;
        }
        for(int id=0;id<index.resourceCount();id++){String k=index.resource(id);check(Arrays.equals(index.consumers(k),consumers.getOrDefault(k,List.of()).stream().mapToInt(Integer::intValue).toArray()),"consumer adjacency");check(Arrays.equals(index.producers(k),producers.getOrDefault(k,List.of()).stream().mapToInt(Integer::intValue).toArray()),"physical producer adjacency");}
    }
    static void oracle(GraphCompiler<String> compiler,GraphCompiler.Compiled<String> actual,String target,Set<String> extra,Map<String,Integer> choices,Set<String> excluded){
        var selected=new LinkedHashMap<String,GraphRecipe<String>>();var recipes=new LinkedHashMap<String,GraphRecipe<String>>();var pending=new ArrayDeque<String>();pending.add(target);pending.addAll(extra);var seen=new HashSet<String>();
        while(!pending.isEmpty()){String k=pending.removeFirst();if(!seen.add(k))continue;var eligible=compiler.producers(k).stream().filter(r->!excluded.contains(r.id())).toList();int chosen=choices.getOrDefault(k,0);if(chosen>=eligible.size())continue;var r=eligible.get(chosen);selected.put(k,r);if(recipes.putIfAbsent(r.id(),r)==null)pending.addAll(r.inputs().keySet());}
        check(actual.recipes().equals(recipes)&&actual.selected().equals(selected),"selection differs");var rs=new ArrayList<>(recipes.values());int n=rs.size();boolean[][] reach=new boolean[n][n];
        for(int i=0;i<n;i++)for(int j=0;j<n;j++)for(String k:rs.get(i).inputs().keySet())if(rs.get(j).executionOutputs().containsKey(k))reach[i][j]=true;
        for(int k=0;k<n;k++)for(int i=0;i<n;i++)for(int j=0;j<n;j++)reach[i][j]|=reach[i][k]&&reach[k][j];
        var groups=new HashMap<String,Integer>();for(int i=0;i<actual.regions().size();i++)for(var r:actual.regions().get(i).recipes())check(groups.put(r.id(),i)==null,"duplicate SCC member");check(groups.size()==n,"lost SCC member");
        for(int i=0;i<n;i++){int a=groups.get(rs.get(i).id());check(actual.regions().get(a).cyclic()==reach[i][i],"cyclic flag");for(int j=0;j<n;j++){int z=groups.get(rs.get(j).id());check((a==z)==(i==j||reach[i][j]&&reach[j][i]),"partition changed");if(reach[i][j])check(a<=z,"consumer-first order changed");}}
        graphs++;
    }
    static GraphCompiler.Compiled<String> compile(GraphCompiler<String> c,String target,Set<String> extra,Map<String,Integer> choices,Set<String> excluded){try(var w=c.begin(target,extra,choices,excluded,budget())){while(!w.step()){}return w.result();}}
    static Set<Set<String>> groups(GraphCompiler.Compiled<String> c){var result=new HashSet<Set<String>>();for(var r:c.regions()){var ids=new TreeSet<String>();for(var x:r.recipes())ids.add(x.id());ids.add("cyclic="+r.cyclic());result.add(ids);}return result;}
    static void random(){
        for(int seed=0;seed<2400;seed++){
            var rs=StartupProbe.recipes(seed);var cold=new GraphCompiler<>(rs);var hot=new GraphCompiler<>(rs);var index=build(hot);ports(hot,index);hot.rememberCatalogIndex(index);var rng=new Random(65537L*seed+171);
            for(int p=0;p<8;p++){String target="k"+rng.nextInt(12);var extra=p%2==0?Set.of("k"+rng.nextInt(12)):Set.<String>of();var excluded=new HashSet<String>();var choices=new HashMap<String,Integer>();for(var r:rs)if(rng.nextInt(7)==0)excluded.add(r.id());for(int k=0;k<12;k++)choices.put("k"+k,rng.nextInt(3));
                var a=compile(cold,target,extra,choices,excluded);var z=compile(hot,target,extra,choices,excluded);oracle(cold,a,target,extra,choices,excluded);oracle(hot,z,target,extra,choices,excluded);check(groups(a).equals(groups(z)),"cold/hot SCC");
                var stock=new HashMap<String,Long>();for(int k=0;k<12;k++)if(rng.nextBoolean())stock.put("k"+k,p%3==0?Long.MAX_VALUE:1L+rng.nextInt(33));var external=p%3==0?Set.of("k"+rng.nextInt(12)):Set.<String>of();var seeds=extra.isEmpty()?Map.<String,Long>of():Map.of(extra.iterator().next(),2L);var b1=budget();var b2=budget();
                // Force the cold constructor every time; otherwise count-catalog reuse would hide packed-row mistakes.
                var freshCold=new GraphCompiler<>(rs);var freshHot=new GraphCompiler<>(hot.catalog());freshHot.rememberCatalogIndex(index);long amount=p%4==0?Long.MAX_VALUE:1L+rng.nextInt(128);
                try(var m1=RecipeCountModel.create(freshCold,target,amount,stock,seeds,external,excluded,p%2==0,b1);var m2=RecipeCountModel.create(freshHot,target,amount,stock,seeds,external,excluded,p%2==0,b2)){
                    check(m1!=null&&m2!=null,"unexpected model cutoff");check(m1.recipes.equals(m2.recipes)&&m1.keys.equals(m2.keys)&&m1.constraints.equals(m2.constraints)&&m1.rowKeys.equals(m2.rowKeys)&&m1.productionGoals.equals(m2.productionGoals),"packed count model differs");models++;
                }check(b1.reservedBytes()==0&&b2.reservedBytes()==0,"count leak");
            }
        }
    }
    static void parallel()throws Exception{
        var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<900;i++){var in=new LinkedHashMap<String,Long>();in.put("k"+(i-1),1L);if(i%5==0)in.put("side"+(i%17),1L);var out=new LinkedHashMap<String,Long>();out.put("k"+i,2L);out.put("side"+((i+5)%17),1L);rs.add(recipe("r"+i,in,out));}
        var c=new GraphCompiler<>(rs);c.rememberCatalogIndex(build(c));var expected=compile(new GraphCompiler<>(rs),"k899",Set.of(),Map.of(),Set.of());
        try(var s=new PlanningScheduler(4,32,128,1_000_000)){
            var jobs=new ArrayList<CompletableFuture<GraphCompiler.Compiled<String>>>();for(int i=0;i<24;i++){var b=budget();jobs.add(s.submit(c.begin("k899",Map.of(),Set.of(),b),b));}for(var job:jobs){var actual=job.get(30,TimeUnit.SECONDS);check(actual.recipes().equals(expected.recipes())&&groups(actual).equals(groups(expected)),"parallel SCC mismatch");}
        }
    }
    static void limits(){
        var c=new GraphCompiler<>(StartupProbe.recipes(555));
        for(int limit=1;limit<=600;limit++){var stop=new AtomicBoolean();var b=new PlanningBudget(0,20_000_000,128L<<20,stop::get,System::nanoTime);try(var builder=new GraphCatalogIndex.Builder<>(c.catalog(),b)){
                int steps=0;for(int i=0;i<c.catalog().size();i++){if(steps++==limit)stop.set(true);builder.add(c.catalog().get(i),i);}while(!builder.step()){if(steps++==limit)stop.set(true);}
            }catch(CancellationException expected){}check(b.reservedBytes()==0,"cancel index leak");}
        for(int work:new int[]{1,100,1024,2000,16000,17000,20000,100000})for(int memory:new int[]{1024,4096,16384,65536,262144,1048576}){var b=new PlanningBudget(0,work,memory,()->false,System::nanoTime);try(var builder=new GraphCatalogIndex.Builder<>(c.catalog(),b)){for(int i=0;i<c.catalog().size();i++)builder.add(c.catalog().get(i),i);while(!builder.step()){}if(builder.result()!=null)ports(c,builder.result());}catch(PlanningBudget.Exhausted expected){}check(b.reservedBytes()==0,"limit index leak");}
        var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<1500;i++)rs.add(recipe("r"+i,Map.of("k"+(i-1),1L),Map.of("k"+i,1L)));var chain=new GraphCompiler<>(rs);
        // Exhaust the optional scan specifically during packed-array freezing.
        for(int work=70000;work<160000;work+=1000){var b=new PlanningBudget(0,work,128L<<20,()->false,System::nanoTime);try(var builder=new GraphCatalogIndex.Builder<>(chain.catalog(),b)){for(int i=0;i<rs.size();i++)builder.add(rs.get(i),i);while(!builder.step()){}check(b.nodes()<=work/16+2,"optional index over budget");}check(b.reservedBytes()==0,"freeze decline leak");}
    }
    public static void main(String[]args)throws Exception{random();parallel();limits();var report=Map.of("catalogs",2400,"graphs",graphs,"countModels",models,"ports",portsChecked,"parallelGraphs",24,"checks",checks);Files.writeString(Path.of(args[0],"catalog-probe.json"),new Gson().toJson(report));System.out.println(report);}
}
