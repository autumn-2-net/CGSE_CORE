package org.cgse.core;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.math.BigInteger;
import java.util.*;
import java.nio.file.*;
import static org.cgse.core.ContrastProbe.*;
import static org.cgse.core.AdversarialProbe.*;

/** Batch of independently specified structural families. Production sources are unchanged. */
public final class BatchProbe {
    record Test(String family,Fixture fixture,String truth,List<String> witness,int horizon) {}
    static final List<Test> tests=new ArrayList<>();
    static Path root;
    static void add(String family,Fixture f,String truth,List<String> witness,int horizon){tests.add(new Test(family,f,truth,witness,horizon));}
    static Fixture interval(int n,int dims,int seed,int slack) {
        Fixture f=multi(n,dims,1000,seed,0);List<GraphRecipe<String>> rs=new ArrayList<>(f.recipes());
        Map<String,Long> need=new LinkedHashMap<>(rs.remove(rs.size()-1).inputs());
        need.replaceAll((k,v)->Math.max(1,v-slack));rs.add(r("finish",need,Map.of("GOAL",1L)));
        return new Fixture("interval_n"+n+"_d"+dims+"_s"+seed+"_slack"+slack,rs,f.stock(),f.target(),1,true);
    }
    static Fixture multiway(int n,int colors,int seed) {
        Random rng=new Random(seed);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int i=0;i<n;i++){
            stock.put("U"+i,1L);need.put("D"+i,1L);int chosen=i<colors?i:rng.nextInt(colors);
            long[] w={1+rng.nextInt(1000),1+rng.nextInt(1000)};
            for(int c=0;c<colors;c++){
                Map<String,Long> outputs=new LinkedHashMap<>();outputs.put("D"+i,1L);
                for(int d=0;d<2;d++){String k="C"+c+"_"+d;outputs.put(k,w[d]);if(chosen==c)need.merge(k,w[d],Long::sum);}
                rs.add(r("r"+i+"c"+c,Map.of("U"+i,1L),outputs));
            }
        }
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Fixture("multiway_n"+n+"_c"+colors+"_s"+seed,rs,stock,"GOAL",1,true);
    }
    static Fixture cover(int n,int extra,int seed){
        Random rng=new Random(seed);List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>();
        for(int i=0;i<n;i++)stock.put("U"+i,1L);
        List<Set<Integer>> sets=new ArrayList<>();for(int i=0;i<n;i+=3)sets.add(Set.of(i,i+1,i+2));
        while(sets.size()<n/3+extra){Set<Integer>s=new TreeSet<>();while(s.size()<3)s.add(rng.nextInt(n));if(!sets.contains(s))sets.add(s);}
        Collections.shuffle(sets,rng);int i=0;for(var s:sets){Map<String,Long> in=new LinkedHashMap<>();for(int v:s)in.put("U"+v,1L);rs.add(r("r"+i++,in,Map.of("COUNT",1L)));}
        rs.add(r("finish",Map.of("COUNT",(long)(n/3)),Map.of("GOAL",1L)));return new Fixture("cover_n"+n+"_extra"+extra+"_s"+seed,rs,stock,"GOAL",1,true);
    }
    static Fixture coloring(int n,int seed,boolean planted){
        Random rng=new Random(seed);int[] assignment=new int[n];for(int i=0;i<n;i++)assignment[i]=rng.nextInt(3);
        List<int[]> edges=new ArrayList<>();Set<String> seen=new HashSet<>();
        while(edges.size()<n*3){int a=rng.nextInt(n),b=rng.nextInt(n);if(a==b||planted&&assignment[a]==assignment[b])continue;int lo=Math.min(a,b),hi=Math.max(a,b);if(seen.add(lo+":"+hi))edges.add(new int[]{lo,hi});}
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();
        for(int e=0;e<edges.size();e++)for(int c=0;c<3;c++)stock.put("E"+e+"c"+c,1L);
        for(int v=0;v<n;v++){
            stock.put("U"+v,1L);need.put("D"+v,1L);
            for(int c=0;c<3;c++){Map<String,Long> in=new LinkedHashMap<>();in.put("U"+v,1L);for(int e=0;e<edges.size();e++)if(edges.get(e)[0]==v||edges.get(e)[1]==v)in.put("E"+e+"c"+c,1L);rs.add(r("v"+v+"c"+c,in,Map.of("D"+v,1L)));}
        }
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Fixture("color_n"+n+"_s"+seed+"_planted"+planted,rs,stock,"GOAL",1,true);
    }
    static Fixture matching(int size,boolean bridge){
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>();
        for(int i=0;i<size*2;i++)stock.put("U"+i,1L);
        for(int group=0;group<2;group++)for(int i=0;i<size;i++)for(int j=i+1;j<size;j++)rs.add(r("e"+group+"_"+i+"_"+j,Map.of("U"+(group*size+i),1L,"U"+(group*size+j),1L),Map.of("PAIR",1L)));
        if(bridge)rs.add(r("bridge",Map.of("U0",1L,"U"+size,1L),Map.of("PAIR",1L)));
        rs.add(r("finish",Map.of("PAIR",(long)size),Map.of("GOAL",1L)));return new Fixture("matching_odd"+size+"_bridge"+bridge,rs,stock,"GOAL",1,true);
    }
    static Test counter(int bits,int shortage){
        List<GraphRecipe<String>> rs=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),need=new LinkedHashMap<>();stock.put("F",(1L<<bits)-1-shortage);
        for(int i=0;i<bits;i++){
            stock.put("Z"+i,1L);need.put("O"+i,1L);Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();in.put("F",1L);in.put("Z"+i,1L);out.put("O"+i,1L);
            for(int j=0;j<i;j++){in.put("O"+j,1L);out.put("Z"+j,1L);}rs.add(r("inc"+i,in,out));
        }
        rs.add(r("finish",need,Map.of("GOAL",1L)));return new Test("binary_counter",new Fixture("counter_bits"+bits+"_short"+shortage,rs,stock,"GOAL",1,false),shortage==0?"SAT":"UNSAT",List.of(),-1);
    }
    static Test walk(int length,int seed){
        Random rng=new Random(seed);int places=5,total=8;long[] held=new long[places];Map<String,Long> stock=new LinkedHashMap<>();stock.put("F",(long)length);
        for(int i=0;i<total;i++)held[rng.nextInt(places)]++;for(int i=0;i<places;i++)if(held[i]>0)stock.put("P"+i,held[i]);
        List<GraphRecipe<String>> rs=new ArrayList<>();List<String> witness=new ArrayList<>();
        for(int step=0;step<length+8;step++){
            int size=1+rng.nextInt(3);Map<String,Long> in=new LinkedHashMap<>(),out=new LinkedHashMap<>();in.put("F",1L);out.put("STEP",1L);
            for(int i=0;i<size;i++){int v;do{v=rng.nextInt(places);}while(step<length&&held[v]==0);in.merge("P"+v,1L,Long::sum);if(step<length)held[v]--;}
            for(int i=0;i<size;i++){int v=rng.nextInt(places);out.merge("P"+v,1L,Long::sum);if(step<length)held[v]++;}
            rs.add(r("r"+step,in,out));if(step<length)witness.add("r"+step);
        }
        Map<String,Long> need=new LinkedHashMap<>();need.put("STEP",(long)length);for(int i=0;i<places;i++)if(held[i]>0)need.put("P"+i,held[i]);
        Collections.shuffle(rs,rng);rs.add(r("finish",need,Map.of("GOAL",1L)));witness.add("finish");
        return new Test("fuelled_order",new Fixture("walk_h"+length+"_s"+seed,rs,stock,"GOAL",1,false),"SAT",witness,length+1);
    }
    static void generate(){
        for(int cap:new int[]{2,3,8,1024})for(int n:new int[]{8,12,16})for(int seed:new int[]{7,19})add("bounded_integer",NextProbe.bounded(n,cap,2,1000,seed,0),"SAT",List.of(),0);
        for(int colors:new int[]{3,4})for(int n:new int[]{8,12,20})for(int seed=0;seed<3;seed++)add("multiway",multiway(n,colors,seed),"SAT",List.of(),0);
        for(int n:new int[]{20,28,36})for(int slack:new int[]{1,10,100})for(int seed:new int[]{7,19})add("interval_balance",interval(n,2,seed,slack),"SAT",List.of(),0);
        for(int n:new int[]{16,24,32})for(int dims:new int[]{8,9,12})for(int seed:new int[]{7,19})add("many_dimensions",multi(n,dims,1000,seed,0),"SAT",List.of(),0);
        for(int n:new int[]{12,24,36})for(int seed=0;seed<3;seed++)add("exact_cover",cover(n,n*3,seed),"SAT",List.of(),0);
        for(int n:new int[]{20,35,60})for(int seed=0;seed<3;seed++)for(boolean planted:new boolean[]{true,false})add("shared_exclusion",coloring(n,seed,planted),planted?"SAT":"UNKNOWN",List.of(),0);
        for(int size:new int[]{3,5,7,9,11,15})for(boolean bridge:new boolean[]{false,true})add("odd_matching",matching(size,bridge),bridge?"SAT":"UNSAT",List.of(),0);
        for(int bits:new int[]{6,10,14,18,24})for(int shortage:new int[]{0,1})tests.add(counter(bits,shortage));
        for(int length:new int[]{12,24,40})for(int seed=0;seed<6;seed++)tests.add(walk(length,seed));
    }
    static void replay(Test test){
        if(test.witness().isEmpty())return;Map<String,GraphRecipe<String>> rs=new LinkedHashMap<>();for(var r:test.fixture().recipes())rs.put(r.id(),r);
        verify(test.fixture(),summary(new PlanStep.Sequence(test.witness().stream().<PlanStep>map(id->new PlanStep.Batch(id,1)).toList()),rs));
    }
    static Result timedCp(Test test,double seconds){
        Fixture f=test.fixture();int h=test.horizon();long start=System.nanoTime();CpModel m=new CpModel();
        Set<String> ks=new TreeSet<>(f.stock().keySet());ks.add(f.target());for(var r:f.recipes()){ks.addAll(r.inputs().keySet());ks.addAll(r.outputs().keySet());}List<String> keys=new ArrayList<>(ks);
        IntVar[][] q=new IntVar[h+1][keys.size()];BoolVar[][] fire=new BoolVar[h][f.recipes().size()];
        for(int k=0;k<keys.size();k++){String key=keys.get(k);long inc=0;for(var r:f.recipes())inc=Math.max(inc,r.outputs().getOrDefault(key,0L));long max=f.stock().getOrDefault(key,0L)+h*inc;
            for(int t=0;t<=h;t++)q[t][k]=m.newIntVar(0,max,"q"+t+"_"+k);m.addEquality(q[0][k],f.stock().getOrDefault(key,0L));}
        for(int t=0;t<h;t++){
            for(int i=0;i<f.recipes().size();i++)fire[t][i]=m.newBoolVar("a"+t+"_"+i);
            m.addLessOrEqual(LinearExpr.sum(fire[t]),1);
            for(int k=0;k<keys.size();k++){
                String key=keys.get(k);LinearExprBuilder balance=LinearExpr.newBuilder().add(q[t][k]);
                for(int i=0;i<f.recipes().size();i++){var r=f.recipes().get(i);long in=r.inputs().getOrDefault(key,0L),output=r.outputs().getOrDefault(key,0L);if(in>0)m.addGreaterOrEqual(q[t][k],in).onlyEnforceIf(fire[t][i]);balance.addTerm(fire[t][i],output-in);}
                m.addEquality(q[t+1][k],balance);
            }
        }
        m.addGreaterOrEqual(q[h][keys.indexOf(f.target())],f.amount());CpSolver solver=new CpSolver();solver.getParameters().setNumWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(seconds);
        var status=solver.solve(m);double ms=(System.nanoTime()-start)/1e6;
        List<String> path=new ArrayList<>();if(status==CpSolverStatus.OPTIMAL||status==CpSolverStatus.FEASIBLE){for(int t=0;t<h;t++)for(int i=0;i<f.recipes().size();i++)if(solver.booleanValue(fire[t][i]))path.add(f.recipes().get(i).id());replay(new Test(test.family(),f,"SAT",path,h));}
        return new Result(status.name(),ms,"horizon="+h+" witness="+path+" validation="+m.validate());
    }
    static Map<String,Object> result(Result r){return Map.of("status",r.status(),"ms",r.ms(),"info",r.info());}
    static void one(Test test,long budget,boolean nativeSolve,int rep)throws Exception{
        Fixture f=test.fixture();replay(test);Result cp=null,mip=null;
        if(nativeSolve&&f.dag()){cp=cpDag(f,budget/1000.0);mip=scip(f,budget/1000.0);}
        else if(nativeSolve&&test.horizon()>0)cp=timedCp(test,budget/1000.0);
        var checked=run(f,budget,f.dag());var g=checked.result();
        if(test.truth().equals("SAT")&&(g.status().equals("MISSING_INPUT")||g.status().equals("MISSING_SEED")||g.status().equals("INFEASIBLE")))System.out.println("WRONG_NEGATIVE "+f.name());
        if(test.truth().equals("UNSAT")&&feasible(g))throw new AssertionError("Unexpected feasibility "+f.name());
        Map<String,Object> row=new LinkedHashMap<>();row.put("family",test.family());row.put("case",f.name());row.put("recipes",f.recipes().size());row.put("truth",test.truth());row.put("budget_ms",budget);row.put("rep",rep);row.put("cgse",result(g));if(cp!=null)row.put("cp_sat",result(cp));if(mip!=null)row.put("scip",result(mip));row.put("witness",test.witness());row.put("horizon",test.horizon());
        Files.writeString(root.resolve("results.jsonl"),json(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        save(f,json(row));System.out.println("CASE "+test.family()+" "+f.name()+" CGSE="+g.status()+" ms="+String.format("%.2f",g.ms())+(cp==null?"":" CP="+cp.status()+"/"+String.format("%.2f",cp.ms()))+(mip==null?"":" SCIP="+mip.status()+"/"+String.format("%.2f",mip.ms())));System.out.flush();
    }
    public static void main(String[] args)throws Exception{
        Loader.loadNativeLibraries();Locale.setDefault(Locale.ROOT);root=Path.of(args[1]);Files.createDirectories(root);out=root.resolve("cases");Files.createDirectories(out);generate();
        System.out.println("GENERATED "+tests.size());
        for(int i=0;i<5;i++){var f=NextProbe.bounded(4,2,2,30,42,0);run(f,1000,true);cpDag(f,1);scip(f,1);}
        if(args[0].equals("screen")){for(var test:tests)one(test,500,true,0);}
        else if(args[0].equals("confirm")){
            int repeats=args.length>3?Integer.parseInt(args[3]):3;
            Set<String> selected=new LinkedHashSet<>(Files.readAllLines(Path.of(args[2])));for(var test:tests)if(selected.contains(test.fixture().name()))for(int i=0;i<repeats;i++)one(test,3000,true,i);
        }else if(args[0].equals("list")){for(var t:tests)System.out.println(t.family()+" "+t.fixture().name());}
        else throw new IllegalArgumentException(args[0]);
    }
}
