import java.util.*;
import java.math.BigInteger;
import org.cgse.core.*;
public class StartupPrototype {
    record Block(PlanStep program,SequenceSummary<String> s){}
    static Block program(List<GraphRecipe<String>> rs,Map<String,BigInteger> counts,Map<String,Long> stock,int mode) {
        Map<String,BigInteger> net=new LinkedHashMap<>();for(var r:rs)SequenceSummary.recipe(r).delta().forEach((k,v)->net.merge(k,v.multiply(counts.getOrDefault(r.id(),BigInteger.ZERO)),BigInteger::add));
        Set<String> internal=new HashSet<>();for(var r:rs)internal.addAll(r.outputs().keySet());internal.removeIf(k->net.getOrDefault(k,BigInteger.ZERO).signum()<0);
        int bits=counts.values().stream().mapToInt(BigInteger::bitLength).max().orElse(0);Block prev=null;
        for(int bit=bits-1;bit>=0;bit--){
            List<Block> pending=new ArrayList<>();if(prev!=null)pending.add(new Block(new PlanStep.Repeat(prev.program,2),prev.s.repeat(2)));
            for(var r:rs)if(counts.getOrDefault(r.id(),BigInteger.ZERO).testBit(bit))pending.add(new Block(new PlanStep.Batch(r.id(),1),SequenceSummary.recipe(r)));
            List<PlanStep> out=new ArrayList<>();SequenceSummary<String> summary=SequenceSummary.empty();
            while(!pending.isEmpty()){
                Block best=null;long score=Long.MAX_VALUE;
                for(var b:pending){long absent=0,kinds=0;var next=summary.then(b.s);
                    for(String k:internal){if(next.required(k).signum()>0)kinds++;if(next.required(k).compareTo(BigInteger.valueOf(stock.getOrDefault(k,0L)))>0)absent++;}
                    long s=mode==0?0:mode==1?absent*10000+kinds:kinds*10000+absent;
                    if(s<score){score=s;best=b;}
                }
                summary=summary.then(best.s);out.add(best.program);pending.remove(best);
            }
            prev=new Block(new PlanStep.Sequence(out),summary);
        }
        return prev;
    }
    public static void main(String[]args)throws Exception{
        DumpReplay.main(new String[]{".local/ae-dumps/sky2-all.json",".local/cp-sat-recheck-20260926/empty.jsonl","1","__none__"});
        var c=DumpReplay.compiler("k747");var b=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(c,"k747",Long.MAX_VALUE,DumpReplay.stock,DumpReplay.external,Map.of(),true,true,b).catalysts(CatalystPolicy.MINIMAL);while(!w.step()){}var p=w.result();
        System.out.println("ORIGINAL "+p.result()+" seeds="+p.seeds());
        var graph=c.compile("k747",Map.of(),Set.of(),new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime));
        for(var region:graph.regions())if(region.cyclic()&&region.recipes().size()>6){
            for(int mode=0;mode<3;mode++){
                long start=System.nanoTime();var out=program(region.recipes(),p.patternTimesExact(),DumpReplay.stock,mode);Map<String,BigInteger> seeds=new LinkedHashMap<>();Set<String> produced=new HashSet<>();region.recipes().forEach(r->produced.addAll(r.outputs().keySet()));for(String k:produced)if(out.s.delta(k).signum()>=0&&out.s.required(k).signum()>0)seeds.put(DumpReplay.labels.getOrDefault(k,k),out.s.required(k));
                System.out.println("MODE "+mode+" ms="+(System.nanoTime()-start)/1e6+" seeds="+seeds);
            }
        }
    }
}
