package org.cgse.core;
import org.cgse.core.*;
import java.util.*;
public final class WideCountOracle {
    static int encode(int[] values){int r=0;for(int i=values.length-1;i>=0;i--)r=(r<<4)|values[i];return r;}
    static int[] decode(int v,int n){int[] a=new int[n];for(int i=0;i<n;i++){a[i]=v&15;v>>>=4;}return a;}
    static boolean reachable(List<GraphRecipe<String>> recipes,int[] stock,int n){
        var seen=new HashSet<Integer>();var todo=new ArrayDeque<Integer>();seen.add(encode(stock));todo.add(encode(stock));
        while(!todo.isEmpty()){
            int[] held=decode(todo.removeFirst(),stock.length);if(held[held.length-1]>=n)return true;
            for(var r:recipes){
                boolean ready=true;for(var e:r.inputs().entrySet())if(held[Integer.parseInt(e.getKey())]<e.getValue()){ready=false;break;}
                if(!ready)continue;var next=held.clone();
                r.inputs().forEach((k,v)->next[Integer.parseInt(k)]-=v.intValue());r.outputs().forEach((k,v)->next[Integer.parseInt(k)]+=v.intValue());
                if(seen.add(encode(next)))todo.addLast(encode(next));
            }
        }return false;
    }
    static GraphPlan<String> solve(List<GraphRecipe<String>> recipes,int[] initial,int n){
        var stock=new HashMap<String,Long>();for(int i=0;i<initial.length;i++)if(initial[i]>0)stock.put(""+i,(long)initial[i]);
        var budget=new PlanningBudget(0,2_000_000,128L<<20,()->false,System::nanoTime);
        var work=new IntegerCountSearch<>(new GraphCompiler<>(recipes),""+(initial.length-1),n,stock,Map.of(),Set.of(),Set.of(),false,false,budget,System.nanoTime());
        while(!work.step()){}proved=work.infeasible();return work.result();
    }
    static boolean proved;

    public static void main(String[] args){
        int count=args.length>0?Integer.parseInt(args[0]):2000,possible=0,missed=0;
        var rng=new Random(90525117);
        for(int sample=0;sample<count;sample++){
            int size=4+rng.nextInt(4),total=3+rng.nextInt(8),n=1+rng.nextInt(total);int[] stock=new int[size];
            for(int i=0;i<total;i++)stock[rng.nextInt(size-1)]++;
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(int j=0,rs=8+rng.nextInt(21);j<rs;j++){
                var in=new HashMap<String,Long>();var out=new HashMap<String,Long>();
                for(int k=0,tokens=1+rng.nextInt(4);k<tokens;k++){in.merge(""+rng.nextInt(size),1L,Long::sum);out.merge(""+rng.nextInt(size),1L,Long::sum);}
                recipes.add(new GraphRecipe<>("r"+j,"r"+j,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out));
            }
            boolean ref=reachable(recipes,stock,n);var p=solve(recipes,stock,n);
            if(p!=null){
                if(!ref)throw new AssertionError("Unsound "+sample);PlanVerifier.verify(p);
            }else if(ref){
                if(proved)throw new AssertionError("FALSE INFEASIBILITY sample="+sample);
                missed++;System.out.println("MISS sample="+sample+" stock="+Arrays.toString(stock)+" amount="+n+" status="+"UNKNOWN");
                
            }
            if(ref)possible++;
            if(sample%100==99)System.out.println("PROGRESS samples="+(sample+1)+" reachable="+possible+" misses="+missed);
        }
        System.out.println("ORACLE total="+count+" reachable="+possible+" missed="+missed+" unsound=0");
    }
}
