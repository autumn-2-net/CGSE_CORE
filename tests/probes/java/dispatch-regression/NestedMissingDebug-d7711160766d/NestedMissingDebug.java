import org.cgse.core.*;
import java.util.*;
public class NestedMissingDebug {
 public static void main(String[] args)throws Exception{var c=ComplexCycleStress.nested(60,2,1,1);var stock=new HashMap<>(c.stock());stock.compute("R",(k,v)->v-1);
 var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);var w=new GraphPlanningWork<>(new GraphCompiler<>(c.recipes()),"Q0",1,stock,true,true,b).catalysts(CatalystPolicy.MINIMAL);
 var f=w.getClass().getDeclaredField("phase");f.setAccessible(true);int phase=-1;long before=0;
 while(!w.step()){int p=f.getInt(w);if(p!=phase){System.out.println("PHASE "+phase+" work="+(b.nodes()-before)+" ->"+p);before=b.nodes();phase=p;}}
 System.out.println("RESULT "+w.result().result()+" work="+b.nodes()+" phase="+phase+" missing="+w.result().missingExact());}
}
