import org.cgse.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BenchmarkBridge {
    record Case(String name,String target,long amount,Map<String,Long> stock,List<GraphRecipe<String>> recipes){}
    static List<Case> cases=new ArrayList<>();
    static String text(DataInputStream in)throws Exception{return new String(in.readNBytes(in.readInt()),StandardCharsets.UTF_8);}
    static Map<String,Long> values(DataInputStream in)throws Exception{var x=new LinkedHashMap<String,Long>();for(int n=in.readInt();n>0;n--)x.put(text(in),in.readLong());return x;}
    public static int load(String path)throws Exception{
        cases.clear();try(var in=new DataInputStream(Files.newInputStream(Path.of(path)))){
            for(int n=in.readInt();n>0;n--){String name=text(in),target=text(in);long amount=in.readLong();text(in);var stock=values(in);var recipes=new ArrayList<GraphRecipe<String>>();
                for(int j=in.readInt();j>0;j--){String id=text(in);var inputs=values(in);var outputs=values(in);recipes.add(new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),outputs));}
                cases.add(new Case(name,target,amount,stock,recipes));
            }
        }return cases.size();
    }
    public static Object[] run(int index){
        var c=cases.get(index);var budget=new PlanningBudget(3000,20_000_000,256L<<20,()->false,System::nanoTime);
        long start=System.nanoTime();String result;
        try{result=new GraphPlanner<>(new GraphCompiler<>(c.recipes)).plan(c.target,c.amount,c.stock,false,true,budget).result().toString();}
        catch(PlanningBudget.Exhausted e){result=e.toString();}
        return new Object[]{c.name,result,(System.nanoTime()-start)/1e6,budget.nodes()};
    }
}
