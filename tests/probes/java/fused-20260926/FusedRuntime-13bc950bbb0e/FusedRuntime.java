import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.cgse.core.*;

public class FusedRuntime extends DumpRuntimeReplay {
    public static void main(String[] args)throws Exception{
        int completed=0;var all=new ArrayList<JsonObject>();
        for(var path:Files.list(Path.of(args[0])).sorted().toList()){
            var data=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String name=data.get("name").getAsString();
            if(!data.get("truth").getAsString().equals("SAT")||name.startsWith("counter_")&&!name.startsWith("counter_b10"))continue;
            var recipes=new ArrayList<GraphRecipe<String>>();
            for(var value:data.getAsJsonArray("recipes")){
                var r=value.getAsJsonObject();String id=r.get("id").getAsString();
                recipes.add(new GraphRecipe<>(id,id,amounts(r.getAsJsonObject("inputs")).entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),amounts(r.getAsJsonObject("outputs"))));
            }
            var budget=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
            var p=new GraphPlanner<>(new GraphCompiler<>(recipes)).plan(data.get("target").getAsString(),data.get("amount").getAsLong(),amounts(data.getAsJsonObject("stock")),false,true,budget);
            check(p.feasible(),"Failed planning "+name+" "+p.result());
            for(int mode=0;mode<3;mode++){
                var result=new Machines(p,mode).execute();result.addProperty("case",name);all.add(result);
                check(result.get("state").getAsString().equals("COMPLETED"),"Incomplete execution "+result);
                completed++;
            }
        }
        Files.writeString(Path.of(args[1]),String.join("\n",all.stream().map(JSON::toJson).toList()));
        System.out.println("FUSED RUNTIME completed="+completed+" checks="+checks+" modes=sync,delayed_restore,partial_restore");
    }
}
