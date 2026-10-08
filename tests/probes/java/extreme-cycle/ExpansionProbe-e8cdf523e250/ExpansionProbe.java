import org.cgse.core.*;
import java.math.BigInteger;
import java.util.*;
public class ExpansionProbe {
    public static void main(String[] args)throws Exception{
        var cls=Class.forName("org.cgse.core.DemandExpansion");
        var ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var step=cls.getDeclaredMethod("step");step.setAccessible(true);
        for(int d:args.length>1?new int[]{Integer.parseInt(args[1])}:new int[]{8,12,16,20,24,40,60}){
            var c=ComplexCycleStress.nested(d,2,1,1);
            boolean free=args.length>0;
            var catalog=free?DeepLongBoundary.nested(d):c.recipes();
            var compiler=new GraphCompiler<>(catalog);
            var recipes=new LinkedHashMap<String,GraphRecipe<String>>();
            var todo=new ArrayDeque<String>();todo.add(c.target());var seen=new HashSet<String>();
            while(!todo.isEmpty()) {var key=todo.removeFirst();if(seen.add(key))for(var r:compiler.producers(key)){recipes.putIfAbsent(r.id(),r);todo.addAll(r.inputs().keySet());}}
            var stock=new HashMap<String,BigInteger>();(free?Map.of("C0",1L):c.stock()).forEach((k,v)->stock.put(k,BigInteger.valueOf(v)));
            var b=new PlanningBudget(5000,10_000_000,128L<<20,()->false,System::nanoTime);
            var exp=ctor.newInstance(recipes,stock,Map.of(c.target(),BigInteger.valueOf(free?Long.MAX_VALUE:1)),b);
            while(!(Boolean)step.invoke(exp)){}
            System.out.print("EXP depth="+d+" work="+b.nodes());
            for(String name:List.of("failed","allowance","result","frames","path")){
                var f=cls.getDeclaredField(name);f.setAccessible(true);var value=f.get(exp);
                System.out.print(" "+name+"="+(value instanceof Collection x?x.size():name.equals("result")?value!=null:value));
            }
            System.out.println();
            if(args.length>1){var f=cls.getDeclaredField("result");f.setAccessible(true);System.out.println(f.get(exp));}
        }
    }
}
