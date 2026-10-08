package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class FallbackSourcesProbe {
    static int checks;static boolean legacy;
    static void ck(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static PlanningBudget budget(){return new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);}
    static Map<String,Integer> distances(GraphFallbackSources<String> x)throws Exception{var f=GraphFallbackSources.class.getDeclaredField("distance");f.setAccessible(true);return (Map<String,Integer>)f.get(x);}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return BootstrapOracleProbe.recipe(id,in,out);}
    static void reference(List<GraphRecipe<String>> rs,Map<String,Long> stock,Set<String> external,String target,boolean force)throws Exception{
        var distance=new HashMap<String,Integer>();var cost=new HashMap<String,Double>();var keys=new LinkedHashSet<String>();
        for(var recipe:rs){keys.addAll(recipe.inputs().keySet());keys.addAll(recipe.outputs().keySet());}
        for(var key:keys){long have=force&&target.equals(key)?0:stock.getOrDefault(key,0L);if(have>0||external.contains(key)){distance.put(key,0);cost.put(key,external.contains(key)?0.0:1.0/have);}}
        boolean changed;do{changed=false;for(var recipe:rs){int depth=0;boolean ready=true;for(var input:recipe.inputs().keySet()){var d=distance.get(input);if(d==null){ready=false;break;}depth=Math.max(depth,d);}if(ready)for(var output:recipe.outputs().keySet())if(distance.getOrDefault(output,Integer.MAX_VALUE)>depth+1){distance.put(output,depth+1);changed=true;}}}while(changed);
        for(int pass=0;pass<8;pass++)for(var recipe:rs){double value=0;for(var input:recipe.inputs().entrySet())value+=input.getValue()*cost.getOrDefault(input.getKey(),Double.POSITIVE_INFINITY);for(var output:recipe.outputs().entrySet()){double v=value/output.getValue();if(v<cost.getOrDefault(output.getKey(),Double.POSITIVE_INFINITY))cost.put(output.getKey(),v);}}
        var compiler=new GraphCompiler<>(rs);var b=budget();try(var actual=GraphFallbackSources.create(compiler,stock,external,target,force,b)){
            ck(actual!=null,"small closure skipped");ck(distances(actual).equals(distance),"distance differs reference expected="+distance+" actual="+distances(actual));
            for(var key:keys)for(boolean byCost:new boolean[]{false,true}){
                var expected=new ArrayList<GraphRecipe<String>>();for(var recipe:compiler.producers(key))if(distance.keySet().containsAll(recipe.inputs().keySet()))expected.add(recipe);
                expected.sort(Comparator.comparingDouble(recipe->{double score=0;for(var input:recipe.inputs().entrySet())score+=byCost?input.getValue()*cost.getOrDefault(input.getKey(),Double.POSITIVE_INFINITY):distance.get(input.getKey());return byCost?score/recipe.outputs().getOrDefault(key,1L):score;}));
                ck(actual.sources(key,byCost).equals(expected),"source ordering differs key="+key+" byCost="+byCost);
            }
        }ck(b.reservedBytes()==0,"random helper leak");
    }
    static void randomized()throws Exception{
        var random=new Random(202610031724L);
        for(int sample=0;sample<300;sample++){
            var rs=new ArrayList<GraphRecipe<String>>();int n=1+random.nextInt(40),keys=3+random.nextInt(15);
            for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int j=random.nextInt(4);j>0;j--)in.put("K"+random.nextInt(keys),1L+random.nextInt(4));for(int j=1+random.nextInt(2);j>0;j--)out.put("K"+random.nextInt(keys),1L+random.nextInt(4));rs.add(r("r"+i,in,out));}
            var stock=new HashMap<String,Long>();var external=new HashSet<String>();for(int i=0;i<keys;i++){if(random.nextInt(3)==0)stock.put("K"+i,1L+random.nextInt(20));if(random.nextInt(11)==0)external.add("K"+i);}
            reference(rs,stock,external,"K0",random.nextBoolean());
        }
        reference(List.of(r("zero",Map.of(),Map.of("B",1L)),r("fast",Map.of("S",1L),Map.of("A",1L)),r("via",Map.of("B",1L),Map.of("A",1L)),r("join",Map.of("A",1L,"B",1L),Map.of("T",1L)),r("cycle",Map.of("U",1L),Map.of("U",1L))),Map.of("S",1L),Set.of(),"T",true);
    }
    static List<GraphRecipe<String>> chain(int n,boolean bad){var rs=new ArrayList<GraphRecipe<String>>();for(int i=n;i>0;i--){if(bad){var inputs=new LinkedHashMap<String,Long>();inputs.put("K"+(i-1),1L);inputs.put("missing"+i,1L);rs.add(r("bad"+i,inputs,Map.of("K"+i,1L)));rs.add(r("missingSrc"+i,Map.of("never"+i,1L),Map.of("missing"+i,1L)));}rs.add(r("r"+i,Map.of("K"+(i-1),1L),Map.of("K"+i,1L)));}return rs;}
    static void large()throws Exception{
        for(int n:new int[]{200,1024,2048}){var b=budget();try(var order=GraphFallbackSources.create(new GraphCompiler<>(chain(n,false)),Map.of("K0",1L),Set.of(),"K"+n,true,b)){System.out.println("reverse-chain n="+n+" admitted="+(order!=null)+" work="+b.nodes());if(!legacy)ck(order!=null,"worklist could not admit simple chain="+n);if(order!=null)ck(distances(order).get("K"+n)==n,"wrong deep distance");}ck(b.reservedBytes()==0,"deep close leak");}
        var wide=new ArrayList<GraphRecipe<String>>();var inputs=new LinkedHashMap<String,Long>();for(int i=0;i<600;i++){wide.add(r("leaf"+i,Map.of(),Map.of("K"+i,1L)));inputs.put("K"+i,1L);}wide.add(r("join",inputs,Map.of("T",1L)));
        var b=budget();try(var order=GraphFallbackSources.create(new GraphCompiler<>(wide),Map.of(),Set.of(),"T",true,b)){if(!legacy)ck(order!=null&&distances(order).get("T")==2,"wide closure/depth");}ck(b.reservedBytes()==0,"wide close leak");
    }
    static void lifecycle(){var compiler=new GraphCompiler<>(chain(200,false));for(int cutoff:new int[]{0,1,4,16,64,256,1024,4096}){var calls=new AtomicInteger();var b=new PlanningBudget(0,2_000_000,128L<<20,()->calls.incrementAndGet()>cutoff,System::nanoTime);try(var ignored=GraphFallbackSources.create(compiler,Map.of("K0",1L),Set.of(),"K200",true,b)){}catch(CancellationException expected){}ck(b.reservedBytes()==0,"cancel closure leak="+cutoff+" bytes="+b.reservedBytes());}
        for(long memory:new long[]{1024,65536,128L<<10,512L<<10,4L<<20}){var b=new PlanningBudget(0,2_000_000,memory,()->false,System::nanoTime);try(var ignored=GraphFallbackSources.create(compiler,Map.of("K0",1L),Set.of(),"K200",true,b)){}ck(b.reservedBytes()==0,"low-memory closure leak="+memory);}
        var big=new ArrayList<>(chain(2048,false));big.add(r("extra",Map.of(),Map.of("bonus",1L)));var b=budget();try(var order=GraphFallbackSources.create(new GraphCompiler<>(big),Map.of("K0",1L),Set.of(),"K2048",true,b)){ck(order==null,"catalog cap removed");}ck(b.reservedBytes()==0,"cap leak");
    }
    static void actual(){for(int padding:new int[]{0,300}){int n=64;var rs=new ArrayList<>(chain(n,true));for(int i=0;i<padding;i++)rs.add(r("unrelated"+i,Map.of("absent"+i,1L),Map.of("unused"+i,1L)));var compiler=new GraphCompiler<>(rs);var b=budget();var p=GraphFallback.plan(compiler,"K"+n,1,Map.of("K0",1L),Set.of(),Map.of(),false,true,b);System.out.println("actual-bad-first depth="+n+" catalog="+rs.size()+" result="+p.result()+" work="+b.nodes()+" steps="+p.patternTimesExact().size());if(!legacy)ck(p.feasible(),"worklist did not rescue failed priority tree padding="+padding);if(p.feasible()){p.initialExact().forEach((key,value)->ck(value.compareTo(BigInteger.valueOf(key.equals("K0")?1:0))<=0,"fallback overdraw"));try(var v=new PlanVerification<>(p,b)){while(!v.step()){}ck(v.physicalProduced("K"+n).signum()>0,"fallback borrowed target");}}}}
    public static void main(String[]args)throws Exception{legacy=args.length>0&&args[0].equals("legacy");randomized();large();lifecycle();actual();System.out.println("PASS fallback worklist checks="+checks+" reference-models=301 cancellation=8 memoryCaps=5");}
}
