package org.cgse.core;
import java.math.BigInteger;
import java.util.*;
public final class OrderRegression {
    static int cases,verified,unknown,missing;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static GraphPlan<String> solve(List<GraphRecipe<String>> rs,int amount,Map<String,Long> stock,boolean force){
        var budget=new PlanningBudget(0,3_000_000,128L<<20,()->false,System::nanoTime);
        return new GraphPlanner<>(new GraphCompiler<>(rs)).plan("C",amount,stock,false,force,budget);
    }
    static void verify(GraphPlan<String> p,Map<String,Long> stock,boolean force){
        PlanVerifier.verify(p);
        var held=new HashMap<>(p.initial());long produced=0;
        held.forEach((k,v)->check(v<=stock.getOrDefault(k,0L),"overdraw "+p.initial()+" stock="+stock));
        var cursor=new PlanCursor(p.steps());int runs=0;
        for(var batch=cursor.current();batch!=null;batch=cursor.current()){
            var recipe=p.recipes().get(batch.recipe());
            for(long i=0;i<batch.runs();i++){
                check(++runs<10000,"unexpected long probe witness");
                for(var in:recipe.inputs().entrySet())check(held.getOrDefault(in.getKey(),0L)>=in.getValue(),"cannot execute "+recipe.id()+" held="+held);
                recipe.inputs().forEach((k,v)->held.merge(k,-v,Long::sum));recipe.outputs().forEach((k,v)->held.merge(k,v,Long::sum));
                produced+=recipe.executionOutputs().getOrDefault("C",0L);
            }
            cursor.dispatched(batch.runs());
        }
        check(held.getOrDefault("C",0L)>=p.amount()+p.seeds().getOrDefault("C",0L),"short delivery "+held);
        for(var seed:p.seeds().entrySet())check(held.getOrDefault(seed.getKey(),0L)>=seed.getValue(),"seed lost");
        check(!force||produced>=p.amount(),"fresh output short "+produced+" / "+p.amount());
        verified++;
    }
    record State(int a,int b,int c,int fresh){}
    static boolean oracle(Map<String,Long> stock,int amount,boolean force){
        int a=stock.getOrDefault("A",0L).intValue(),b=stock.getOrDefault("B",0L).intValue(),c=stock.getOrDefault("C",0L).intValue();
        var first=new State(a,b,c,0);var seen=new HashSet<State>();var todo=new ArrayDeque<State>();seen.add(first);todo.add(first);
        while(!todo.isEmpty()){
            var s=todo.removeFirst();
            if(s.c>=amount&&(!force||s.fresh>=amount&&(s.a>a||s.b>b||s.c>c)))return true;
            if(s.a>=2&&s.b>=3){var n=new State(s.a-1,s.b-3,s.c+3,Math.min(amount,s.fresh+3));if(seen.add(n))todo.add(n);}
            if(s.c>=1){var n=new State(s.a+1,s.b,s.c-1,s.fresh);if(seen.add(n))todo.add(n);}
        }return false;
    }
    static void randomized(){
        var rng=new Random(2026100301L);
        for(int sample=0;sample<180;sample++){
            var stock=Map.of("A",(long)rng.nextInt(4),"B",(long)rng.nextInt(16),"C",(long)rng.nextInt(7));int amount=1+rng.nextInt(12);
            for(boolean force:new boolean[]{false,true}){
                boolean ref=oracle(stock,amount,force);
                for(int order=0;order<3;order++){
                    var rs=OrderProbe.recipes(order==0);if(order>0)Collections.shuffle(rs,rng);
                    var p=solve(rs,amount,stock,force);cases++;
                    check(!p.feasible()||ref,"false feasible sample="+sample+" force="+force+" stock="+stock+" amount="+amount+" first="+rs.get(0).id()+" result="+p.result()+" missing="+p.missing()+" counts="+p.patternTimes());
                    if(ref&&!p.feasible()){if(p.missing().isEmpty())unknown++;else missing++;}
                    System.out.println("CASE "+sample+" "+force+" "+order+" "+ref+" "+p.result()+" "+stock+" "+amount);
                    if(p.feasible())verify(p,stock,force);
                }
            }
            if(sample%30==29)System.out.println("RANDOM samples="+(sample+1)+" cases="+cases+" verified="+verified);
        }System.out.println("MATRIX cases="+cases+" verified="+verified+" oracle_possible_unknown="+unknown+" oracle_possible_missing="+missing);
    }
    static void countPath(){
        for(boolean force:new boolean[]{false,true})for(int amount=1;amount<=8;amount++)for(boolean order:new boolean[]{false,true}){
            var stock=Map.of("A",1L,"B",3L,"C",1L);var budget=new PlanningBudget(0,3_000_000,128L<<20,()->false,System::nanoTime);
            try(var work=new IntegerCountSearch<>(new GraphCompiler<>(OrderProbe.recipes(order)),"C",amount,stock,Map.of(),Set.of(),Set.of(),false,force,budget,System.nanoTime())){
                while(!work.step()){}var p=work.result();boolean ref=oracle(stock,amount,force);
                check(!ref||!work.infeasible(),"false count infeasibility amount="+amount+" force="+force);
                check(p==null||ref,"invalid count witness amount="+amount+" force="+force);
                if(!force)check((p!=null)==ref,"nonforce count completeness");
                if(p!=null)verify(p,stock,force);cases++;
            }
        }
        System.out.println("COUNT PATH passed");
    }
    static void loops(){
        for(boolean fuel:new boolean[]{false,true}){
            var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",fuel?Map.of("A",1L,"fuel",1L):Map.of("A",1L),Map.of("C",1L)));
            var stock=Map.of("C",100L,"fuel",100L);
            var p=solve(rs,3,stock,true);
            check(!p.feasible(),"unproductive turnover accepted fuel="+fuel+" counts="+p.patternTimes());cases++;
            p=solve(rs,3,stock,false);check(p.feasible(),"stored output nonforce");verify(p,stock,false);cases++;
        }
        var productive=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",Map.of("A",1L,"fuel",1L),Map.of("C",1L,"bonus",1L)));
        var byId=new LinkedHashMap<String,GraphRecipe<String>>();productive.forEach(r->byId.put(r.id(),r));
        var witness=PlanStep.repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("out",1),new PlanStep.Batch("back",1))),BigInteger.valueOf(3));
        var budget=new PlanningBudget(0,100_000,()->false);
        try(var candidate=new AllocationSearch.Candidate<>(witness,byId,"C",3,Map.of("C",100L,"fuel",3L),Map.of(),Set.of(),false,true,false,budget,System.nanoTime())){
            while(!candidate.step()){}check(candidate.plan==null,"baseline zero-target-gain protection changed");cases++;
        }
        var rs=List.of(OrderProbe.recipe("make",Map.of("raw",1L),Map.of("C",1L)));
        var p=solve(rs,3,Map.of("C",100L,"raw",3L),true);check(p.feasible()&&p.patternTimes().get("make")==3,"large existing target skips fresh production");verify(p,Map.of("C",100L,"raw",3L),true);cases++;
        System.out.println("LOOPS AND STORED TARGET passed");
    }
    static void mixedAndLowBudget(){
        var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",Map.of("A",1L,"fuel",1L),Map.of("C",1L)),OrderProbe.recipe("make",Map.of("raw",1L),Map.of("C",1L)));
        var stock=Map.of("C",100L,"fuel",100L,"raw",1L);
        var p=solve(rs,3,stock,true);check(!p.feasible(),"real output plus wasted circulation accepted");cases++;
        var map=new LinkedHashMap<String,GraphRecipe<String>>();rs.forEach(r->map.put(r.id(),r));
        for(long repetitions:new long[]{2,100,Long.MAX_VALUE})for(long work:new long[]{4000,100000}){
            var program=new PlanStep.Sequence(List.of(new PlanStep.Batch("out",repetitions),new PlanStep.Batch("back",repetitions),new PlanStep.Batch("make",1)));
            var budget=new PlanningBudget(0,work,()->false);
            try(var candidate=new AllocationSearch.Candidate<>(program,map,"C",3,Map.of("C",Long.MAX_VALUE,"fuel",Long.MAX_VALUE,"raw",1L),Map.of(),Set.of(),false,true,false,budget,System.nanoTime())){
                while(!candidate.step()){}check(candidate.plan==null,"mixed circulation low-budget candidate accepted n="+repetitions+" work="+work);cases++;
            }
        }
        System.out.println("MIXED AND LOW-BUDGET LARGE LOOPS passed");
    }
    static void modelChecks(){
        var compiler=new GraphCompiler<>(List.of(OrderProbe.recipe("make",Map.of("raw",1L),Map.of("C",1L))));
        var budget=new PlanningBudget(0,100000,128L<<20,()->false,System::nanoTime);
        var model=RecipeCountModel.create(compiler,"C",3,Map.of("C",100L,"raw",3L),Map.of(),Set.of(),Set.of(),true,budget);
        check(model.goal("C").equals(BigInteger.valueOf(3)),"stored target retained as mandatory reserve");
        check(model.constraints.size()==model.rowKeys.size()+1,"fresh-production row layout");
        var cert=new ExactRational[model.constraints.size()];Arrays.fill(cert,ExactRational.ZERO);cert[cert.length-1]=ExactRational.ONE;
        check(model.certificate(cert).isEmpty(),"production row published as resource certificate");
        check(budget.reservedBytes()>0,"model uncharged");model.close();check(budget.reservedBytes()==0,"fresh model memory leak");
        var tight=new PlanningBudget(0,100000,(512L<<10)+128L*2+128L*4,()->false,System::nanoTime);
        var skipped=RecipeCountModel.create(compiler,"C",3,Map.of("C",100L,"raw",3L),Map.of(),Set.of(),Set.of(),true,tight);
        check(skipped==null,"optional model must skip on production-row memory boundary");
        check(tight.reservedBytes()==0,"failure during production-row reservation leaked memory");
        cases+=5;System.out.println("MODEL ROWS AND MEMORY passed");
    }
    public static void main(String[]args){modelChecks();countPath();loops();mixedAndLowBudget();randomized();System.out.println("PASS cases="+cases+" sequentially_verified="+verified);}
}
