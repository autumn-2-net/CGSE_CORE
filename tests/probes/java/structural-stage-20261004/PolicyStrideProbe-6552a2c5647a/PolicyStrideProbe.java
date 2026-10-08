package org.cgse.core;
import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class PolicyStrideProbe {
    static long checks;static int separator,mitm,gateCases,lifecycle;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static BigInteger bi(long value){return BigInteger.valueOf(value);}
    static PlanningBudget budget(){return new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);}
    static void eq(List<ExactLinearProgram.Constraint> rows,Map<Integer,BigInteger> terms,BigInteger value){rows.add(new ExactLinearProgram.Constraint(terms,value));var opposite=new LinkedHashMap<Integer,BigInteger>();terms.forEach((k,v)->opposite.put(k,v.negate()));rows.add(new ExactLinearProgram.Constraint(opposite,value.negate()));}
    static void policy(){
        for(int sample=0;sample<2000;sample++){
            var p=new CountPortfolioPolicy();var a=p.add(8,"lcg");var b=p.add(8,"pb");var peer=p.add(8,"lcg");
            for(var arm:List.of(a,b,peer)){p.selected(arm);p.feedback(arm,4096,20);arm.waiting=0;}
            double unchanged=p.candidateEfficiency(b);p.candidateFeedback(a,4096,100_000,false,false);
            check(p.candidateEfficiency(b)==unchanged,"foreign family adjusted");check(p.candidateEfficiency(peer)<1,"retired source not transferred to family");check(p.select()==b,"downstream cost ignored");
            double censored=p.candidateEfficiency(a);p.candidateFeedback(a,4096,100_000,true,false);check(p.candidateEfficiency(a)<censored,"resolved failure ignored");
            var newcomer=p.add(8,"lcg");check(p.select()==newcomer,"new arms starved by family history");p.selected(newcomer);p.feedback(newcomer,4096,20);
            var r=new Random(sample*37L);var all=List.of(a,b,peer,newcomer);int[] used=new int[4];
            for(int step=0;step<120;step++){var arm=p.select();int id=all.indexOf(arm);check(id>=0,"retired selected");used[id]++;p.selected(arm);p.feedback(arm,1+r.nextInt(10000),r.nextInt(10));long q=p.quantum(arm,1+r.nextInt(100000));check(q>0&&q<=CountPortfolioPolicy.MAX_QUANTUM,"quantum overflow");}
            for(int count:used)check(count>0,"aging lost liveness");
            p.candidateFeedback(a,Long.MAX_VALUE,Long.MAX_VALUE,true,true);check(Double.isFinite(p.candidateEfficiency(a))&&p.candidateEfficiency(a)>=0&&p.candidateEfficiency(a)<=1,"large costs invalid");
        }
        var a=new CountPortfolioPolicy();var b=new CountPortfolioPolicy();var aa=a.add(1,"family");var bb=b.add(1,"family");a.candidateFeedback(aa,1,100,true,false);check(b.candidateEfficiency(bb)==1,"cross-request feedback");
    }
    static List<?> searches(CountViewSearch search)throws Exception{var f=CountViewSearch.class.getDeclaredField("searches");f.setAccessible(true);return (List<?>)f.get(search);}
    static String engine(Object search)throws Exception{var f=search.getClass().getDeclaredField("engine");f.setAccessible(true);return f.get(search).toString();}
    static void isolate(CountViewSearch search,String wanted)throws Exception{for(var s:searches(search))if(!engine(s).equals(wanted)){var d=s.getClass().getDeclaredField("done");d.setAccessible(true);d.set(s,true);var f=s.getClass().getDeclaredField("scheduling");f.setAccessible(true);((CountPortfolioPolicy.Arm)f.get(s)).retired=true;}}
    static int scouts(CountViewSearch search)throws Exception{int n=0;for(var s:searches(search))if(engine(s).equals("SEPARATOR")||engine(s).equals("MITM"))n++;return n;}
    static void specialist(int sample,boolean useMitm)throws Exception{
        var rows=new ArrayList<ExactLinearProgram.Constraint>();BigInteger[] low={bi(0),bi(0)},high;
        if(useMitm){BigInteger a=bi(1_000_003),b=bi(1_000_033),n=bi(80+sample%3);eq(rows,Map.of(0,a,1,b),a.multiply(b).multiply(n));high=new BigInteger[]{b.multiply(n),a.multiply(n)};rows.add(new ExactLinearProgram.Constraint(Map.of(0,bi(2),1,bi(3)),b.multiply(bi(2)).multiply(n.subtract(bi(20))).add(a.multiply(bi(60)))));}
        else{eq(rows,Map.of(0,bi(6),1,bi(10)),bi(500+10*(sample%10)));high=new BigInteger[]{bi(100),bi(100)};}
        if(sample%2==1){Collections.reverse(rows);var swapped=new ArrayList<ExactLinearProgram.Constraint>();for(var row:rows){var terms=new LinkedHashMap<Integer,BigInteger>();row.terms().forEach((k,v)->terms.put(1-k,v));swapped.add(new ExactLinearProgram.Constraint(terms,row.upper()));}rows=swapped;var temp=high[0];high[0]=high[1];high[1]=temp;}
        var b=budget();String wanted=useMitm?"MITM":"SEPARATOR";
        try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){
            reduction.retainStrideView();while(!reduction.step()){}check(reduction.stride()!=null,"stride absent");views.compileLight();views.addReduced(reduction);
            try(var search=new CountViewSearch(views,b)){search.resume(100000);check(scouts(search)==1,"expected single "+wanted+" "+views.available().stream().map(v->v.name()+Arrays.toString(v.lower())+Arrays.toString(v.upper())).toList()+" "+b.diagnostics());boolean matched=false;for(var s:searches(search))matched|=engine(s).equals(wanted);check(matched,"wrong specialist "+wanted+" "+b.diagnostics());
                isolate(search,wanted);while(!search.step()){}var counts=search.counts();check(counts!=null,"scout no witness "+wanted+" "+b.diagnostics());CountBenchmark.verify(rows,low,high,counts);check(search.candidateOrigin().engine().equals(wanted.toLowerCase(Locale.ROOT)),"lost provenance");
                search.feedback(search.candidateOrigin(),CountViewSearch.CandidateOutcome.UNRESOLVED,8192);search.resume(100000);check(scouts(search)==1,"duplicate scout added");
            }
        }check(b.reservedBytes()==0,"scout leak");if(useMitm)mitm++;else separator++;
    }
    static void gates()throws Exception{
        for(int sample=0;sample<48;sample++){
            var rows=new ArrayList<ExactLinearProgram.Constraint>();eq(rows,Map.of(0,bi(6),1,bi(10)),bi(100));var low=new BigInteger[]{bi(0),bi(0)};var high=new BigInteger[]{bi(12),bi(10)};var b=budget();
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){reduction.retainStrideView();while(!reduction.step()){}views.addReduced(reduction);try(var search=new CountViewSearch(views,b)){search.resume(100000);check(scouts(search)==0,"already-admitted domains scout duplicated");}}check(b.reservedBytes()==0,"gate leak");gateCases++;
        }
        for(int limit=1;limit<=180;limit++){
            var calls=new AtomicInteger();final int cutoff=limit*7;var b=new PlanningBudget(0,20_000_000,128L<<20,()->calls.incrementAndGet()>cutoff,System::nanoTime);
            var rows=new ArrayList<ExactLinearProgram.Constraint>();eq(rows,Map.of(0,bi(6),1,bi(10)),bi(500));var low=new BigInteger[]{bi(0),bi(0)};var high=new BigInteger[]{bi(100),bi(100)};
            try(var reduction=new CountReduction(rows,low,high,b);var views=CountModelViews.create(rows,low,high,b)){reduction.retainStrideView();while(!reduction.step()){}views.addReduced(reduction);try(var search=new CountViewSearch(views,b)){search.resume(100000);while(!search.step()){}}}catch(java.util.concurrent.CancellationException|PlanningBudget.Exhausted expected){}
            check(b.reservedBytes()==0,"scout cancellation leak "+limit+" "+b.reservedBytes());lifecycle++;
        }
    }
    public static void main(String[] args)throws Exception{
        policy();for(int seed=0;seed<48;seed++){specialist(seed,false);specialist(seed,true);}gates();var report=Map.of("policyScenarios",2000,"separatorCases",separator,"mitmCases",mitm,"admissionGuards",gateCases,"cancellationCases",lifecycle,"assertions",checks);
        Files.writeString(Path.of(args[0],"policy-stride-probe.json"),new Gson().toJson(report));System.out.println(report);
    }
}
