import java.util.*;
import java.math.BigInteger;
import org.cgse.core.*;
public class BootstrapPrototype {
    static Map<String,Set<String>> introduced=new HashMap<>();
    static List<GraphRecipe<String>> closure(List<GraphRecipe<String>> rs,Set<String> base,Set<String> goals){
        introduced.clear();Set<String> have=new HashSet<>(base);List<GraphRecipe<String>> order=new ArrayList<>();Set<String> fired=new HashSet<>();boolean changed;
        do{changed=false;for(var r:rs)if(!fired.contains(r.id())&&have.containsAll(r.inputs().keySet())){fired.add(r.id());if(!have.containsAll(r.outputs().keySet())){var fresh=new HashSet<>(r.outputs().keySet());fresh.removeAll(have);introduced.put(r.id(),fresh);order.add(r);have.addAll(r.outputs().keySet());changed=true;}}}while(changed);
        return have.containsAll(goals)?order:null;
    }
    static List<PlanStep> prefix(List<GraphRecipe<String>> order,Map<String,BigInteger> wanted,Set<String> base){
        var need=new HashMap<>(wanted);base.forEach(need::remove);var out=new ArrayList<PlanStep>();
        for(int i=order.size()-1;i>=0;i--){var r=order.get(i);BigInteger n=BigInteger.ZERO;
            for(var e:r.outputs().entrySet())if(introduced.get(r.id()).contains(e.getKey()))n=n.max(CheckedAmounts.ceilDiv(need.getOrDefault(e.getKey(),BigInteger.ZERO),BigInteger.valueOf(e.getValue())));
            if(n.signum()==0)continue;out.add(PlanStep.batch(r.id(),n));
            final BigInteger count=n;
            r.outputs().forEach((k,v)->need.compute(k,(key,old)->(old==null?BigInteger.ZERO:old).subtract(BigInteger.valueOf(v).multiply(count)).max(BigInteger.ZERO)));
            r.inputs().forEach((k,v)->need.merge(k,BigInteger.valueOf(v).multiply(count),BigInteger::add));
        }
        Collections.reverse(out);return out;
    }
    static BigInteger none(){return BigInteger.ZERO;}
    public static void main(String[]args)throws Exception{
        DumpReplay.main(new String[]{".local/ae-dumps/sky2-all.json",".local/cp-sat-recheck-20260926/empty.jsonl","1","__none__"});
        var c=DumpReplay.compiler("k747");var b=new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime);
        var w=new GraphPlanningWork<>(c,"k747",Long.MAX_VALUE,DumpReplay.stock,DumpReplay.external,Map.of(),true,true,b).catalysts(CatalystPolicy.MINIMAL);while(!w.step()){}var p=w.result();
        var graph=c.compile("k747",Map.of(),Set.of(),new PlanningBudget(30000,20_000_000,256L<<20,()->false,System::nanoTime));
        for(var region:graph.regions())if(region.cyclic()&&region.recipes().size()>6){
            var rs=region.recipes();var original=StartupPrototype.program(rs,p.patternTimesExact(),DumpReplay.stock,0);
            Set<String> produced=new HashSet<>(),base=new HashSet<>(DumpReplay.external);Map<String,GraphRecipe<String>> by=new HashMap<>();rs.forEach(r->{produced.addAll(r.outputs().keySet());base.addAll(r.inputs().keySet());by.put(r.id(),r);});base.removeAll(produced);for(String k:produced)if(original.s().required(k).signum()>0&&original.s().delta(k).signum()<0)base.add(k);
            List<String> seeds=new ArrayList<>();Map<String,BigInteger>wanted=new HashMap<>();for(String k:produced)if(original.s().required(k).signum()>0&&original.s().delta(k).signum()>=0){seeds.add(k);wanted.put(k,original.s().required(k));}
            int best=seeds.size();System.out.println("BEFORE seeds="+seeds.size());
            List<Integer> masks=new ArrayList<>();for(int i=0;i<(1<<seeds.size());i++)masks.add(i);masks.sort(Comparator.comparingInt(Integer::bitCount));
            for(int mask:masks){if(Integer.bitCount(mask)>=best)continue;Set<String> initial=new HashSet<>(base);for(int i=0;i<seeds.size();i++)if((mask&(1<<i))!=0)initial.add(seeds.get(i));var order=closure(rs,initial,wanted.keySet());if(order==null)continue;
                var steps=prefix(order,wanted,initial);steps.add(original.program());var summary=SequenceSummary.of(new PlanStep.Sequence(steps),by);
                Map<String,BigInteger> after=new LinkedHashMap<>();boolean consumed=false;for(String k:produced)if(summary.required(k).signum()>0&&summary.delta(k).signum()>=0)after.put(DumpReplay.labels.getOrDefault(k,k),summary.required(k));for(int i=0;i<seeds.size();i++)if((mask&(1<<i))!=0&&summary.delta(seeds.get(i)).signum()<0)consumed=true;
                if(after.size()<best&&!consumed){best=after.size();System.out.println("AFTER seeds="+after+" prefix="+(steps.size()-1));}
                else System.out.println("DECLINE chosen="+Integer.bitCount(mask)+" actual="+after.size()+" consumed="+consumed);
            }
        }
    }
}
