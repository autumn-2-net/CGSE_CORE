package org.cgse.core;
import java.util.*;
public final class CompilerProbe {
  static long checks;
  static void ok(boolean x){checks++;if(!x)throw new AssertionError("check "+checks);}
  static GraphRecipe<String> recipe(String id, Map<String,Long> in, Map<String,Long> out){
    var slots=new ArrayList<GraphRecipe.Slot<String>>(); int i=0;
    for(var e:in.entrySet())slots.add(new GraphRecipe.Slot<>(e.getKey(),e.getValue(),i++,false,false));
    return new GraphRecipe<>(id,id,slots,out);
  }
  static PlanningBudget budget(){return new PlanningBudget(0,100_000_000,128L<<20,()->false,System::nanoTime);}
  static Map<String,GraphRecipe<String>> selected(GraphCompiler<String> c,String target,Set<String> extra,Map<String,Integer> choices,Set<String> excluded){
    var selected=new LinkedHashMap<String,GraphRecipe<String>>();var used=new HashSet<String>();var seen=new HashSet<String>();var q=new ArrayDeque<String>();q.add(target);q.addAll(extra);
    while(!q.isEmpty()){String k=q.removeFirst();if(!seen.add(k))continue;int at=choices.getOrDefault(k,0);
      for(var r:c.producers(k)){if(excluded.contains(r.id())||at-->0)continue;selected.put(k,r);if(used.add(r.id()))q.addAll(r.inputs().keySet());break;}}
    return selected;
  }
  static void structure(GraphCompiler<String> c,String target,Set<String> extra,Map<String,Integer> choices,Set<String> excluded){
    var b=budget();GraphCompiler.Compiled<String> g;
    try(var work=c.begin(target,extra,choices,excluded,b)){while(!work.step()){}g=work.result();}
    ok(b.reservedBytes()==0);var expect=selected(c,target,extra,choices,excluded);ok(expect.equals(g.selected()));ok(new ArrayList<>(expect.keySet()).equals(new ArrayList<>(g.selected().keySet())));
    var rs=new ArrayList<>(g.recipes().values());int n=rs.size();boolean[][] reach=new boolean[n][n];
    for(int i=0;i<n;i++)for(int j=0;j<n;j++)for(String in:rs.get(i).inputs().keySet())if(rs.get(j).executionOutputs().containsKey(in))reach[i][j]=true;
    for(int k=0;k<n;k++)for(int i=0;i<n;i++)for(int j=0;j<n;j++)reach[i][j]|=reach[i][k]&&reach[k][j];
    var region=new HashMap<String,Integer>();for(int i=0;i<g.regions().size();i++)for(var r:g.regions().get(i).recipes())ok(region.put(r.id(),i)==null);
    for(int i=0;i<n;i++){int ri=region.get(rs.get(i).id());ok(g.regions().get(ri).cyclic()==reach[i][i]);
      for(int j=0;j<n;j++){int rj=region.get(rs.get(j).id());ok((ri==rj)==(i==j||(reach[i][j]&&reach[j][i])));if(ri!=rj&&reach[i][j])ok(ri<rj);}}
    c.publish(target,extra,choices,excluded,g);ok(c.cached(target,extra,choices,excluded)==g);
  }
  public static void main(String[] args){
    Random random=new Random(713117);for(int trial=0;trial<5000;trial++){
      var rs=new ArrayList<GraphRecipe<String>>();int n=1+random.nextInt(24);
      for(int i=0;i<n;i++){var in=new LinkedHashMap<String,Long>();var out=new LinkedHashMap<String,Long>();for(int k=0;k<random.nextInt(4);k++)in.put("k"+random.nextInt(12),1L);for(int k=0;k<1+random.nextInt(3);k++)out.put("k"+random.nextInt(12),1L);rs.add(recipe("r"+i,in,out));}
      var c=new GraphCompiler<String>(rs);var choices=new LinkedHashMap<String,Integer>();for(int k=0;k<12;k++)choices.put("k"+k,random.nextInt(5));
      var excluded=new HashSet<String>();if(trial%2==0)for(var r:rs)if(random.nextInt(4)==0)excluded.add(r.id());
      var extra=new LinkedHashSet<>(List.of("k2","k7"));structure(c,"k0",extra,choices,excluded);structure(c,"k0",extra,choices,excluded);
      var full=c.cached("k0",extra,choices,excluded);var leafBudget=budget();var leaves=Set.of("k2","k5","k8");var producers=new LinkedHashMap<String,List<GraphRecipe<String>>>();for(int k=0;k<12;k++)if(!leaves.contains("k"+k))producers.put("k"+k,c.producers("k"+k));
      var leafCompiler=new GraphCompiler<String>(rs,producers);try(var work=c.beginStockView("k0",extra,choices,excluded,leaves,leafBudget)){while(!work.step()){}ok(work.result().selected().equals(selected(leafCompiler,"k0",extra,choices,excluded)));}ok(leafBudget.reservedBytes()==0);ok(c.cached("k0",extra,choices,excluded)==full);
      var b=budget();var x=c.countClosure("k0",extra,excluded,100,100,b);ok(x!=null);long old=b.nodes();ok(c.countClosure("k0",extra,excluded,100,100,b)==x);ok(b.nodes()-old==1);
      if(!x.recipes().isEmpty())ok(c.countClosure("k0",extra,excluded,100,x.recipes().size()-1,b)==null);
      var reversed=new LinkedHashSet<>(List.of("k7","k2"));ok(c.cached("k0",reversed,choices,excluded)==null);
    }
    var rs=new ArrayList<GraphRecipe<String>>();for(int i=0;i<9000;i++)rs.add(recipe("r"+i,Map.of("k"+(i+1),1L),Map.of("k"+i,1L)));
    var c=new GraphCompiler<String>(rs);var b=budget();ok(c.countClosure("k0",Set.of(),Set.of(),8192,8192,b)==null);long old=b.nodes();ok(c.countClosure("k0",Set.of(),Set.of(),8192,8192,b)==null);ok(b.nodes()-old==1);var larger=c.countClosure("k0",Set.of(),Set.of(),10000,10000,b);ok(larger!=null&&larger.recipes().size()==9000);
    var tiny=new PlanningBudget(0,1_000_000,1024,()->false,System::nanoTime);try(var work=c.begin("k0",Map.of(),Set.of(),tiny)){try{while(!work.step()){}throw new AssertionError();}catch(PlanningBudget.Exhausted expected){ok(expected.limit()==PlanningBudget.Limit.MEMORY_LIMIT);}}ok(tiny.reservedBytes()==0);
    var more=new GraphCompiler<String>(List.of(recipe("r",Map.of("x",1L),Map.of("y",1L))));var shared=new PlanningBudget(0,1_000_000,2048,()->false,System::nanoTime);for(int i=0;i<1000;i++){try(var work=more.begin("y",Map.of(),Set.of(),shared)){while(!work.step()){}ok(work.result().recipes().size()==1);}ok(shared.reservedBytes()==0);}
    System.out.println("CompilerProbe checks="+checks);
  }
}
