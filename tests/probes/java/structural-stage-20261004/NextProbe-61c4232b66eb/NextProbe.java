package org.cgse.core;

import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class NextProbe {
    static long checks; static int regions, integrated, transfers, random;
    static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    static BigInteger bi(long x){return BigInteger.valueOf(x);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,256L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> recipe(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static void rowsAccept(List<ExactLinearProgram.Constraint> rows,BigInteger[] x){for(var row:rows){BigInteger sum=BigInteger.ZERO;for(var t:row.terms().entrySet())sum=sum.add(t.getValue().multiply(x[t.getKey()]));check(sum.compareTo(row.upper())<=0,"invalid count row");}}
    static List<GraphRecipe<String>> recipes(){return List.of(recipe("grow",Map.of("a",1L),Map.of("b",2L)),recipe("return",Map.of("b",1L),Map.of("a",1L)),recipe("deliver",Map.of("a",1L),Map.of("g",1L)),recipe("seed",Map.of("raw",1L),Map.of("a",1L)),recipe("unrelated",Map.of("ore",1L),Map.of("h",1L)));}
    static BigInteger[] candidate(List<GraphRecipe<String>> rs){return rs.stream().map(r->bi(r.id().equals("return")?2:r.id().equals("seed")?0:1)).toArray(BigInteger[]::new);}
    static void failureRegions()throws Exception{
        for(int seed=0;seed<80;seed++){
            var rs=new ArrayList<>(recipes());Collections.shuffle(rs,new Random(seed));var stock=Map.of("raw",1L,"ore",1L);var b=budget();
            try(var model=RecipeCountModel.forShell(rs,Map.of("g",bi(1),"h",bi(1)),stock,Set.of(),b)){
                BigInteger[] center=candidate(rs),lo=new BigInteger[rs.size()],hi=new BigInteger[lo.length];Arrays.fill(lo,bi(0));Arrays.fill(hi,bi(8));rowsAccept(model.constraints,center);
                BitSet region;
                try(var schedule=new CountSchedule<>(model,center,b)){while(!schedule.step()){}check(schedule.result()==CountSchedule.Result.DEAD,"seedless candidate not dead");region=schedule.failureRegion();}
                int source=-1,unrelated=-1;for(int i=0;i<rs.size();i++){if(rs.get(i).id().equals("seed"))source=i;if(rs.get(i).id().equals("unrelated"))unrelated=i;}
                check(region.get(source),"unused seed source omitted");check(!region.get(unrelated),"unrelated variable released");
                try(var repair=new CountNeighborhood(model.constraints,lo,hi,Arrays.stream(center).map(ExactRational::of).toArray(ExactRational[]::new),b).failureRegion(region)){
                    while(!repair.step()){}var x=repair.counts();check(x!=null&&!Arrays.equals(x,center),"no new candidate");check(x[unrelated].equals(center[unrelated]),"unrelated count changed");rowsAccept(model.constraints,x);
                    try(var schedule=new CountSchedule<>(model,x,b)){while(!schedule.step()){}check(schedule.result()==CountSchedule.Result.WITNESS,"seed repair not executable");}
                    // Rejecting one feasible point must permit a different local move.
                    repair.rejectFailureCandidate();while(!repair.step()){}if(repair.counts()!=null)rowsAccept(model.constraints,repair.counts());regions++;
                }
            }
            check(b.reservedBytes()==0,"region leak");
        }
        for(int seed=0;seed<40;seed++){
            var rs=new ArrayList<>(recipes());rs.remove(rs.size()-1);Collections.shuffle(rs,new Random(seed));var stock=Map.of("raw",1L);var b=budget();
            try(var model=RecipeCountModel.forShell(rs,Map.of("g",bi(1)),stock,Set.of(),b);var execution=new CountExecution<>(model,b);var branch=new IntegerCountBranch<>(model,execution,"g",1,stock,Map.of(),Set.of(),false,true,b,System.nanoTime(),List.of())){
                branch.initialized=true;branch.linearConstraints=new ArrayList<>(model.constraints);branch.lower=new BigInteger[rs.size()];branch.upper=new BigInteger[rs.size()];Arrays.fill(branch.lower,bi(0));Arrays.fill(branch.upper,bi(8));branch.counts=candidate(rs);branch.jumpCandidate=true;branch.jumpLate=true;branch.scheduling=new CountSchedule<>(model,branch.counts,b);
                for(int steps=0;steps<10000&&branch.state==IntegerCountBranch.State.OPEN;steps++)branch.run(1024,List.of(),List.of(),List.of(),null,()->false);
                check(branch.state==IntegerCountBranch.State.FOUND,"pipeline failed "+seed+" "+branch.state+" "+b.diagnostics());check(branch.failureNeighborhoodTried,"no failure handoff");PlanVerifier.verifyRuntimeInventory(branch.plan);integrated++;
            }
            check(b.reservedBytes()==0,"pipeline leak");
        }
    }
    static void viewFacts()throws Exception{
        for(int seed=0;seed<600;seed++){
            var rng=new Random(seed*101L);int n=2+seed%3;var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] low=new BigInteger[n],high=new BigInteger[n],known=new BigInteger[n];
            for(int i=0;i<n;i++){low[i]=bi(rng.nextInt(3)-1);high[i]=low[i].add(bi(2+rng.nextInt(4)));known[i]=low[i].add(bi(rng.nextInt(high[i].subtract(low[i]).intValue()+1)));}
            for(int r=0;r<3+seed%4;r++){var terms=new LinkedHashMap<Integer,BigInteger>();BigInteger rhs=bi(0);for(int i=0;i<n;i++){BigInteger a=bi(rng.nextInt(11)-5);if(a.signum()!=0)terms.put(i,a);rhs=rhs.add(a.multiply(known[i]));}if(r==0&&seed%2==0)StrideProbe.equation(rows,terms,rhs);else rows.add(new ExactLinearProgram.Constraint(terms,rhs.add(bi(rng.nextInt(4)))));}
            var expected=StrideProbe.enumerate(rows,low,high,p->p);var b=budget();if(seed%5==0)b.proofJournal(new CountProof.Journal(2L<<20));
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){
                reduction.retainStrideView();while(!reduction.step()){}views.compileLight();views.addReduced(reduction);
                for(var view:views.available()){
                    var domain=views.domains(view);
                    try(var search=new CountLcg(view.rows(),domain.lower(),domain.upper(),b,4096)){
                        while(!search.step()){}check(!search.infeasible()||expected.isEmpty(),"bad local proof "+seed);
                        if(!search.infeasible())views.publishBounds(view,search.rootLower(),search.rootUpper());
                    }
                }
                for(var view:views.available()){
                    var domain=views.domains(view);if(domain.version()>0)transfers++;
                    var actual=StrideProbe.enumerate(view.rows(),domain.lower(),domain.upper(),view::restore);
                    check(actual.equals(expected),"shared bounds removed solution "+seed+" "+view.name());
                }
                // Another request's view cannot publish even if dimensions and rows match.
                try(var other=CountModelViews.create(rows,low,high,b)){
                    var foreign=other.available().get(0);BigInteger[] wrong=low.clone();Arrays.fill(wrong,bi(1000000));views.publishBounds(foreign,wrong,high);
                    for(var view:views.available()){var d=views.domains(view);check(StrideProbe.enumerate(view.rows(),d.lower(),d.upper(),view::restore).equals(expected),"foreign scope entered");}
                }
                var altered=new ArrayList<>(rows);altered.add(new ExactLinearProgram.Constraint(Map.of(0,bi(1)),low[0]));
                try(var wrong=new CountReduction(altered,low,high,b)){while(!wrong.step()){}boolean refused=false;try{views.addReduced(wrong);}catch(IllegalArgumentException good){refused=true;}check(refused,"restricted reduction accepted");}
            }
            check(b.reservedBytes()==0,"view leak "+seed);random++;
        }
        check(transfers>300,"no real fact sharing");
        for(var semantics:List.of(CountModelViews.Semantics.RESTRICTED,CountModelViews.Semantics.HINT)){
            var b=budget();var low=new BigInteger[]{bi(0)};var high=new BigInteger[]{bi(1)};
            try(var views=CountModelViews.create(List.of(),low,high,b)){
                var field=CountModelViews.class.getDeclaredField("views");field.setAccessible(true);
                @SuppressWarnings("unchecked")var list=(List<CountModelViews.View>)field.get(views);
                list.clear();var restricted=new CountModelViews.View("restricted",List.of(new ExactLinearProgram.Constraint(Map.of(),bi(-1))),low,high,new CountModelViews.Shape(1,0,1,1),null,semantics,null);list.add(restricted);
                views.publishBounds(restricted,new BigInteger[]{bi(1)},new BigInteger[]{bi(0)});check(views.domains(restricted).version()==0,"restricted fact leaked");
                try(var search=new CountViewSearch(views,b)){search.resume(100000);while(!search.step()){}check(!search.infeasible(),"restricted UNSAT leaked");}
            }check(b.reservedBytes()==0,"restricted leak");
        }
    }
    static void lifecycle(){
        var rows=new ArrayList<ExactLinearProgram.Constraint>();StrideProbe.equation(rows,Map.of(0,bi(6),1,bi(10)),bi(100));var low=new BigInteger[]{bi(0),bi(0)};var high=new BigInteger[]{bi(100),bi(100)};
        for(int limit=1;limit<=240;limit++){
            final int cutoff=limit*3;var checks=new AtomicInteger();var b=new PlanningBudget(0,2_000_000,32L<<20,()->checks.incrementAndGet()>=cutoff,System::nanoTime);
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){
                reduction.retainStrideView();while(!reduction.step()){}if(views!=null){views.compileLight();views.addReduced(reduction);try(var search=new CountViewSearch(views,b)){search.resume(65536);while(!search.step()){} }}
            }catch(java.util.concurrent.CancellationException|PlanningBudget.Exhausted good){}
            check(b.reservedBytes()==0,"cancel leak "+limit+" "+b.reservedBytes());
        }
        for(int limit=1;limit<=80;limit++){
            var b=new PlanningBudget(0,2_000_000,limit*1024L,()->false,System::nanoTime);
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){
                reduction.retainStrideView();while(!reduction.step()){}if(views!=null){views.compileLight();views.addReduced(reduction);try(var search=new CountViewSearch(views,b)){search.resume(65536);while(!search.step()){} }}
            }catch(PlanningBudget.Exhausted good){}
            check(b.reservedBytes()==0,"cap leak "+limit+" "+b.reservedBytes());
        }
    }
    public static void main(String[]args)throws Exception{failureRegions();viewFacts();lifecycle();var out=Map.of("checks",checks,"regions",regions,"branch_repairs",integrated,"random_models",random,"fact_transfers",transfers,"lifecycle",320);Files.writeString(Path.of(args[0],"next-probe.json"),new Gson().toJson(out));System.out.println(out);}
}
