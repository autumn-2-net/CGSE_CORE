import org.cgse.core.*;
import java.util.*;
public class PlanningTrace {
    static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    public static void main(String[] args)throws Exception{
        int d=args.length>0?Integer.parseInt(args[0]):60;
        var b=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
        var recipes=DeepLongBoundary.nested(d);
        var work=new GraphPlanningWork<>(new GraphCompiler<>(recipes),"Q0",Long.MAX_VALUE,Map.of("C0",1L),true,true,b).catalysts(CatalystPolicy.MINIMAL);
        Object last=null;int lastPhase=-1;Object prevExpansion=null;
        while(!work.step()){
            Object a=get(work,"allocating");if(a==null)continue;
            int phase=(Integer)get(a,"phase");if(a!=last||phase!=lastPhase){
                System.out.println("ALLOC phase="+phase+" work="+b.nodes());last=a;lastPhase=phase;
                if(phase==2&&prevExpansion!=null){
                    System.out.println("EXP result="+(get(prevExpansion,"result")!=null)+" frames="+((Collection)get(prevExpansion,"frames")).size()+" path="+((Collection)get(prevExpansion,"path")).size()+" allowance="+get(prevExpansion,"allowance")+" start="+get(prevExpansion,"started"));
                    Object s=get(prevExpansion,"result");
                    if(s instanceof PlanStep step){
                        var unique=Collections.newSetFromMap(new IdentityHashMap<PlanStep,Boolean>());var q=new ArrayDeque<PlanStep>();q.push(step);int refs=0;
                        while(!q.isEmpty()){var x=q.pop();if(unique.add(x)){if(x instanceof PlanStep.Sequence sq){refs+=sq.children().size();q.addAll(sq.children());}else if(x instanceof PlanStep.Repeat rp){q.push(rp.body());refs++;}}}
                        System.out.println("AST unique="+unique.size()+" refs="+refs);
                    }
                }
            }
            var e=get(a,"expansion");if(e!=null)prevExpansion=e;
        }
        System.out.println("DONE result="+work.result().result()+" work="+b.nodes()+" detail="+b.failureDetail());
    }
}
